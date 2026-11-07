"""검색 근거 충분성 판정(Assessment)의 검증. State를 수정하지 않는다.

모델은 판정을 제안만 한다. Knowledge 에이전트가 모델 출력을 받을 때와 Supervisor가 병합할 때 같은
규칙을 적용하며, 어느 쪽 패키지도 불러오지 않는다(설계 문서 7.2절).

- 근거 ID는 이번 검색의 검증된 근거 안에서만 고른다.
- SUFFICIENT는 직원 공개(EMPLOYEE) 근거 ID를 가져야 한다. 안내를 만들 수 없는 판정은 거부한다.
- missingInformation은 닫힌 어휘다. 양식 필드 이름(그 의도가 묻는 필드)이거나 허용된 관계
  확인 항목 ID만 받고 자유 문장은 받지 않는다. 허용된 확인 항목은 이번 검색과 함께 조회한 직원 공개
  승인 항목(`check_item_ids`)이다. 조회하지 않았거나 항목이 없으면 CHECK_ITEM은 비어 있어야 한다.
  nextChecks도 같은 허용 항목 안이어야 한다(프롬프트는 직원 프로필에서 빈 목록을 요구한다).
- candidateCauses는 항상 confirmed=false이고 근거를 가진다. 과거 사례·관계 의존을 현재 원인으로
  확정하지 않는다.
- rationale은 내부용이다. 사용자 응답·질문·티켓에 쓰지 않는다.
"""

from collections.abc import Iterable, Sequence

from hops_ai.contracts.roles import Assessment, RelationNeighbors
from hops_ai.contracts.state import SupervisorState
from hops_ai.contracts.wire import Intent, KnowledgeEvidence
from hops_ai.harness.errors import OutputRejected
from hops_ai.rules.intent_policy import policy_for

# 판정 하나가 담을 수 있는 항목 수와 문장 길이. 모델이 길게 쓴 값은 거부한다.
MAX_MISSING_INFORMATION = 5
# 부족한 정보 중 관계 확인 항목의 수. 한 번에 사용자에게 물을 수 있는 양이 정해져 있고(질문 묶음
# 3개), 프롬프트의 최대 3개 규칙을 모델이 어긴 사례가 있어 코드로 강제한다.
MAX_CHECK_ITEM_REFS = 3
MAX_CANDIDATE_CAUSES = 3
MAX_CAUSE_TEXT = 200
MAX_RATIONALE = 500


def allowed_fields(intent: Intent) -> tuple[str, ...]:
    """그 의도의 양식이 묻는 필드 이름(필수·가이드 우선·인계 필드의 합, 순서 유지)."""
    policy = policy_for(intent)
    names: list[str] = []
    for group in (policy.required_fields, policy.guide_first_fields, policy.handoff_fields):
        for name in group or ():
            if name not in names:
                names.append(name)
    return tuple(names)


def check_item_ids(relations: RelationNeighbors | None) -> frozenset[str]:
    """조회한 관계에서 판정이 지목할 수 있는 확인 항목 ID(직원 공개로 승인된 항목)."""
    if relations is None:
        return frozenset()
    return frozenset(check.id for symptom in relations.symptoms for check in symptom.checkItems)


def approved_questions(relations: RelationNeighbors | None) -> dict[str, str]:
    """조회한 관계의 확인 항목 ID → 직원에게 물을 승인된 질문 문구(항목의 첫 질문).

    질문이 없는 항목은 뺀다. 직원 상담의 관계에는 직원 공개로 승인된 항목과 보이는 질문만 있으므로
    이 문구만 직원 질문에 쓴다(P12). 같은 항목이 여러 증상에 있으면 처음 것을 쓴다.
    """
    items: dict[str, str] = {}
    if relations is None:
        return items
    for symptom in relations.symptoms:
        for check in symptom.checkItems:
            if check.questions:
                items.setdefault(check.id, check.questions[0].text)
    return items


def limit_check_items(assessment: Assessment) -> Assessment:
    """확인 항목 참조를 앞에서부터 MAX_CHECK_ITEM_REFS개만 남긴다. 판정 전체를 거부하지 않는다."""
    kept: list = []
    checks = 0
    for item in assessment.missingInformation:
        if item.kind == "CHECK_ITEM":
            checks += 1
            if checks > MAX_CHECK_ITEM_REFS:
                continue
        kept.append(item)
    if len(kept) == len(assessment.missingInformation):
        return assessment
    return assessment.model_copy(update={"missingInformation": kept})


def no_match_assessment(problem_ref: str) -> Assessment:
    """검색 결과가 0건일 때 코드가 확정하는 판정. 모델을 부르지 않는다."""
    return Assessment(
        problemRef=problem_ref,
        verdict="NO_MATCH",
        supportingEvidenceIds=[],
        missingInformation=[],
        candidateCauses=[],
        nextChecks=[],
        rationale="검색 결과가 없다",
    )


def _unique(values: Iterable[str]) -> bool:
    items = list(values)
    return len(items) == len(set(items))


def validate_assessment(
    assessment: Assessment,
    *,
    intent: Intent,
    search_state: str,
    evidence: Sequence[KnowledgeEvidence],
    allowed_check_items: frozenset[str] = frozenset(),
) -> None:
    """판정이 이번 검색 결과와 어휘 규칙에 맞는지 확인한다.

    Args:
        intent: 판정 대상 문제의 의도. 묻을 수 있는 양식 필드를 정한다.
        search_state: 이번 검색의 상태. ERROR는 평가 대상이 아니다.
        evidence: 이번 검색의 검증된 근거. 판정이 인용할 수 있는 근거의 전부다.
        allowed_check_items: 이번 실행에서 허용된(직원 공개로 승인된) 관계 확인 항목 ID.

    Raises:
        OutputRejected: 규칙을 어긴 경우.
    """
    if search_state == "ERROR":
        raise OutputRejected("검색 오류는 평가하지 않는다")
    by_id = {item.id: item for item in evidence}
    supporting = assessment.supportingEvidenceIds
    if search_state == "EMPTY":
        if (
            assessment.verdict != "NO_MATCH"
            or supporting
            or assessment.candidateCauses
            or assessment.missingInformation
            or assessment.nextChecks
        ):
            raise OutputRejected("검색 0건은 근거 없는 NO_MATCH만 허용한다")
    if not _unique(supporting) or not set(supporting) <= by_id.keys():
        raise OutputRejected("판정의 근거 ID가 이번 검색 결과에 없거나 중복됐다")
    if assessment.verdict == "NO_MATCH" and supporting:
        raise OutputRejected("NO_MATCH는 근거 ID를 가질 수 없다")
    if assessment.verdict in ("SUFFICIENT", "PARTIAL") and not supporting:
        raise OutputRejected("SUFFICIENT·PARTIAL은 근거 ID가 필요하다")
    if assessment.verdict == "SUFFICIENT" and not any(
        by_id[evidence_id].accessScope == "EMPLOYEE" for evidence_id in supporting
    ):
        raise OutputRejected("SUFFICIENT는 직원 공개 근거 ID가 필요하다")

    missing = assessment.missingInformation
    if len(missing) > MAX_MISSING_INFORMATION or not _unique(f"{m.kind}:{m.ref}" for m in missing):
        raise OutputRejected("부족한 정보의 개수가 많거나 중복됐다")
    if sum(item.kind == "CHECK_ITEM" for item in missing) > MAX_CHECK_ITEM_REFS:
        raise OutputRejected("부족한 정보의 확인 항목이 너무 많다")
    fields = set(allowed_fields(intent))
    for item in missing:
        vocabulary = fields if item.kind == "FIELD" else allowed_check_items
        if item.ref not in vocabulary:
            raise OutputRejected("부족한 정보가 닫힌 어휘 밖이다")

    causes = assessment.candidateCauses
    if len(causes) > MAX_CANDIDATE_CAUSES:
        raise OutputRejected("가능 원인의 개수가 많다")
    for cause in causes:
        if (
            cause.confirmed
            or not cause.text.strip()
            or len(cause.text) > MAX_CAUSE_TEXT
            or not cause.evidenceIds
            or not _unique(cause.evidenceIds)
            or not set(cause.evidenceIds) <= by_id.keys()
        ):
            raise OutputRejected("가능 원인이 확정됐거나 근거가 없거나 규칙을 어겼다")

    checks = [item.checkItemId for item in assessment.nextChecks]
    if not _unique(checks) or not set(checks) <= allowed_check_items:
        raise OutputRejected("다음 확인 항목이 허용된 관계 확인 항목이 아니다")
    if not assessment.rationale.strip() or len(assessment.rationale) > MAX_RATIONALE:
        raise OutputRejected("판정 이유가 비었거나 길다")


def answerable(state: SupervisorState, ref: str) -> bool:
    """그 문제를 승인된 가이드로 답할 수 있는지.

    판정이 SUFFICIENT이고 그 문제의 안내가 판정이 인용한 근거로 만들어졌을 때다. 안내문은 직원 공개
    가이드 본문으로만 조립하므로 판정이 충분해도 안내가 없으면 답할 수 없다.
    """
    assessment = state["assessments"].get(ref)
    guide = state["guide"]
    return (
        assessment is not None
        and assessment.verdict == "SUFFICIENT"
        and guide is not None
        and guide.problemRef == ref
        and bool(set(guide.evidenceIds) & set(assessment.supportingEvidenceIds))
    )
