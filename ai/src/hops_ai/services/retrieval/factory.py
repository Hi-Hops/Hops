"""설정에 따라 Knowledge 검색 자료를 고른다. 연결하지 않는 설정이면 None이다."""

from hops_ai.services.retrieval.fixture_source import FixtureKnowledgeSource
from hops_ai.services.retrieval.knowledge_source import KnowledgeSource
from hops_ai.services.retrieval.spring_source import SpringKnowledgeSource
from hops_ai.settings import KnowledgeSourceKind, Settings


def knowledge_source_for(settings: Settings) -> KnowledgeSource | None:
    """none이면 연결하지 않고(Knowledge는 연결 전으로 남는다), fixture면 개발용 고정 자료다.

    spring이면 Spring 내부 API로 DB의 승인 문서를 읽는다.
    주소·서비스 토큰이 없으면 시작을 거부한다.
    """
    if settings.knowledge_source is KnowledgeSourceKind.FIXTURE:
        return FixtureKnowledgeSource()
    if settings.knowledge_source is KnowledgeSourceKind.SPRING:
        return SpringKnowledgeSource(settings)
    return None
