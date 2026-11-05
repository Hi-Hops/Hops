"""Spring이 AI 결과를 받아 다음 실행 입력을 만드는 일을 흉내 내는 얇은 대역.

**이 모듈의 동작은 하네스의 가정이다.** 현재 Spring은 문제·질문을 저장해 다음 실행 입력에 돌려주지
않는다(#189에서 구현). 그래서 여러 번 오가는 시나리오를 재생하려고 최소한의 규칙을 여기서 흉내 낸다.

- 새 문제 참조(`new:...`)에는 문제 ID를 부여한다.
- 질문이 있었던 문제는 질문 횟수를 올린다.
- 문제의 표준 요청은 AI가 제안한 문제 제안(사실·미확인·세부 정보)을 그대로 옮긴다.
- 문제의 사실이 인용한 메시지를 그 문제에 연결한다(실제 서비스의 메시지-문제 연결 대신).
- 사용자 응답을 새 메시지로 추가하고 입력 버전을 올린다. 사용자가 답한 뒤의 새 실행에서 문제는
  `ANALYZING`이다.

실제 Spring 구현이 생기면 이 가정을 실제 동작과 대조해 바꾼다.
"""

from datetime import UTC, datetime, timedelta
from typing import Any
from uuid import NAMESPACE_URL, uuid5

from evaluation.harness.catalog import SYSTEMS
from evaluation.harness.scenario import Scenario
from hops_ai.contracts.wire import AiResult, AiRunRequest

DEFAULT_LIMITS = {
    "maxModelCalls": 8,
    "maxInputTokens": 60_000,
    "maxOutputTokens": 8_000,
    "maxToolCalls": 3,
    "maxRetries": 1,
}


def _time(moment: datetime) -> str:
    return moment.strftime("%Y-%m-%dT%H:%M:%S.%fZ")


class SpringStub:
    def __init__(
        self,
        scenario: Scenario,
        *,
        limits: dict[str, int] | None = None,
        now: datetime | None = None,
    ) -> None:
        self._scenario = scenario
        self._limits = dict(limits or DEFAULT_LIMITS)
        self._clock = now or datetime(2026, 11, 5, 0, 0, 0, tzinfo=UTC)
        self._counter = 0
        self._inquiry_id = self._new_id("inquiry")
        self._actor_id = self._new_id("actor")
        self._messages: list[dict[str, Any]] = []
        self._problems: dict[str, dict[str, Any]] = {}
        self._version = 1

    # -- 식별자·시각 ---------------------------------------------------------
    def _new_id(self, kind: str) -> str:
        self._counter += 1
        return str(
            uuid5(NAMESPACE_URL, f"hops-eval/{self._scenario.scenario_id}/{kind}/{self._counter}")
        )

    def _tick(self) -> str:
        self._clock += timedelta(seconds=30)
        return _time(self._clock)

    # -- 메시지 --------------------------------------------------------------
    def _add_message(self, sender: str, content: str, problem_ids: list[str]) -> str:
        message_id = self._new_id("message")
        self._messages.append(
            {
                "id": message_id,
                "revision": 1,
                "sequenceNo": len(self._messages) + 1,
                "sender": sender,
                "content": content,
                "problemIds": problem_ids,
                "createdAt": self._tick(),
            }
        )
        return message_id

    # -- 요청 만들기 ---------------------------------------------------------
    def _request(self, problem_ids: list[str]) -> AiRunRequest:
        return AiRunRequest.model_validate(
            {
                "contractVersion": "hops-system-1",
                "runId": self._new_id("run"),
                "kind": "INQUIRY_ANALYSIS",
                "inquiryId": self._inquiry_id,
                "inquiryVersion": self._version,
                "answerMessageId": self._new_id("answer"),
                "problemIds": problem_ids,
                "messages": [
                    {**message, "problemIds": list(message["problemIds"])}
                    for message in self._messages
                ],
                "problems": [dict(problem) for problem in self._problems.values()],
                "systems": [dict(system) for system in SYSTEMS],
                "accessContext": {
                    "actorId": self._actor_id,
                    "purpose": "EMPLOYEE_HELP",
                    "systemIds": [system["id"] for system in SYSTEMS],
                    "allowedDocumentScopes": ["EMPLOYEE"],
                    "policyRevision": 1,
                },
                "existingWorkItems": [],
                "workRecord": None,
                # 실행 기한은 실제 시계로 검사되므로 지금 시각 기준으로 둔다.
                "expiresAt": _time(datetime.now(UTC) + timedelta(hours=1)),
                "limits": self._limits,
            }
        )

    def first_request(self) -> AiRunRequest:
        """문의의 첫 메시지로 만든 첫 실행 입력. 아직 문제는 없다."""
        self._add_message("USER", self._scenario.first_message, [])
        return self._request([])

    # -- 결과 반영 -----------------------------------------------------------
    def advance(
        self, result: AiResult, reply_text: str
    ) -> tuple[AiRunRequest, tuple[tuple[str, str], ...]]:
        """이번 결과를 반영하고 사용자 응답으로 다음 실행 입력을 만든다.

        Returns:
            다음 실행 입력과, 이번 결과의 새 문제 참조에 부여한 (참조, 문제 ID).
        """
        ref_map: dict[str, str] = {}

        def resolve(ref: str) -> str:
            if ref.startswith("new:"):
                if ref not in ref_map:
                    ref_map[ref] = self._new_id("problem")
                return ref_map[ref]
            return ref

        asked: dict[str, int] = {}
        for question in result.questions:
            problem_id = resolve(question.problemRef)
            asked[problem_id] = asked.get(problem_id, 0) + 1
        for proposal in result.problemProposals:
            problem_id = resolve(proposal.problemRef)
            previous = self._problems.get(problem_id)
            count = (previous["questionCount"] if previous else 0) + asked.get(problem_id, 0)
            self._problems[problem_id] = {
                "id": problem_id,
                "summary": proposal.summary,
                "intent": proposal.intent,
                "processingType": proposal.processingType,
                "reportedSystemId": proposal.reportedSystemCandidateId,
                "reportedFeatureId": None,
                "state": "OUT_OF_SCOPE" if proposal.intent == "OUT_OF_SCOPE" else "ANALYZING",
                "questionCount": min(count, 5),
                "standardRequest": {
                    "summary": proposal.summary,
                    "confirmedFacts": [fact.model_dump() for fact in proposal.facts],
                    "assumptions": [item.model_dump() for item in proposal.assumptions],
                    "unknowns": list(proposal.unknowns),
                    "responsibleSystemCandidateId": None,
                    "responsibilityEvidenceIds": [],
                    "details": proposal.details.model_dump(),
                    "knowledge": [],
                },
                "resolutionContext": None,
            }
            cited = {ref for fact in proposal.facts for ref in fact.sourceMessageIds}
            for message in self._messages:
                if message["id"] in cited and problem_id not in message["problemIds"]:
                    message["problemIds"].append(problem_id)
        active = [
            pid for pid, problem in self._problems.items() if problem["state"] != "OUT_OF_SCOPE"
        ]
        answered = list(asked) or active
        if result.answer:
            self._add_message("AI", result.answer, list(answered))
        self._version += 1
        self._add_message("USER", reply_text, list(answered))
        return self._request(active), tuple(ref_map.items())
