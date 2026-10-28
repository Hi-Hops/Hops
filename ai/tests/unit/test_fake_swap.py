"""Fake와 다른 구현체를 바꿔 끼워도 호출하는 코드가 그대로 동작하는지 확인한다."""

from collections.abc import Sequence
from dataclasses import replace

from pydantic import BaseModel

from hops_ai.fakes.llm import FakeLLMClient
from hops_ai.fakes.tools import FakeToolGateway, fake_tool_ok
from hops_ai.services.llm.client import LLMMessage, LLMRequest, LLMResponse, LLMUsage
from hops_ai.services.retrieval.base import RetrievalFilter, RetrievalQuery, RetrievedChunk
from hops_ai.services.shared import SharedServices
from hops_ai.services.tools.base import ToolRequest, ToolResult


class Summary(BaseModel):
    text: str


async def summarize_vpn_case(services: SharedServices) -> tuple[str, int, str]:
    """서비스 묶음만 쓰는 호출 코드. 구현체가 무엇인지 모른다."""
    found = await services.retriever.search(
        RetrievalQuery("VPN", RetrievalFilter.for_mode("SUPPORT_DIAGNOSIS"))
    )
    tool = await services.tools.call(
        "DIAGNOSIS", ToolRequest("read_service_status", {"serviceId": "vpn-gateway"})
    )
    reply = await services.llm.generate(
        LLMRequest(
            messages=[LLMMessage("user", f"문서 {len(found)}건, 도구 {tool.status}")],
            response_model=Summary,
            max_output_tokens=200,
            prompt_version="swap-test.v1",
        )
    )
    return reply.parsed.text, len(found), tool.status


def fake_services(make_services) -> SharedServices:
    return make_services(
        llm=FakeLLMClient(Summary(text="가짜 요약")),
        tools=FakeToolGateway(
            {
                "read_service_status": fake_tool_ok(
                    "read_service_status",
                    {"state": "UP"},
                    result_id="r-1",
                    observed_at="2026-10-28T00:00:30Z",
                )
            }
        ),
    )


class HandWrittenLLM:
    async def generate[T: BaseModel](self, request: LLMRequest[T]) -> LLMResponse[T]:
        return LLMResponse(
            parsed=request.response_model.model_validate({"text": "직접 만든 요약"}),
            usage=LLMUsage(1, 1),
            model_id="hand-written",
        )


class HandWrittenRetriever:
    async def search(self, query: RetrievalQuery) -> Sequence[RetrievedChunk]:
        return []


class HandWrittenTools:
    async def call(self, role, request: ToolRequest) -> ToolResult:
        return ToolResult.timeout(request.tool_name, "직접 만든 도구가 시간 초과를 돌려준다")


async def test_calling_code_works_with_fake_services(make_services) -> None:
    text, documents, tool_status = await summarize_vpn_case(fake_services(make_services))

    assert (text, documents, tool_status) == ("가짜 요약", 2, "OK")


async def test_the_same_calling_code_works_with_other_implementations(make_services) -> None:
    services = make_services(
        llm=HandWrittenLLM(), retriever=HandWrittenRetriever(), tools=HandWrittenTools()
    )

    text, documents, tool_status = await summarize_vpn_case(services)

    assert (text, documents, tool_status) == ("직접 만든 요약", 0, "TIMEOUT")


async def test_services_can_be_replaced_one_at_a_time(make_services) -> None:
    base = fake_services(make_services)
    swapped = replace(base, retriever=HandWrittenRetriever())

    _, documents, tool_status = await summarize_vpn_case(swapped)

    assert (documents, tool_status) == (0, "OK")
