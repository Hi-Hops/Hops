"""안내 뒤 사용자가 미해결로 응답한 문제(#189 ④). State를 수정하지 않는 순수 함수다.

Spring은 미해결 응답을 받으면 같은 문제로 재분석을 접수하며 입력에 `resolutionContext`
(state=UNRESOLVED)와 이미 안내한 근거(`standardRequest.knowledge`)를 싣는다. 같은 근거로 같은
안내를 되풀이하면 사용자는 해결하지 못한 채 같은 말을 다시 듣는다. 그래서 이미 안내한 근거는 이번
검색 결과에서 빼고, 다른 근거가 없으면 담당자 검토(티켓)로 넘긴다(설계 문서 5.3절 티켓 (c)).
"""

from collections.abc import Iterable

from hops_ai.contracts.state import SupervisorState
from hops_ai.contracts.wire import InputProblem, KnowledgeEvidence

type DocumentKey = tuple[str, int]


def _original(state: SupervisorState, ref: str) -> InputProblem | None:
    return next((item for item in state["input"].problems if item.id == ref), None)


def is_unresolved(state: SupervisorState, ref: str) -> bool:
    """그 문제가 안내 뒤 사용자가 미해결로 응답한 문제인지. 서버가 입력에 표시한 것만 믿는다."""
    original = _original(state, ref)
    context = original.resolutionContext if original is not None else None
    return context is not None and context.state == "UNRESOLVED"


def document_key(evidence: KnowledgeEvidence) -> DocumentKey:
    """근거가 가리키는 문서와 버전. 근거 ID는 검색마다 달라질 수 있어 비교에 쓰지 않는다."""
    return (evidence.documentId, evidence.documentVersion)


def guided_documents(state: SupervisorState, ref: str) -> frozenset[DocumentKey]:
    """미해결 응답을 받은 문제에 이미 안내한 문서와 버전. 아니거나 알 수 없으면 비어 있다."""
    original = _original(state, ref)
    if original is None or not is_unresolved(state, ref) or original.standardRequest is None:
        return frozenset()
    return frozenset(document_key(item) for item in original.standardRequest.knowledge)


def without_guided[T](state: SupervisorState, ref: str, items: Iterable[T], key) -> list[T]:
    """검색 결과에서 이미 안내한 문서·버전을 뺀다. key는 항목의 근거를 꺼낸다."""
    excluded = guided_documents(state, ref)
    return [item for item in items if document_key(key(item)) not in excluded]
