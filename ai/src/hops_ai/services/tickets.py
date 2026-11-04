"""HOPS 업무 도구. 읽기 Mock의 TOOL 근거로 저장 성공을 위장하지 않는다."""

import asyncio
from copy import deepcopy
from urllib.parse import quote

import httpx
from pydantic import ValidationError

from hops_ai.contracts.domain import AgentRole
from hops_ai.contracts.wire import (
    AiRunRequest,
    ProblemProposal,
    SpringTicketResult,
    TicketExecution,
    TicketProposal,
)
from hops_ai.harness.context import RunContext
from hops_ai.harness.errors import ComponentNotReady, InputRejected, OutputRejected, PolicyDenied
from hops_ai.services.tools.registry import ToolRegistry, ToolSpec
from hops_ai.settings import Settings

TICKET_TOOLS = frozenset({"create_ticket", "get_ticket_creation_result", "retry_ticket_creation"})
REGISTRY = ToolRegistry(
    ToolSpec(
        "create_ticket",
        "Spring에 티켓 생성 요청",
        frozenset({"inquiryVersion", "problem", "proposal", "messageLinks"}),
    ),
    ToolSpec("get_ticket_creation_result", "실제 저장 결과 조회", frozenset({"problemRef"})),
    ToolSpec(
        "retry_ticket_creation",
        "확인된 미생성 작업 재시도",
        frozenset({"problemRef", "inquiryVersion", "expectedAttempt"}),
    ),
)


class TicketUncertain(Exception):
    """전송 실패·5xx는 미생성의 증거가 아니다."""


class TicketRejected(Exception):
    def __init__(self, code: str):
        self.code = code


def creation_command(
    request: AiRunRequest,
    problem: ProblemProposal,
    proposal: TicketProposal,
    quotes: list[dict[str, str]],
) -> dict:
    """검증된 원문 인용의 구간만 연결한다. 전체 메시지를 공개하는 fallback은 없다."""
    from hops_ai.agents.inquiry.forms import FIELD_LABELS

    ids = set(proposal.evidenceIds + proposal.standardRequestSnapshot.responsibilityEvidenceIds)
    facts = [*problem.facts, *problem.assumptions]
    for fact in facts:
        ids.update(fact.sourceMessageIds)
    links = []
    for message in request.messages:
        if message.id not in ids:
            continue
        texts = [q["quote"] for q in quotes if q["sourceId"] == message.id]
        for fact in facts:
            if message.id not in fact.sourceMessageIds:
                continue
            text = fact.text
            for label in FIELD_LABELS.values():
                if text.startswith(label + ": "):
                    text = text[len(label) + 2 :]
                    break
            texts.append(text)
        ranges = []
        for text in texts:
            start = message.content.find(text) if text.strip() else -1
            if start >= 0:
                ranges.append((start, start + len(text)))
        if message.sender != "USER" or not ranges:
            raise OutputRejected("티켓 출처의 공개 가능한 원문 구간을 확인할 수 없다")
        merged: list[list[int]] = []
        for start, end in sorted(set(ranges)):
            if merged and start <= merged[-1][1]:
                merged[-1][1] = max(merged[-1][1], end)
            else:
                merged.append([start, end])
        if len(merged) > 20:
            raise OutputRejected("원문 구간 한도를 초과했다")
        links.append(
            {
                "messageId": message.id,
                "revision": message.revision,
                "spans": [{"start": start, "end": end} for start, end in merged],
            }
        )
    return {
        "inquiryVersion": request.inquiryVersion,
        "problem": problem.model_dump(mode="json"),
        "proposal": proposal.model_dump(mode="json"),
        "messageLinks": links,
    }


class TicketHTTPClient:
    """주소·인증은 설정에서만 읽으며 모델의 인자를 받지 않는다. 재시도는 Session이 관리한다."""

    def __init__(self, settings: Settings, *, transport: httpx.AsyncBaseTransport | None = None):
        if not settings.spring_base_url or not settings.spring_service_token:
            raise ComponentNotReady("Spring 티켓 서비스 주소·인증")
        self._settings = settings
        self._transport = transport

    def bind(self, request: AiRunRequest, context: RunContext) -> "TicketSession":
        if (context.run_id, context.inquiry_id, context.inquiry_version) != (
            request.runId,
            request.inquiryId,
            request.inquiryVersion,
        ):
            raise InputRejected("티켓 도구와 실행 문맥이 다르다")
        return TicketSession(self._settings, request, context, self._transport)


class TicketSession:
    def __init__(self, settings, request, context, transport):
        self._settings = settings
        self._request = request.model_copy(deep=True)
        self._context = context
        self._transport = transport
        self._commands: dict[str, dict] = {}
        self._results: dict[str, SpringTicketResult] = {}
        self._outcomes: dict[str, TicketExecution] = {}
        self._queries: dict[str, int] = {}
        self._writes: dict[str, int] = {}
        self._uncertain_writes: set[str] = set()
        self._expected_attempt: dict[str, int] = {}

    @property
    def verified_results(self) -> tuple[TicketExecution, ...]:
        return tuple(item.model_copy(deep=True) for item in self._outcomes.values())

    def _available(self) -> bool:
        self._context.ensure_active()
        budget = self._context.budget
        budget.check_limits()
        maximum = min(
            self._request.limits.maxToolCalls,
            budget.max_tool_calls or self._request.limits.maxToolCalls,
        )
        return budget.tool_calls_used < maximum

    async def _send(self, role, name, ref, arguments) -> SpringTicketResult | None:
        self._context.ensure_active()
        self._context.tool_policy.authorize(role, name)
        spec = REGISTRY.get(name)
        if spec is None or spec.argument_problem(arguments) is not None:
            raise InputRejected("등록되지 않았거나 잘못된 티켓 도구 인자다")
        self._context.budget.start_tool_call()
        base = f"/internal/v2/runs/{self._request.runId}/ticket-creations"
        if name == "create_ticket":
            method, path, body = "POST", base, arguments
        elif name == "retry_ticket_creation":
            method, path = "POST", base + "/" + quote(ref, safe="") + "/retries"
            body = {key: value for key, value in arguments.items() if key != "problemRef"}
        else:
            method, path, body = "GET", base + "/" + quote(ref, safe=""), None
        timeout = min(
            self._settings.ticket_http_timeout_seconds, self._context.budget.remaining_seconds()
        )
        try:
            async with asyncio.timeout(timeout):
                async with httpx.AsyncClient(
                    base_url=self._settings.spring_base_url,
                    transport=self._transport,
                    follow_redirects=False,
                    trust_env=False,
                    timeout=timeout,
                    headers={
                        "Authorization": "Bearer "
                        + self._settings.spring_service_token.get_secret_value()
                    },
                ) as client:
                    async with client.stream(method, path, json=body) as response:
                        status = response.status_code
                        data = bytearray()
                        async for chunk in response.aiter_bytes():
                            data.extend(chunk)
                            if len(data) > 65536:
                                raise OutputRejected("티켓 서비스 응답 한도 초과")
        except (httpx.TransportError, TimeoutError):
            raise TicketUncertain from None
        self._context.ensure_active()
        if status in (401, 403):
            raise PolicyDenied("Spring 티켓 서비스 접근이 거부됐다")
        if name == "get_ticket_creation_result" and status == 404:
            return None
        if status >= 500:
            raise TicketUncertain
        if status != 200:
            # 원격 오류 본문에는 신뢰할 수 없는 문자열이 있어 반환·로그에 싣지 않는다.
            if 400 <= status < 500:
                raise TicketRejected("SPRING_REQUEST_REJECTED")
            raise OutputRejected("티켓 서비스가 허용되지 않은 HTTP 응답을 반환했다")
        try:
            result = SpringTicketResult.model_validate_json(data)
        except ValidationError:
            raise OutputRejected("티켓 서비스 결과 형식이 계약과 다르다") from None
        self._validate_result(ref, result)
        self._results[ref] = result.model_copy(deep=True)
        return result

    def _validate_result(self, ref, result):
        if (
            result.runId != self._request.runId
            or result.problemRef != ref
            or result.inquiryVersion != self._request.inquiryVersion
            or result.maxAttempts > min(3, 1 + self._request.limits.maxRetries)
            or (not ref.startswith("new:") and result.problemId != ref)
        ):
            raise OutputRejected("다른 실행·문제·버전의 티켓 결과다")
        if result.outcome == "STORED" and result.responsibleSystemId not in {
            system.id for system in self._request.systems
        }:
            raise OutputRejected("저장 결과의 시스템이 지원 목록에 없다")
        previous = self._results.get(ref)
        if previous is not None and (
            previous.problemId != result.problemId
            or previous.maxAttempts != result.maxAttempts
            or previous.attempt > result.attempt
            or (
                previous.outcome == "STORED"
                and (result.outcome != "STORED" or previous.ticketId != result.ticketId)
            )
        ):
            raise OutputRejected("기존 저장 결과나 시도 이력이 변경됐다")
        if any(
            other != ref
            and (
                saved.problemId == result.problemId
                or result.ticketId is not None
                and saved.ticketId == result.ticketId
            )
            for other, saved in self._results.items()
        ):
            raise OutputRejected("서로 다른 문제의 저장 식별자가 충돌했다")

    async def execute(self, role: AgentRole, command: dict) -> TicketExecution:
        self._context.ensure_active()
        self._context.tool_policy.authorize(role, "create_ticket")
        ref = command["problem"]["problemRef"]
        if ref in self._commands and self._commands[ref] != command:
            raise OutputRejected("같은 업무 도구 요청의 내용을 바꿀 수 없다")
        if (
            ref in self._outcomes
            and self._outcomes[ref].result is not None
            and self._outcomes[ref].result.outcome == "STORED"
        ):
            return self._outcomes[ref].model_copy(deep=True)
        first = ref not in self._commands
        self._commands[ref] = deepcopy(command)
        self._queries.setdefault(ref, 0)
        self._writes.setdefault(ref, 0)
        error = None
        try:
            if first and self._available():
                self._writes[ref] += 1
                self._uncertain_writes.add(ref)
                self._expected_attempt[ref] = 1
                try:
                    await self._send(role, "create_ticket", ref, command)
                    self._uncertain_writes.discard(ref)
                except TicketUncertain:
                    pass  # 생성 재전송 없이 같은 작업의 저장 결과부터 조회한다.
            while self._queries[ref] < self._settings.ticket_max_queries and self._available():
                self._queries[ref] += 1
                try:
                    result = await self._send(
                        role, "get_ticket_creation_result", ref, {"problemRef": ref}
                    )
                except TicketUncertain:
                    result = None
                if result is not None:
                    if result.outcome == "STORED":
                        self._uncertain_writes.discard(ref)
                        break
                    if result.attempt >= self._expected_attempt.get(ref, 1):
                        self._uncertain_writes.discard(ref)
                    maximum = min(self._settings.ticket_max_attempts, result.maxAttempts)
                    retry_budget = min(
                        self._request.limits.maxRetries,
                        self._context.budget.max_run_retries
                        if self._context.budget.max_run_retries is not None
                        else self._request.limits.maxRetries,
                    )
                    if (
                        result.outcome == "NOT_CREATED"
                        and ref not in self._uncertain_writes
                        and result.retryable
                        and result.attempt < maximum
                        and self._writes[ref] < maximum
                        and self._context.budget.retries_used < retry_budget
                        and self._queries[ref] < self._settings.ticket_max_queries
                        and self._available()
                    ):
                        self._writes[ref] += 1
                        self._context.budget.start_tool_retry()
                        self._uncertain_writes.add(ref)
                        self._expected_attempt[ref] = result.attempt + 1
                        try:
                            await self._send(
                                role,
                                "retry_ticket_creation",
                                ref,
                                {
                                    "problemRef": ref,
                                    "inquiryVersion": self._request.inquiryVersion,
                                    "expectedAttempt": result.attempt,
                                },
                            )
                            self._uncertain_writes.discard(ref)
                        except TicketUncertain:
                            pass
                        continue
                    if result.outcome == "NOT_CREATED" and ref not in self._uncertain_writes:
                        if result.retryable:
                            error = "CREATION_RETRY_LIMIT"
                        break
                if self._queries[ref] < self._settings.ticket_max_queries:
                    await asyncio.sleep(
                        min(
                            self._settings.ticket_poll_interval_seconds,
                            self._context.budget.remaining_seconds(),
                        )
                    )
            if not self._available():
                error = "TOOL_BUDGET_EXHAUSTED"
        except TicketRejected as rejected:
            error = rejected.code
        except OutputRejected:
            error = "UPSTREAM_PROTOCOL_ERROR"
        last = (
            None
            if error == "UPSTREAM_PROTOCOL_ERROR" or ref in self._uncertain_writes
            else self._results.get(ref)
        )
        if last is None or last.outcome in ("UNKNOWN", "PENDING"):
            error = error or "CREATION_RESULT_UNCONFIRMED"
        outcome = TicketExecution(problemRef=ref, result=last, errorCode=error)
        self._outcomes[ref] = outcome.model_copy(deep=True)
        return outcome
