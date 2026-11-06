"""평가 실행의 기록. 검사기는 이 기록만 보고 판정한다.

AiResult에는 검색 상태·모델 호출 수 같은 내부 값이 없으므로, 하네스가 실행 중에 따로 기록한다
(`recorders.py`). 런타임 코드는 바꾸지 않는다.
"""

from dataclasses import dataclass
from typing import Literal

from hops_ai.contracts.domain import AgentRole
from hops_ai.contracts.wire import AiResult, AiRunRequest, KnowledgeEvidence

type SearchState = Literal["FOUND", "EMPTY", "ERROR"]


@dataclass(frozen=True)
class AnalysisRecord:
    """Inquiry가 문제 하나에 낸 검색 힌트. 모델이 낸 원본과 규칙이 정리한 결과를 함께 남긴다.

    원본과 정리 결과를 비교하면 규칙이 얼마나 걸러냈는지, 모델이 쓸 만한 키워드를 냈는지
    사람이 볼 수 있다. 검사기는 이 값을 판정에 쓰지 않는다.
    """

    problem_ref: str
    keywords: tuple[str, ...]
    feature_candidates: tuple[str, ...]
    raw_keywords: tuple[str, ...] = ()
    raw_feature_candidates: tuple[str, ...] = ()


@dataclass(frozen=True)
class AssessmentRecord:
    """Knowledge가 한 검색에 대해 낸 충분성 판정. 검사기(P10·P11)가 판정에 쓴다.

    판정을 낸 호출의 검색 근거는 같은 `RoleCall.evidence`에 있다. 가능 원인은 (문장, 확정 여부)
    쌍으로, 부족한 정보는 (종류, 참조) 쌍으로 남긴다. rationale은 내부용이라 사람이 읽는 데만 쓴다.
    """

    problem_ref: str
    verdict: str
    supporting_evidence_ids: tuple[str, ...] = ()
    missing_information: tuple[tuple[str, str], ...] = ()
    candidate_causes: tuple[tuple[str, bool], ...] = ()
    rationale: str = ""


@dataclass(frozen=True)
class RoleCall:
    """한 번의 역할 호출. 검색 역할은 검색 상태·근거·판정을, 문의 분석 역할은 검색 힌트를 남긴다."""

    role: AgentRole
    # 검색이 다룬 문제. 알 수 없으면 None이다(예외로 끝난 호출 등).
    problem_ref: str | None = None
    search_state: SearchState | None = None
    evidence: tuple[KnowledgeEvidence, ...] = ()
    # 역할이 결과 없이 예외로 끝났다면 예외 이름.
    failed_with: str | None = None
    # 실패한 예외의 사유 문구(하네스 오류의 detail, 없으면 고정 문구). 어느 검증이 거부했는지
    # 구분하려고 남긴다. 합성 시나리오에서만 저장하며 길이는 자른다.
    failure_detail: str | None = None
    # 문의 분석 역할이 문제마다 낸 검색 힌트. 다른 역할은 비어 있다.
    analysis: tuple[AnalysisRecord, ...] = ()
    # 검색 역할이 낸 충분성 판정. 평가를 하지 않았거나 "평가 없음"이면 None이다.
    assessment: AssessmentRecord | None = None
    # 질문 행동에서 문장을 누가 썼는지(FIXED, MODEL, FIXED_FALLBACK)와 대체 사유 코드.
    question_source: str | None = None
    question_fallback: str | None = None
    # 검색 역할이 함께 조회한 관계 데이터의 상태(FOUND, EMPTY, ERROR)와 받은 확인 항목 ID.
    # 관계를 조회하지 않았으면 상태가 None이다.
    relation_state: str | None = None
    relation_check_ids: tuple[str, ...] = ()


@dataclass(frozen=True)
class TurnTrace:
    """한 번의 실행(run)과 그 뒤 사용자 응답."""

    request: AiRunRequest
    result: AiResult | None
    role_calls: tuple[RoleCall, ...]
    model_calls: int
    # 실행이 결과 없이 예외로 끝났다면 예외 이름.
    error: str | None = None
    # 이 실행의 질문에 사용자가 보낸 응답 문장.
    user_reply: str | None = None
    # 사용자가 "모른다"고 답한 (문제, 필드). 같은 문제의 이후 질문에서 다시 묻지 않아야 한다.
    unknown_after: tuple[tuple[str, str], ...] = ()
    # 이 실행의 결과에서 새 문제 참조("new:...")에 서버가 부여한 문제 ID.
    ref_map: tuple[tuple[str, str], ...] = ()
    # 공급자가 보고한 토큰 합계. 실패한 원격 호출의 미보고 사용량은 포함하지 않는다.
    input_tokens: int = 0
    output_tokens: int = 0
    elapsed_seconds: float = 0.0


@dataclass(frozen=True)
class RunTrace:
    """한 시나리오의 전체 실행 기록."""

    scenario_id: str
    turns: tuple[TurnTrace, ...]
    # 직원에게 보이면 안 되는 추가 문구(시나리오 정답의 금지 문구).
    extra_forbidden_phrases: tuple[str, ...] = ()
    # 질문이 남은 채 최대 턴 수에 도달해 재생이 잘렸다. 끝까지 재생한 기록이 아니다.
    truncated: bool = False
