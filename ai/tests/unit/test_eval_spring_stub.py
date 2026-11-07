"""Spring 대역이 다음 실행 입력을 가정대로 만드는지 확인한다. 가정은 실제 Spring과 다를 수 있다."""

from evaluation.harness.scenario import parse_scenario
from evaluation.harness.spring_stub import SpringStub
from tests.eval_fixtures import ai_result, fact_from, problem_dict, question
from tests.unit.test_eval_user_sim import SCENARIO


def stub() -> SpringStub:
    return SpringStub(parse_scenario(SCENARIO))


def first_result(request):
    source = request.messages[0].id
    return ai_result(
        request,
        answer="어떤 업무를 하려고 하셨나요?",
        problems=[
            problem_dict(
                "new:1", [fact_from("하려던 업무·화면: 지난달 결재 문서가 안 보여요", source)]
            )
        ],
        questions=[question("new:1", "어떤 업무를 하려고 하셨나요?", "taskOrScreen")],
    )


def test_first_request_has_the_first_message_and_no_problems() -> None:
    request = stub().first_request()

    assert [m.content for m in request.messages] == ["지난달 결재 문서가 안 보여요"]
    assert request.problems == [] and request.problemIds == []
    assert request.accessContext.allowedDocumentScopes == ["EMPLOYEE"]
    assert {system.code for system in request.systems} == {
        "ELECTRONIC_APPROVAL",
        "COMMON_LOGIN",
        "FILE_SHARING",
    }


def test_advance_assigns_ids_counts_questions_and_adds_the_reply() -> None:
    harness = stub()
    request = harness.first_request()
    result = first_result(request)

    next_request, ref_map = harness.advance(result, "전자결재 문서 조회 화면입니다.")

    assert len(ref_map) == 1 and ref_map[0][0] == "new:1"
    problem_id = ref_map[0][1]
    assert [p.id for p in next_request.problems] == [problem_id]
    problem = next_request.problems[0]
    assert problem.questionCount == 1
    assert problem.state == "ANALYZING"
    assert problem.standardRequest is not None and problem.standardRequest.confirmedFacts
    assert [m.sender for m in next_request.messages] == ["USER", "AI", "USER"]
    assert next_request.messages[-1].content == "전자결재 문서 조회 화면입니다."
    assert next_request.inquiryVersion == request.inquiryVersion + 1
    assert next_request.problemIds == [problem_id]
    assert next_request.runId != request.runId


def test_question_count_accumulates_across_turns() -> None:
    harness = stub()
    request = harness.first_request()
    result = first_result(request)
    second, ref_map = harness.advance(result, "답변 1")
    problem_id = ref_map[0][1]

    again = ai_result(
        second,
        answer="언제부터인가요?",
        problems=[
            problem_dict(
                problem_id,
                [
                    fact_from(
                        "하려던 업무·화면: 지난달 결재 문서가 안 보여요", request.messages[0].id
                    )
                ],
            )
        ],
        questions=[question(problem_id, "언제부터인가요?", "occurredAt")],
    )
    third, no_new = harness.advance(again, "답변 2")

    assert no_new == ()
    assert third.problems[0].questionCount == 2
    assert third.inquiryVersion == request.inquiryVersion + 2


def test_advance_accumulates_the_fields_that_were_asked() -> None:
    harness = stub()
    request = harness.first_request()
    first = first_result(request)
    second_request, ref_map = harness.advance(first, "전자결재 문서 조회 화면입니다.")
    problem_id = ref_map[0][1]
    assert second_request.problems[0].askedFields == ["taskOrScreen"]

    second = ai_result(
        second_request,
        answer="기대한 동작은 무엇인가요?",
        problems=[
            problem_dict(
                problem_id, [fact_from("하려던 업무·화면: 문서 조회", request.messages[0].id)]
            )
        ],
        questions=[question(problem_id, "기대한 동작은 무엇인가요?", "expectedBehavior")],
    )
    third_request, _ = harness.advance(second, "모르겠어요")

    assert third_request.problems[0].questionCount == 2
    assert third_request.problems[0].askedFields == ["taskOrScreen", "expectedBehavior"]
