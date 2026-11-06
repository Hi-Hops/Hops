"""문제별 분석(검색 키워드·기능 후보)의 정규화와 검증. State를 수정하지 않는다.

Inquiry의 모델은 문제마다 검색에 쓸 핵심어와 관련 기능 후보를 제안한다. 둘 다 사실이 아니라
검색 힌트이고 사용자에게 보이지 않는다. 이 모듈은 에이전트가 모델 출력을 정리할 때와 Supervisor가
병합할 때 같은 규칙을 쓰며, 어느 쪽 패키지도 불러오지 않는다.

한도의 근거는 관계 조회·지식 검색 계약이다(contracts/agents/KNOWLEDGE_TOOLS.md).
- 관계 조회 API는 키워드 1~20개(각 1~50자)와 기능 ID 1~10개를 받는다. 접근 시스템에 없는 기능
  ID는 요청 전체를 거부하므로(FEATURE_NOT_ALLOWED) 후보는 코드가 미리 걸러야 한다.
- 점수는 기능 일치 0.5에 키워드 일치 비율 0.5를 더한다. 키워드가 많으면 일치 하나의 몫이 줄어
  틀릴 수 있는 기능 추측이 순위를 좌우하고, 기능 후보 하나마다 0.5가 붙는다. 그래서 한도를
  API 상한보다 훨씬 작게 둔다.
- 증상 이름과의 비교는 형태소 분석도 동의어도 없는 부분 문자열이다. 사용자 글에 없는 단어는
  맞출 근거가 없고 분모만 키우므로 키워드는 사용자 메시지에 실제로 있는 구절만 쓴다.
"""

from collections.abc import Iterable, Sequence

from hops_ai.contracts.roles import ProblemAnalysis
from hops_ai.contracts.state import SupervisorState
from hops_ai.contracts.wire import AiRunRequest, ProblemProposal
from hops_ai.harness.errors import OutputRejected

# 문제 하나의 키워드 상한. 관계 조회 API 상한은 20개다.
MAX_KEYWORDS = 5
# 키워드 길이. 지식 검색은 2자 미만 토큰을 버리고, 증상 이름에 통째로 들어 있어야 일치하므로
# 긴 구절은 거의 맞지 않는다. API 상한은 50자다.
MIN_KEYWORD_LENGTH = 2
MAX_KEYWORD_LENGTH = 20
# 문제 하나의 기능 후보 상한. 시스템마다 기능이 3~4개뿐이라 더 늘리면 거르는 효과가 없다.
# API 상한은 10개다.
MAX_FEATURE_CANDIDATES = 2


def _squash(text: str) -> str:
    """공백 차이와 대소문자를 무시하고 비교하려고 정규화한다."""
    return " ".join(text.split()).casefold()


def user_texts(request: AiRunRequest) -> list[str]:
    """키워드가 실제로 있어야 하는 원문. 사용자(USER) 메시지만 쓴다."""
    return [_squash(message.content) for message in request.messages if message.sender == "USER"]


def clean_keyword(keyword: str) -> str | None:
    """공백을 정리한 키워드. 길이 범위를 벗어나면 None이다."""
    cleaned = " ".join(keyword.split())
    if MIN_KEYWORD_LENGTH <= len(cleaned) <= MAX_KEYWORD_LENGTH:
        return cleaned
    return None


def is_grounded(keyword: str, texts: Iterable[str]) -> bool:
    """키워드가 사용자 메시지 중 하나에 부분 문자열로 들어 있는지."""
    needle = _squash(keyword)
    return bool(needle) and any(needle in text for text in texts)


def normalize_keywords(raw: Sequence[str], texts: Sequence[str]) -> tuple[str, ...]:
    """모델이 낸 키워드를 정리한다. 규칙에 맞지 않는 값은 버리고 전체를 거부하지 않는다.

    공백 정리, 길이 범위(2~20자), 사용자 메시지에 실제로 있는 구절, 중복 제거를 적용하고
    앞에서부터 MAX_KEYWORDS개까지만 남긴다.
    """
    kept: list[str] = []
    seen: set[str] = set()
    for value in raw:
        keyword = clean_keyword(value)
        if keyword is None or not is_grounded(keyword, texts):
            continue
        key = keyword.casefold()
        if key in seen:
            continue
        seen.add(key)
        kept.append(keyword)
        if len(kept) == MAX_KEYWORDS:
            break
    return tuple(kept)


def allowed_feature_ids(request: AiRunRequest, system_id: str | None) -> frozenset[str]:
    """기능 후보로 인정하는 기능 ID.

    접근할 수 있는 시스템이 확정된 문제에 한해 그 시스템의 기능이다. 시스템이 정해지지
    않았거나 접근 범위 밖이면 비어 있다. 기능은 한 시스템에 속하므로 시스템이 불확실한
    문제에서 기능을 짚으면 엉뚱한 시스템의 증상이 올라온다.
    """
    if system_id is None or system_id not in request.accessContext.systemIds:
        return frozenset()
    return frozenset(
        feature.id
        for system in request.systems
        if system.id == system_id
        for feature in system.features
    )


def select_feature_candidates(raw: Sequence[str], allowed: frozenset[str]) -> tuple[str, ...]:
    """허용된 기능 ID만 중복 없이 앞에서부터 MAX_FEATURE_CANDIDATES개까지 남긴다."""
    kept: list[str] = []
    for value in raw:
        if value in allowed and value not in kept:
            kept.append(value)
            if len(kept) == MAX_FEATURE_CANDIDATES:
                break
    return tuple(kept)


def validate_analysis(
    state: SupervisorState, analysis: ProblemAnalysis, proposal: ProblemProposal
) -> None:
    """병합 단계의 재검증. 에이전트가 이미 정리했으므로 어긋나면 거부한다.

    Raises:
        OutputRejected: 개수·길이·중복 위반, 사용자 메시지에 없는 키워드, 허용되지 않은 기능 후보.
    """
    texts = user_texts(state["input"])
    keys = [keyword.casefold() for keyword in analysis.keywords]
    if (
        len(analysis.keywords) > MAX_KEYWORDS
        or len(keys) != len(set(keys))
        or any(clean_keyword(keyword) != keyword for keyword in analysis.keywords)
    ):
        raise OutputRejected("분석의 키워드가 개수·길이·중복 규칙을 어겼다")
    if any(not is_grounded(keyword, texts) for keyword in analysis.keywords):
        raise OutputRejected("분석의 키워드가 사용자 메시지에 없다")
    candidates = analysis.featureCandidates
    if len(candidates) > MAX_FEATURE_CANDIDATES or len(candidates) != len(set(candidates)):
        raise OutputRejected("분석의 기능 후보가 개수·중복 규칙을 어겼다")
    allowed = allowed_feature_ids(state["input"], proposal.reportedSystemCandidateId)
    if not set(candidates) <= allowed:
        raise OutputRejected("분석의 기능 후보가 허용된 시스템의 기능이 아니다")
