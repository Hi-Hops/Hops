"""실행과 구독을 분리하는 유실 허용 이벤트 전달. 최종 정본은 RunLedger다."""

import asyncio
from contextlib import contextmanager
from typing import Literal

from hops_ai.contracts.wire import AiEvent, AiResult, ProgressPayload

type Role = Literal["SUPERVISOR", "INQUIRY", "KNOWLEDGE", "TICKET", "CURATOR"]

# 모델 출력이나 도구 원문을 공개 진행 문구로 사용하지 않는다.
SUMMARIES: dict[Role, str] = {
    "SUPERVISOR": "문의 내용을 확인하고 있어요",
    "INQUIRY": "문의에 필요한 정보를 정리하고 있어요",
    "KNOWLEDGE": "관련 지식을 확인하고 있어요",
    "TICKET": "담당자에게 전달할 요청을 정리하고 있어요",
    "CURATOR": "해결 사례 초안을 정리하고 있어요",
}


class RunEventHub:
    """프로세스 내 실시간 fan-out. 과거 진행 replay·영속 큐는 제공하지 않는다."""

    def __init__(self) -> None:
        self._subscribers: dict[str, set[asyncio.Queue[AiEvent | None]]] = {}

    @contextmanager
    def subscribe(self, run_id: str):
        if sum(map(len, self._subscribers.values())) >= 256:
            raise OverflowError("전체 구독 상한")
        subscribers = self._subscribers.setdefault(run_id, set())
        if len(subscribers) >= 8:
            raise OverflowError("실행 구독 상한")
        queue: asyncio.Queue[AiEvent | None] = asyncio.Queue(maxsize=64)
        subscribers.add(queue)
        try:
            yield queue
        finally:
            subscribers.remove(queue)
            if not subscribers:
                del self._subscribers[run_id]

    def publish(self, event: AiEvent) -> None:
        for queue in self._subscribers.get(event.runId, ()):
            if queue.full():
                # 느린 소비자가 실행을 막지 않는다. 종료 후 snapshot으로 복구한다.
                while not queue.empty():
                    queue.get_nowait()
                queue.put_nowait(None)
            else:
                queue.put_nowait(event)


class RunEvents:
    """한 실행의 순번과 종료 장벽. 역할이나 모델에 순번 발급을 맡기지 않는다."""

    def __init__(self, run_id: str, hub: RunEventHub) -> None:
        self.run_id = run_id
        self.hub = hub
        self.seq = 0
        self.closed = False

    def progress(self, role: Role) -> None:
        if self.closed:
            return
        self.seq += 1
        self.hub.publish(
            AiEvent(
                contractVersion="hops-system-1",
                runId=self.run_id,
                seq=self.seq,
                eventType="progress",
                payload=ProgressPayload(role=role, displaySummary=SUMMARIES[role]),
            )
        )

    def prepare_result(self, result: AiResult) -> AiEvent:
        if self.closed:
            raise ValueError("종료 결과는 한 번만 준비합니다")
        self.closed = True
        self.seq += 1
        return AiEvent(
            contractVersion="hops-system-1",
            runId=self.run_id,
            seq=self.seq,
            eventType="result",
            payload=result,
        )
