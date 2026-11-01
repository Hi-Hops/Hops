"""LangGraph 실행 그래프 자리. 모델 판단·허용 경로 검증·예산 공유는 구현 전이다."""

from hops_ai.harness.errors import ComponentNotReady
from hops_ai.services.shared import SharedServices


class Supervisor:
    async def analyze(self, request: object, services: SharedServices) -> object:
        raise ComponentNotReady("model-based supervisor")
