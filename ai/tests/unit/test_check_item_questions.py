"""확인 항목 질문(#189 ③b): 판정이 지목한 관계 확인 항목을 승인된 문구로 묻는다.

사용자 답은 판정 입력으로 준다. 모델은 Fake이며 판정이 좋은 항목을 고르는지, 질문이 좋은지는
검증하지 않는다. 무엇을 묻는지(승인된 항목만, 이미 물은 것은 제외, 묶음 구성, 한도)와 판정
입력에 무엇이 실리는지를 코드 규칙으로 확인한다.
"""

import json

from hops_ai.agents.inquiry.asking import check_item_questions, form_question_plan, question_plan
from hops_ai.agents.inquiry.forms import SYSTEM
from hops_ai.agents.knowledge.assessment import (
    MAX_REPLY_CHARS,
    MAX_USER_REPLIES,
    MAX_USER_STATEMENTS,
    asked_checks,
    build_user_payload,
)
from hops_ai.contracts.roles import (
    Assessment,
    MissingInformation,
    RelationCheck,
    RelationNeighbors,
    RelationQuestion,
    RelationSymptom,
)
from hops_ai.contracts.wire import AiRunRequest
from hops_ai.supervisor.facts import problem_facts
from tests.supervisor_fixtures import EVIDENCE, PROBLEM, make_details, make_evidence, uid
from tests.unit.test_problem_decisions import base_state, request_for
from tests.unit.test_question_generation import ask, asked

ASK_PERIOD = "문서함에서 조회 기간을 어떻게 설정하셨나요?"
ASK_FILTER = "조회할 때 상태나 제목 키워드를 입력하셨나요?"
ASK_DATE = "찾으시는 문서를 기안한 시기가 언제인가요?"
ASK_STATE = "문서를 상신하셨나요, 임시 저장 상태인가요?"
APPROVED = {
    "check-period": ASK_PERIOD,
    "check-filter": ASK_FILTER,
    "check-date": ASK_DATE,
    "check-state": ASK_STATE,
}


def relations(*ids: str) -> RelationNeighbors:
    checks = [
        RelationCheck(id=key, questions=[RelationQuestion(id=f"q-{key}", text=APPROVED[key])])
        for key in ids
    ]
    return RelationNeighbors(
        state="FOUND", symptoms=[RelationSymptom(id="s", label="문서 미표시", checkItems=checks)]
    )


def request_with(asked_fields=(), *, question_count=0, system=...) -> AiRunRequest:
    base = (
        request_for(question_count=question_count)
        if system is ...
        else request_for(question_count=question_count, system=system)
    )
    fields = base.model_dump(mode="json")
    fields["problems"][0]["askedFields"] = list(asked_fields)
    return AiRunRequest.model_validate(fields)


def judged(state, *refs, offered=("check-period", "check-filter", "check-date", "check-state")):
    """판정이 refs를 확인 항목으로 지목했고 관계 조회가 offered를 보여 준 상태."""
    assessment = Assessment(
        problemRef=PROBLEM,
        verdict="PARTIAL",
        supportingEvidenceIds=[EVIDENCE],
        missingInformation=[MissingInformation(kind="CHECK_ITEM", ref=ref) for ref in refs],
        candidateCauses=[],
        nextChecks=[],
        rationale="내부용",
    )
    return {
        **state,
        "assessments": {PROBLEM: assessment},
        "relations": {PROBLEM: relations(*offered)},
    }


def complete(state):
    """양식 필드를 모두 확보한 문제(필수 정보는 갖춰졌다)."""
    full = make_details().model_copy(
        update={"expectedBehavior": "문서가 보여야 해요", "occurredAt": "어제부터"}
    )
    problem = state["problems"][PROBLEM].model_copy(update={"details": full})
    return {**state, "problems": {PROBLEM: problem}}


# -- 질문 계획 --------------------------------------------------------------------------------


def test_a_check_item_takes_the_last_slot_when_form_fields_would_fill_the_bundle() -> None:
    state = judged(base_state(request_with()), "check-period", "check-filter")

    form = form_question_plan(state, PROBLEM, searched=True)
    plan = question_plan(state, PROBLEM, searched=True)

    assert len(form.fields) == 3
    assert plan.action == "ASK"
    assert plan.fields == (*form.fields[:2], "check-period")
    assert plan.prompts[-1] == ASK_PERIOD
    assert plan.text is not None and ASK_PERIOD in plan.text


def test_check_items_fill_the_slots_form_fields_leave_open() -> None:
    state = judged(
        base_state(request_with(), details=make_details()),
        "check-period",
        "check-filter",
        "check-date",
    )

    plan = question_plan(state, PROBLEM, searched=True)

    form = form_question_plan(state, PROBLEM, searched=True)
    assert len(form.fields) == 2
    assert plan.fields == (*form.fields, "check-period")


def test_when_the_form_is_complete_only_check_items_are_asked() -> None:
    state = complete(judged(base_state(request_with()), "check-period", "check-filter"))

    assert form_question_plan(state, PROBLEM, searched=True).action == "COMPLETE"
    plan = question_plan(state, PROBLEM, searched=True)

    assert plan.action == "ASK"
    assert plan.fields == ("check-period", "check-filter")
    assert plan.prompts == (ASK_PERIOD, ASK_FILTER)


def test_at_most_three_items_are_asked_at_once_in_the_judgments_order() -> None:
    state = complete(
        judged(
            base_state(request_with()), "check-state", "check-date", "check-period", "check-filter"
        )
    )

    assert question_plan(state, PROBLEM, searched=True).fields == (
        "check-state",
        "check-date",
        "check-period",
    )


def test_a_check_item_already_asked_is_not_asked_again() -> None:
    state = complete(
        judged(
            base_state(request_with(["check-period"], question_count=1)),
            "check-period",
            "check-filter",
        )
    )

    assert check_item_questions(state, PROBLEM) == [("check-filter", ASK_FILTER)]
    assert question_plan(state, PROBLEM, searched=True).fields == ("check-filter",)


def test_when_every_designated_item_was_asked_the_form_plan_stands() -> None:
    state = complete(
        judged(base_state(request_with(["check-period"], question_count=1)), "check-period")
    )

    assert question_plan(state, PROBLEM, searched=True) == form_question_plan(
        state, PROBLEM, searched=True
    )
    assert question_plan(state, PROBLEM, searched=True).action == "COMPLETE"


def test_an_item_the_lookup_did_not_offer_is_never_asked() -> None:
    """판정이 조회에 없던 ID(승인되지 않았거나 다른 증상의 항목)를 지목해도 질문하지 않는다."""
    state = complete(
        judged(
            base_state(request_with()),
            "check-unapproved",
            "check-period",
            offered=("check-period",),
        )
    )

    assert check_item_questions(state, PROBLEM) == [("check-period", ASK_PERIOD)]
    assert question_plan(state, PROBLEM, searched=True).fields == ("check-period",)


def test_without_an_assessment_or_relations_the_plan_is_the_form_plan() -> None:
    state = base_state(request_with())

    assert question_plan(state, PROBLEM, searched=True) == form_question_plan(
        state, PROBLEM, searched=True
    )
    no_relations = {**judged(state, "check-period"), "relations": {}}
    assert check_item_questions(no_relations, PROBLEM) == []


def test_no_check_item_is_asked_once_the_question_limit_is_reached() -> None:
    state = complete(judged(base_state(request_with(question_count=5)), "check-period"))

    plan = question_plan(state, PROBLEM, searched=True)

    assert plan.action == "COMPLETE" or plan.action == "HANDOFF"
    assert plan.fields == ()


def test_the_system_is_asked_first_and_alone_without_check_items() -> None:
    state = judged(base_state(request_with(system=None)), "check-period")

    plan = question_plan(state, PROBLEM, searched=False)

    assert plan.fields[0] == "system"
    assert "check-period" not in plan.fields


# -- 합법 행동 ----------------------------------------------------------------------------------


def test_a_designated_check_item_makes_asking_legal_even_when_the_form_is_complete() -> None:
    state = complete(judged(base_state(request_with()), "check-period"))

    facts = problem_facts(state, PROBLEM)

    assert facts.askable is True
    # 필수 정보는 확인 항목과 무관하게 양식 필드만으로 판단한다
    assert facts.required_info_secured is True


def test_without_a_designation_a_complete_form_has_nothing_to_ask() -> None:
    facts = problem_facts(complete(base_state(request_with())), PROBLEM)

    assert facts.askable is False and facts.required_info_secured is True


# -- 질문 행동 ----------------------------------------------------------------------------------


async def test_the_ask_action_asks_the_approved_wording_when_no_model_text_is_available() -> None:
    state = complete(judged(base_state(request_with()), "check-period", "check-filter"))
    request = state["input"]

    merged, _ = await ask(state, request, generate=False)

    question = asked(merged)
    assert question.requestedFields == ["check-period", "check-filter"]
    assert ASK_PERIOD in question.text and ASK_FILTER in question.text


async def test_the_model_only_sees_the_approved_wording_of_the_items_to_ask() -> None:
    from hops_ai.agents.inquiry.question_text import QuestionDraft

    state = complete(judged(base_state(request_with()), "check-period", "check-filter"))
    request = state["input"]
    draft = QuestionDraft(text=f"1. {ASK_PERIOD}\n2. {ASK_FILTER}")

    merged, llm = await ask(state, request, draft)

    payload = json.loads(llm.requests[0].messages[1].content)
    assert payload["toAsk"] == [
        {"field": "check-period", "ask": ASK_PERIOD},
        {"field": "check-filter", "ask": ASK_FILTER},
    ]
    assert asked(merged).requestedFields == ["check-period", "check-filter"]


# -- 판정 입력 ----------------------------------------------------------------------------------


def conversation(request: AiRunRequest, *contents: tuple[str, str]) -> AiRunRequest:
    """request의 첫 메시지 뒤에 (보낸 이, 내용) 메시지를 순서대로 붙인다."""
    fields = request.model_dump(mode="json")
    first = fields["messages"][0]
    fields["messages"] = [first]
    for number, (sender, content) in enumerate(contents, start=2):
        fields["messages"].append(
            {
                **first,
                "id": f"00000000-0000-0000-0000-0000000001{number:02d}",
                "sequenceNo": number,
                "sender": sender,
                "content": content,
                "problemIds": [],
            }
        )
    return AiRunRequest.model_validate(fields)


def payload_of(state, asked_context):
    problem = state["problems"][PROBLEM]
    hit = _hit()
    return json.loads(
        build_user_payload(problem, [hit], state["relations"][PROBLEM], asked_context)
    )


def _hit():
    from hops_ai.services.retrieval.knowledge_source import KnowledgeHit
    from tests.unit.test_knowledge_assessment import authorize

    evidence = make_evidence()
    return KnowledgeHit(evidence=evidence, reference=authorize(evidence), content="본문", score=1)


def test_the_payload_has_no_asked_checks_before_any_question_was_asked() -> None:
    state = judged(base_state(request_with()))

    payload = payload_of(state, asked_checks(state, PROBLEM, state["relations"][PROBLEM]))

    assert "askedChecks" not in payload and "userReplies" not in payload
    assert [item["id"] for item in payload["checkItems"]] == list(APPROVED)
    # 첫 문의는 질문 전에 사용자가 스스로 한 말로 판정에 실린다
    assert payload["userStatements"] == ["전자결재 문서 조회 방법을 알려주세요"]


def test_what_the_user_said_before_any_check_question_reaches_the_judgment() -> None:
    # EC2 재현: 첫 문의의 "상신한 문서"가 요약·양식에 남지 않아 판정이 상신 여부를 다시 물었다.
    # 확인 항목이 아닌 질문(시스템 등)에 대한 답도 사용자가 스스로 한 말이다.
    request = conversation(
        request_with([SYSTEM], question_count=1),
        ("AI", "어느 시스템에 대한 문의인가요?"),
        ("USER", "전자결재예요. 방금 상신한 문서가 문서함 전체 문서에 안 나와요."),
    )
    state = judged(base_state(request))

    context = asked_checks(state, PROBLEM, state["relations"][PROBLEM])
    payload = payload_of(state, context)

    assert payload["userStatements"] == [
        "전자결재 문서 조회 방법을 알려주세요",
        "전자결재예요. 방금 상신한 문서가 문서함 전체 문서에 안 나와요.",
    ]
    assert "askedChecks" not in payload and "userReplies" not in payload


def test_statements_keep_the_first_inquiry_and_the_latest_messages() -> None:
    long_message = "나" * (MAX_REPLY_CHARS + 50)
    request = conversation(
        request_with(),
        *[("USER", f"말 {number}") for number in range(MAX_USER_STATEMENTS + 2)],
        ("USER", long_message),
    )
    state = judged(base_state(request))

    statements = asked_checks(state, PROBLEM, state["relations"][PROBLEM]).statements

    assert len(statements) == MAX_USER_STATEMENTS
    assert statements[0] == "전자결재 문서 조회 방법을 알려주세요"
    assert statements[-1] == "나" * MAX_REPLY_CHARS


def test_asked_checks_and_the_replies_after_the_first_question_reach_the_judgment() -> None:
    request = conversation(
        request_with(["check-period"], question_count=1),
        ("AI", "조회 기간을 어떻게 설정하셨나요?"),
        ("USER", "최근 3개월로 두고 조회했어요"),
    )
    state = judged(base_state(request))

    context = asked_checks(state, PROBLEM, state["relations"][PROBLEM])
    payload = payload_of(state, context)

    assert payload["askedChecks"] == [ASK_PERIOD]
    assert payload["userReplies"] == ["최근 3개월로 두고 조회했어요"]
    # 질문 전의 첫 문의는 답과 겹치지 않게 따로 실린다
    assert payload["userStatements"] == ["전자결재 문서 조회 방법을 알려주세요"]
    # 이미 물은 항목은 다시 지목할 후보에서 빠진다
    assert "check-period" not in [item["id"] for item in payload["checkItems"]]


def test_replies_are_scoped_to_the_problem_that_was_asked_about() -> None:
    other = str(uid(500))
    request = conversation(
        request_with(["check-period"], question_count=1),
        ("USER", "첫 문의 외 다른 말"),
        ("AI", "다른 문제에 대한 질문"),
        ("USER", "다른 문제의 답"),
        ("AI", "이 문제에 대한 확인 질문"),
        ("USER", "이 문제의 답"),
        ("USER", "대상을 고르지 않은 답"),
    )
    fields = request.model_dump(mode="json")
    # 같은 문의에 다른 문제가 하나 더 있다(메시지의 문제 참조는 입력의 문제여야 한다)
    fields["problems"].append({**fields["problems"][0], "id": other})
    # 순번: 1 첫 문의, 2 사용자, 3 AI(다른 문제), 4 사용자(다른 문제), 5 AI(이 문제), 6·7 사용자
    by_number = {message["sequenceNo"]: message for message in fields["messages"]}
    by_number[3]["problemIds"] = [other]
    by_number[4]["problemIds"] = [other]
    by_number[5]["problemIds"] = [PROBLEM]
    by_number[6]["problemIds"] = [PROBLEM]
    state = judged(base_state(AiRunRequest.model_validate(fields)))

    context = asked_checks(state, PROBLEM, state["relations"][PROBLEM])

    assert context.replies == ("이 문제의 답", "대상을 고르지 않은 답")
    # 다른 문제에 연결된 말은 질문 전이라도 이 문제의 진술이 아니다
    assert context.statements == ("전자결재 문서 조회 방법을 알려주세요", "첫 문의 외 다른 말")


def test_replies_fall_back_to_the_first_ai_message_when_no_message_is_linked() -> None:
    request = conversation(
        request_with(["check-period"], question_count=1),
        ("USER", "질문 전의 말"),
        ("AI", "질문"),
        ("USER", "답"),
    )
    state = judged(base_state(request))

    assert asked_checks(state, PROBLEM, state["relations"][PROBLEM]).replies == ("답",)


def test_replies_are_the_latest_few_and_are_cut_to_the_limit() -> None:
    long_reply = "가" * (MAX_REPLY_CHARS + 50)
    request = conversation(
        request_with(["check-period"], question_count=1),
        ("AI", "질문"),
        *[("USER", f"답 {number}") for number in range(MAX_USER_REPLIES + 2)],
        ("USER", long_reply),
    )
    state = judged(base_state(request))

    context = asked_checks(state, PROBLEM, state["relations"][PROBLEM])

    assert len(context.replies) == MAX_USER_REPLIES
    assert context.replies[-1] == "가" * MAX_REPLY_CHARS
    assert context.replies[0] == f"답 {MAX_USER_REPLIES - 1}"


def test_an_asked_item_outside_this_lookup_is_not_shown() -> None:
    request = conversation(
        request_with(["check-other"], question_count=1), ("AI", "질문"), ("USER", "답")
    )
    state = judged(base_state(request))

    assert asked_checks(state, PROBLEM, state["relations"][PROBLEM]).asks == ()


def test_prompt_rules_keep_user_replies_out_of_the_evidence() -> None:
    from hops_ai.agents.knowledge.assessment import PROMPT_VERSION, SYSTEM_PROMPT

    assert PROMPT_VERSION == "knowledge.assessment.v5"
    assert "userReplies" in SYSTEM_PROMPT and "evidence가 아니다" in SYSTEM_PROMPT
    assert "userStatements" in SYSTEM_PROMPT


def test_prompt_rules_skip_check_items_the_user_already_answered() -> None:
    from hops_ai.agents.knowledge.assessment import SYSTEM_PROMPT, AssessmentDraft

    assert "answeredCheckItems" in SYSTEM_PROMPT and "이미 했다고 말했으면" in SYSTEM_PROMPT
    # 예시는 특정 업무 시나리오의 말이 아니라 일반 규칙으로 쓴다(재현에 쓴 전자결재 용어 금지)
    assert not [word for word in ("상신", "기안", "결재", "문서함") if word in SYSTEM_PROMPT]
    # 모델이 답한 항목을 먼저 판단하도록 출력 순서에서 missingInformation보다 앞에 둔다
    order = list(AssessmentDraft.model_json_schema()["properties"])
    assert order.index("answeredCheckItems") < order.index("missingInformation")


def test_a_check_item_the_model_marks_answered_is_never_asked() -> None:
    # 실모델 비교에서 프롬프트 지시만으로는 이미 답한 상신 여부를 다시 지목했다. 모델이 답했다고
    # 표시한 항목은 함께 지목했더라도 코드가 질문 후보에서 뺀다.
    from hops_ai.agents.knowledge.assessment import AssessmentDraft, DraftMissing, to_assessment

    draft = AssessmentDraft(
        verdict="PARTIAL",
        supportingEvidenceIds=[EVIDENCE],
        answeredCheckItems=["check-state"],
        missingInformation=[
            DraftMissing(kind="CHECK_ITEM", ref="check-state"),
            DraftMissing(kind="CHECK_ITEM", ref="check-period"),
            DraftMissing(kind="FIELD", ref="occurredAt"),
        ],
        candidateCauses=[],
        nextChecks=[],
        rationale="내부용",
    )

    missing = to_assessment(PROBLEM, draft).missingInformation

    assert [(item.kind, item.ref) for item in missing] == [
        ("CHECK_ITEM", "check-period"),
        ("FIELD", "occurredAt"),
    ]
    # 이번 관계 조회가 보여 주지 않은 ID를 답했다고 해도 인정하지 않는다
    unoffered = to_assessment(PROBLEM, draft, offered=frozenset({"check-period"}))
    assert [item.ref for item in unoffered.missingInformation][:2] == [
        "check-state",
        "check-period",
    ]
