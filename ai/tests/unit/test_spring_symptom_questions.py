"""Spring의 JSON 계약에서 판정 모드의 Inquiry 질문까지 연결한다(HTTP·모델 대역)."""

import json

import httpx
import pytest

from hops_ai.agents.inquiry.extraction import InquiryExtraction, QuotedValue
from hops_ai.agents.inquiry.question_text import QuestionDraft
from hops_ai.agents.knowledge.assessment import AssessmentDraft
from hops_ai.contracts.wire import AiRunRequest
from hops_ai.services.retrieval.spring_source import SpringKnowledgeSource, SpringRelationSource
from hops_ai.services.runs.supervisor import SupervisorPipeline
from hops_ai.supervisor.selection import ActionSelection
from tests.doubles.llm import ByModelLLM
from tests.supervisor_fixtures import MESSAGE, uid
from tests.unit.test_inquiry_agent import extracted, first_message_request
from tests.unit.test_knowledge_assessment import draft
from tests.unit.test_knowledge_wiring import services_for
from tests.unit.test_multi_turn_scenarios import prefer
from tests.unit.test_problem_graph import REF
from tests.unit.test_spring_source import (
    knowledge_body,
    relation_body,
    result_item,
    settings,
    symptom_item,
)

MESSAGE_TEXT = "상신한 문서가 문서함에 안 보여요"
CHECK_ID = uid(310)
APPROVED_QUESTION = "조회할 때 상태나 제목 키워드를 입력하셨나요? 입력하셨다면 무엇인가요?"
PRIVATE_TEXT = "담당자 전용 원문"


def symptom_response(*, internal: bool) -> dict:
    symptom = symptom_item(basis={"visible": False})
    check = symptom["checkItems"][0]
    check["basis"] = {"visible": False}
    question = check["questions"][0]
    question.update(text=APPROVED_QUESTION, basis={"visible": False})
    if internal:
        symptom["causes"] = [
            {
                "id": uid(330),
                "catalogKey": "cause-test",
                "label": PRIVATE_TEXT,
                "accessScope": "SYSTEM_STAFF",
                "basis": {"visible": True},
                "checkItemKeys": [],
            }
        ]
        check["label"] = PRIVATE_TEXT
        check["questions"] = [
            {**question, "id": uid(321), "text": PRIVATE_TEXT, "employeeDisclosable": False},
            {**question, "id": uid(322), "text": PRIVATE_TEXT, "basis": {"visible": True}},
            {**question, "employeeDisclosable": True},
        ]
    return relation_body(symptoms=[symptom], internal=internal)


async def run_with_spring(monkeypatch, *, document_status, relation_response):
    request = first_message_request(MESSAGE_TEXT)
    public_doc = result_item()
    internal = relation_response["applied"]["internalJudgement"]
    if internal:
        data = request.model_dump(mode="json")
        data["accessContext"]["retrievalScopes"] = ["EMPLOYEE", "SYSTEM_STAFF"]
        request = AiRunRequest.model_validate(data)
    calls = []

    def handler(http_request):
        body = json.loads(http_request.content)
        endpoint = http_request.url.path.rsplit("/", 1)[-1]
        calls.append(endpoint)
        envelope = {"runId": request.runId, "problemRef": body["problemRef"]}
        if endpoint == "relation-searches":
            return httpx.Response(200, json={**relation_response, **envelope})
        assert endpoint == "knowledge-searches"
        if document_status == "ERROR":
            return httpx.Response(503, json={"code": "SEARCH_UNAVAILABLE"})
        results = [public_doc] if document_status == "FOUND" else []
        response = knowledge_body(document_status, results, **envelope)
        if internal and document_status == "FOUND":
            results.append(
                result_item(
                    content=PRIVATE_TEXT,
                    id=uid(340),
                    documentId=uid(341),
                    kind="RUNBOOK",
                    accessScope="SYSTEM_STAFF",
                )
            )
            response["applied"].update(
                accessScopes=["EMPLOYEE", "SYSTEM_STAFF"], internalJudgement=True
            )
        return httpx.Response(200, json=response)

    def assess(call):
        payload = json.loads(call.messages[1].content)
        assert payload["checkItems"] == [{"id": CHECK_ID, "ask": APPROVED_QUESTION}]
        assert [item["id"] for item in payload["evidence"]] == [public_doc["evidence"]["id"]]
        return draft(
            verdict="PARTIAL",
            supportingEvidenceIds=[public_doc["evidence"]["id"]],
            missingInformation=[{"kind": "CHECK_ITEM", "ref": CHECK_ID}],
        )

    def write_question(call):
        prompts = json.loads(call.messages[1].content)["toAsk"]
        return QuestionDraft(text="\n".join(item["ask"] for item in prompts))

    llm = ByModelLLM(
        {
            InquiryExtraction: [
                InquiryExtraction(
                    problems=[
                        extracted(
                            actualBehavior=QuotedValue(messageId=MESSAGE, quote=MESSAGE_TEXT),
                            keywords=["문서함", "문서"],
                        )
                    ]
                )
            ],
            AssessmentDraft: assess,
            ActionSelection: prefer("SEARCH", "ASK"),
            QuestionDraft: write_question,
        }
    )
    monkeypatch.setenv("HOPS_AI_KNOWLEDGE_ASSESSMENT", "true")
    config = settings()
    transport = httpx.MockTransport(handler)
    pipeline = SupervisorPipeline(
        llm,
        knowledge_source=SpringKnowledgeSource(config, transport=transport),
        relation_source=SpringRelationSource(config, transport=transport),
        assess_evidence=config.knowledge_assessment,
    )
    result = await pipeline.run(request, services_for(request, llm).runtime)
    return result, llm, calls


@pytest.mark.parametrize(
    ("document_status", "internal"), [("FOUND", False), ("NO_RESULT", False), ("FOUND", True)]
)
async def test_approved_spring_question_reaches_inquiry(monkeypatch, document_status, internal):
    result, llm, calls = await run_with_spring(
        monkeypatch,
        document_status=document_status,
        relation_response=symptom_response(internal=internal),
    )

    assert calls == ["knowledge-searches", "relation-searches"]
    assert result.status == "SUCCEEDED"
    [question] = result.questions
    assert question.problemRef == REF and CHECK_ID in question.requestedFields
    assert APPROVED_QUESTION in question.text
    [inquiry_call] = [call for call in llm.requests if call.response_model is QuestionDraft]
    assert {"field": CHECK_ID, "ask": APPROVED_QUESTION} in json.loads(
        inquiry_call.messages[1].content
    )["toAsk"]
    assert all(
        PRIVATE_TEXT not in message.content for call in llm.requests for message in call.messages
    )
    assert PRIVATE_TEXT not in result.model_dump_json()
    assert result.guide is None and not result.ticketProposals
    if document_status == "NO_RESULT":
        assert not result.evidence and llm.calls(AssessmentDraft) == 0
        selections = [
            json.loads(call.messages[1].content)
            for call in llm.requests
            if call.response_model is ActionSelection
        ]
        [problem] = selections[-1]["problems"]
        assert problem["searchState"] == "EMPTY" and problem["verdict"] == "NO_MATCH"


async def test_empty_documents_and_empty_relations_leave_only_form_questions(monkeypatch):
    result, llm, calls = await run_with_spring(
        monkeypatch, document_status="NO_RESULT", relation_response=relation_body("NO_RESULT")
    )

    assert calls == ["knowledge-searches", "relation-searches"]
    [question] = result.questions
    assert question.requestedFields == ["taskOrScreen", "expectedBehavior", "occurredAt"]
    assert not result.evidence and llm.calls(AssessmentDraft) == 0


async def test_document_search_failure_does_not_use_relation_questions(monkeypatch):
    result, llm, calls = await run_with_spring(
        monkeypatch, document_status="ERROR", relation_response=symptom_response(internal=False)
    )

    assert calls == ["knowledge-searches"]
    assert result.status == "FAILED" and result.error.code == "SEARCH_UNAVAILABLE"
    assert not result.questions and not result.evidence
    assert llm.calls(AssessmentDraft) == llm.calls(QuestionDraft) == 0
