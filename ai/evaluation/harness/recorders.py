"""런타임 코드를 바꾸지 않고 역할 호출과 모델 호출을 기록하는 래퍼."""

from pydantic import BaseModel

from evaluation.harness.budget import CallCapExceeded
from evaluation.harness.trace import AnalysisRecord, AssessmentRecord, RoleCall
from hops_ai.agents.base import Agent
from hops_ai.agents.inquiry.extraction import InquiryExtraction
from hops_ai.contracts.domain import AgentRole
from hops_ai.contracts.roles import InquiryResult, KnowledgeResult
from hops_ai.harness.errors import HarnessError, RetrievalUnavailable
from hops_ai.services.llm.client import LLMClient, LLMRequest, LLMResponse
from hops_ai.supervisor.execution import RoleResponse


class CallLog:
    """한 번의 실행(run) 동안 쌓이는 기록."""

    def __init__(self) -> None:
        self.role_calls: list[RoleCall] = []
        self.model_calls = 0
        self.input_tokens = 0
        self.output_tokens = 0
        # 문의 분석 모델이 낸 검색 힌트 원본. 모델 호출마다 문제 순서대로 (키워드, 기능 후보)를
        # 쌓고, 문의 분석 역할이 끝날 때 정리 결과와 짝지어 꺼낸다.
        self.raw_hints: list[list[tuple[tuple[str, ...], tuple[str, ...]]]] = []


class RecordingLLM:
    """모델 호출 횟수와 공급자가 보고한 토큰을 센다. 재시도도 별도 호출로 센다.

    문의 분석 응답이면 정리되기 전의 검색 힌트(키워드·기능 후보)도 남긴다.
    """

    def __init__(self, inner: LLMClient, log: CallLog) -> None:
        self._inner = inner
        self._log = log

    async def generate[T: BaseModel](self, request: LLMRequest[T]) -> LLMResponse[T]:
        self._log.model_calls += 1
        try:
            response = await self._inner.generate(request)
        except CallCapExceeded:
            # 평가 상한이 막은 요청은 모델에 전달되지 않았으므로 호출로 세지 않는다
            self._log.model_calls -= 1
            raise
        usage = getattr(response, "usage", None)
        self._log.input_tokens += getattr(usage, "input_tokens", 0)
        self._log.output_tokens += getattr(usage, "output_tokens", 0)
        parsed = getattr(response, "parsed", None)
        if isinstance(parsed, InquiryExtraction):
            self._log.raw_hints.append(
                [(tuple(item.keywords), tuple(item.featureCandidates)) for item in parsed.problems]
            )
        return response


MAX_FAILURE_DETAIL = 200


def _failure_detail(exc: Exception) -> str | None:
    """하네스 오류의 사유 문구. 그 밖의 예외는 임의 내용이 섞일 수 있어 남기지 않는다."""
    if not isinstance(exc, HarnessError):
        return None
    return str(exc)[:MAX_FAILURE_DETAIL]


def _assessment_record(result: KnowledgeResult) -> AssessmentRecord | None:
    item = result.assessment
    if item is None:
        return None
    return AssessmentRecord(
        problem_ref=item.problemRef,
        verdict=item.verdict,
        supporting_evidence_ids=tuple(item.supportingEvidenceIds),
        missing_information=tuple((entry.kind, entry.ref) for entry in item.missingInformation),
        candidate_causes=tuple((cause.text, cause.confirmed) for cause in item.candidateCauses),
        rationale=item.rationale,
    )


class RecordingAgent:
    """역할 호출의 결과(검색 상태·근거)를 기록하고 결과는 그대로 돌려준다.

    역할의 다른 속성은 원래 역할에 위임한다. Supervisor의 선택지는 역할 규칙(supervisor.choices)이
    State에서 정하므로 기록 래퍼 때문에 달라지지 않는다.
    """

    def __init__(self, inner: Agent, log: CallLog) -> None:
        self._inner = inner
        self._log = log
        self.role: AgentRole = inner.role

    def __getattr__(self, name: str):
        return getattr(self._inner, name)

    async def run(self, request, services):
        try:
            response = await self._inner.run(request, services)
        except RetrievalUnavailable:
            self._log.role_calls.append(
                RoleCall(self.role, search_state="ERROR", failed_with="RetrievalUnavailable")
            )
            raise
        except Exception as exc:
            self._log.role_calls.append(
                RoleCall(
                    self.role,
                    failed_with=type(exc).__name__,
                    failure_detail=_failure_detail(exc),
                )
            )
            raise
        result = response.result if isinstance(response, RoleResponse) else response
        if isinstance(result, KnowledgeResult):
            verified = response.verified_evidence if isinstance(response, RoleResponse) else ()
            self._log.role_calls.append(
                RoleCall(
                    self.role,
                    problem_ref=result.problemRef,
                    search_state=result.search.state,
                    evidence=tuple(verified) or tuple(result.search.evidence),
                    assessment=_assessment_record(result),
                )
            )
        elif isinstance(result, InquiryResult):
            self._log.role_calls.append(RoleCall(self.role, analysis=self._analysis(result)))
        else:
            self._log.role_calls.append(RoleCall(self.role))
        return response

    def _analysis(self, result: InquiryResult) -> tuple[AnalysisRecord, ...]:
        """정리된 검색 힌트를 방금 받은 모델 원본과 문제 순서대로 짝짓는다.

        역할은 추출 결과의 앞쪽 문제부터 순서대로 분석을 만들므로 같은 위치끼리 대응한다.
        원본이 없으면(모델을 거치지 않은 역할) 원본 칸은 비워 둔다.
        """
        raw = self._log.raw_hints.pop() if self._log.raw_hints else []
        records = []
        for index, item in enumerate(result.analysis):
            raw_keywords, raw_features = raw[index] if index < len(raw) else ((), ())
            records.append(
                AnalysisRecord(
                    problem_ref=item.problemRef,
                    keywords=tuple(item.keywords),
                    feature_candidates=tuple(item.featureCandidates),
                    raw_keywords=raw_keywords,
                    raw_feature_candidates=raw_features,
                )
            )
        return tuple(records)
