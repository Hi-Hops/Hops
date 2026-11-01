import pytest
from pydantic import BaseModel, TypeAdapter, ValidationError

from hops_ai.agents.registry import build_registry
from hops_ai.contracts.domain import AgentRole
from hops_ai.contracts.types import QuestionCount
from hops_ai.harness.errors import ComponentNotReady
from hops_ai.services.llm.client import LLMRequest
from hops_ai.services.llm.openai import OpenAIClient
from hops_ai.services.retrieval.base import RetrievalQuery
from hops_ai.services.retrieval.neo4j import Neo4jRetriever
from hops_ai.services.retrieval.pgvector import PgVectorRetriever
from hops_ai.supervisor.graph import Supervisor


@pytest.mark.parametrize("role", list(AgentRole))
async def test_pending_role_never_generates_success_or_assignment(role) -> None:
    with pytest.raises(ComponentNotReady):
        await build_registry()[role].run(object(), None)


async def test_pending_supervisor_has_no_fixed_business_routing() -> None:
    with pytest.raises(ComponentNotReady):
        await Supervisor().analyze(object(), None)


@pytest.mark.parametrize("retriever", [PgVectorRetriever(), Neo4jRetriever()])
async def test_disconnected_retrieval_is_not_a_successful_empty_search(retriever) -> None:
    with pytest.raises(ComponentNotReady):
        await retriever.search(RetrievalQuery("전자결재 문서 조회", "test-scope"))


async def test_disconnected_model_does_not_generate_a_fake_answer() -> None:
    with pytest.raises(ComponentNotReady):
        await OpenAIClient().generate(LLMRequest([], BaseModel, 100, "test-only"))


@pytest.mark.parametrize("count", [0, 4, 5])
def test_question_bundle_limit_allows_up_to_five(count) -> None:
    assert TypeAdapter(QuestionCount).validate_python(count) == count


@pytest.mark.parametrize("count", [-1, 6, True, "5"])
def test_invalid_question_bundle_counts_are_rejected(count) -> None:
    with pytest.raises(ValidationError):
        TypeAdapter(QuestionCount).validate_python(count)
