"""보존 색인 어댑터의 계약 검증. 임베딩·HTTP는 대역이며 실검색 품질 검사가 아니다."""

from uuid import uuid4

import httpx
import pytest

from hops_ai.services.indexing.approved_case import canonical, sha256, verified_case_body
from hops_ai.services.indexing.chunks import case_chunks
from hops_ai.services.indexing.neo4j import Neo4jCaseIndex
from hops_ai.services.indexing.pgvector import OpenAICaseEmbedder
from hops_ai.settings import Settings


def test_canonical_matches_java_and_preserves_unicode_and_array_order():
    assert (
        canonical({"z": 1, "a": {"한글": ["나", "가"], "b": None}})
        == '{"a":{"b":null,"한글":["나","가"]},"z":1}'
    )
    assert (
        sha256(canonical({"b": 2, "a": 1}))
        == "43258cff783fe7036d8a43033f830adfc60ec037382473548ac742b888292777"
    )


def test_index_is_opt_in_and_requires_explicit_models_and_stores():
    assert not Settings(_env_file=None).index_jobs_enabled
    with pytest.raises(ValueError, match="명시적"):
        Settings(_env_file=None, index_jobs_enabled=True)


async def test_embedder_uses_explicit_model_and_dimensions():
    async def response(request):
        assert b'"model":"synthetic-model"' in request.content
        assert b'"dimensions":3' in request.content
        return httpx.Response(
            200,
            json={"model": "synthetic-model", "data": [{"index": 0, "embedding": [1.0, 0.0, 0.0]}]},
        )

    async with httpx.AsyncClient(
        transport=httpx.MockTransport(response), base_url="https://api.openai.com/v1/"
    ) as client:
        assert await OpenAICaseEmbedder(client, "synthetic-model", 3)("가상 사례") == [
            1.0,
            0.0,
            0.0,
        ]


async def test_embedder_batch_matches_input_order_by_index():
    async def response(request):
        import json

        assert json.loads(request.content)["input"] == ["first", "second"]
        return httpx.Response(
            200,
            json={
                "model": "synthetic-model",
                "data": [
                    {"index": 1, "embedding": [0.0, 1.0]},
                    {"index": 0, "embedding": [1.0, 0.0]},
                ],
            },
        )

    async with httpx.AsyncClient(
        transport=httpx.MockTransport(response), base_url="https://api.openai.com/v1/"
    ) as client:
        assert await OpenAICaseEmbedder(client, "synthetic-model", 2).many(["first", "second"]) == [
            [1.0, 0.0],
            [0.0, 1.0],
        ]


def test_chunks_preserve_unicode_and_original_digest():
    import tiktoken

    content = canonical({"a": ("가😀 문장. \\n" * 2000), "b": "다른 필드" * 1000})
    chunks = case_chunks(content, "text-embedding-3-small", 32)
    encoding = tiktoken.encoding_for_model("text-embedding-3-small")
    assert len(chunks) > 32
    assert all(0 < len(encoding.encode_ordinary(chunk)) <= 32 for chunk in chunks)
    assert "".join(chunks) == content
    assert sha256("".join(chunks)) == sha256(content)
    assert "�" not in "".join(chunks)


@pytest.mark.parametrize("indices", [[0, 0], [0, True], [0, 2]])
async def test_embedder_batch_rejects_invalid_result_indices(indices):
    async def response(_request):
        return httpx.Response(
            200,
            json={
                "model": "synthetic-model",
                "data": [{"index": index, "embedding": [1.0, 0.0]} for index in indices],
            },
        )

    async with httpx.AsyncClient(
        transport=httpx.MockTransport(response), base_url="https://api.openai.com/v1/"
    ) as client:
        with pytest.raises(ValueError, match="일치하지"):
            await OpenAICaseEmbedder(client, "synthetic-model", 2).many(["first", "second"])


def graph_request():
    body = {"systemId": str(uuid4()), "accessScope": "SYSTEM_STAFF", "entityLinks": []}
    content = {"caseId": str(uuid4()), "caseVersion": 1, "store": "NEO4J", "approvedCase": body}
    return {
        **content,
        "contractVersion": "hops-system-1",
        "contentDigest": sha256(canonical(content)),
    }


async def test_graph_rejects_unknown_relations_before_http():
    request = graph_request()
    request["approvedCase"]["entityLinks"] = [{"relation": "DELETE", "targetId": "unknown"}]
    request["contentDigest"] = sha256(
        canonical({key: request[key] for key in ("caseId", "caseVersion", "store", "approvedCase")})
    )

    async def forbidden(_request):
        pytest.fail("허용하지 않은 관계는 HTTP로 보내면 안 됩니다")

    async with httpx.AsyncClient(transport=httpx.MockTransport(forbidden)) as client:
        with pytest.raises(ValueError):
            await Neo4jCaseIndex(client).apply(request)


def test_request_digest_includes_store_but_source_digest_does_not():
    request = graph_request()
    body = verified_case_body(request, "NEO4J")
    assert "store" not in body
    with pytest.raises(ValueError):
        verified_case_body(request, "VECTOR")
