"""실행에 고정된 이미지를 같은 예산 안에서 미확인 관찰로 변환한다."""

from hops_ai.agents.inquiry.image_observations import ImageObservation, observe_images
from hops_ai.services.attachments import SpringImageReader, read_run_images
from hops_ai.supervisor.runtime import SupervisorRuntime


async def observe_run_images(
    runtime: SupervisorRuntime, reader: SpringImageReader
) -> list[ImageObservation]:
    """호출자는 전체 작업을 runtime.run 안에서 실행한다.

    모든 이미지를 검증하기 전에는 모델을 호출하지 않는다. 반환값은 문제 연결과
    사용자 확인을 거치지 않은 관찰이며 업무 사실이나 최종 답변이 아니다.
    """
    runtime.ensure_active()
    request = runtime.request
    images = await read_run_images(request, runtime.services.runtime, reader)
    runtime.ensure_active()
    observations = await observe_images(request, images, runtime.services.llm)
    runtime.ensure_active()
    return observations
