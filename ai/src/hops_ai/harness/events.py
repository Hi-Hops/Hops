"""한 실행이 내보내는 이벤트(진행·답변 조각·최종 결과)를 순서대로 모아 두고 흘려 준다.

계약 규칙: 최종 결과(AiResult)는 한 번만 보내고, 그 뒤에 도착한 진행·답변 조각은 쓰지 않는다.
이벤트에는 1부터 늘어나는 번호가 붙으며, 연결이 끊겼다 다시 이어지면 마지막으로 받은 번호 이후부터
다시 받을 수 있다(SSE의 Last-Event-ID). 진행과 답변 조각은 표시용이며 저장·해결 성공이 아니다.
"""

import asyncio
from collections import defaultdict
from collections.abc import AsyncIterator

from hops_ai.api.schemas import (
    SCHEMA_VERSION,
    AgentProgressPayload,
    AiDelta,
    AiFailure,
    AiProgress,
    AiSuccess,
    AnswerDeltaPayload,
)

AiEvent = AiProgress | AiDelta | AiSuccess | AiFailure


class RunEvents:
    def __init__(self, *, run_id: str, inquiry_id: str, inquiry_version: int) -> None:
        self._ids = {
            "runId": run_id,
            "inquiryId": inquiry_id,
            "inquiryVersion": inquiry_version,
        }
        self._events: list[AiEvent] = []
        self._finished = False
        self._step_no = 0
        self._delta_seq: defaultdict[str, int] = defaultdict(int)
        self._changed = asyncio.Condition()

    @property
    def finished(self) -> bool:
        return self._finished

    def history(self) -> list[tuple[int, AiEvent]]:
        return list(enumerate(self._events, start=1))

    async def progress(self, step_type: str, display_summary: str, status: str) -> bool:
        """진행 이벤트를 보낸다. 이미 끝난 실행이면 보내지 않고 False를 돌려준다."""
        async with self._changed:
            if self._finished:
                return False
            self._step_no += 1
            self._append(
                AiProgress(
                    schemaVersion=SCHEMA_VERSION,
                    **self._ids,
                    payload=AgentProgressPayload(
                        stepNo=self._step_no,
                        stepType=step_type,
                        displaySummary=display_summary,
                        status=status,
                    ),
                )
            )
            return True

    async def answer_delta(self, message_id: str, text: str) -> bool:
        """답변 조각을 보낸다. 순번은 메시지마다 1부터 센다."""
        async with self._changed:
            if self._finished:
                return False
            self._delta_seq[message_id] += 1
            self._append(
                AiDelta(
                    schemaVersion=SCHEMA_VERSION,
                    **self._ids,
                    payload=AnswerDeltaPayload(
                        messageId=message_id,
                        seq=self._delta_seq[message_id],
                        text=text,
                    ),
                )
            )
            return True

    async def finish(self, result: AiSuccess | AiFailure) -> bool:
        """최종 결과를 한 번만 보낸다. 이미 끝났으면 무시하고 False를 돌려준다."""
        for key, expected in self._ids.items():
            if getattr(result, key) != expected:
                raise ValueError(f"결과의 {key}가 이 실행과 다르다")
        async with self._changed:
            if self._finished:
                return False
            self._finished = True
            self._append(result)
            return True

    async def stream(self, after_id: int = 0) -> AsyncIterator[tuple[int, AiEvent]]:
        """after_id 이후의 이벤트를 번호와 함께 흘린다. 최종 결과를 내보내면 끝난다."""
        next_index = after_id
        while True:
            async with self._changed:
                await self._changed.wait_for(
                    lambda index=next_index: len(self._events) > index or self._finished
                )
                batch = self._events[next_index:]
                done = self._finished
            for event_id, event in enumerate(batch, start=next_index + 1):
                yield event_id, event
            next_index += len(batch)
            if done:
                return

    def _append(self, event: AiEvent) -> None:
        self._events.append(event)
        self._changed.notify_all()
