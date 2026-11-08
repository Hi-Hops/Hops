"""검증된 State를 외부 AiResult로 확정한다. 업무 저장 성공을 만들지 않는다."""

from hops_ai.agents.inquiry.image_observations import ImageObservation
from hops_ai.contracts.execution import RunKind
from hops_ai.contracts.wire import AiResult
from hops_ai.harness.errors import OutputRejected
from hops_ai.rules.progress import get_problem_progress, validate_evidence_scope
from hops_ai.rules.proposals import (
    validate_details,
    validate_guide,
    validate_statements,
    validate_ticket,
)
from hops_ai.supervisor.image_questions import bind_image_questions
from hops_ai.supervisor.state import SupervisorState, transition_phase
from hops_ai.supervisor.validation import (
    ensure_active,
    is_reply,
    validate_completion,
    validate_questions,
)


def compose_public_answer(state: SupervisorState) -> str:
    """검증된 제안과 서버가 제공한 상태로 상담 본문을 구성한다.

    Args:
        state: 최종 출처·참조 검사를 마친 상담 State. 모델의 publicSummary와
            state.answer는 답변 근거로 사용하지 않는다. 질문과 가이드 본문의
            의미·공개 적합성은 각 역할과 해당 역할의 검증 경계에서 확인해야 한다.

    Returns:
        질문·가이드 원문을 한 번씩 포함하는 공개 본문. 티켓 제안은 준비 사실만
        안내하고 저장·배정 성공을 추정하지 않는다. 기존 업무 결과는 Spring이
        전달한 STORED/PENDING/UNKNOWN을 구분하며 새 업무 상태를 만들지 않는다.
        여러 문제의 질문 본문이 완전히 같으면 문제 번호를 함께 표시하고 본문은
        한 번만 넣는다. 문제별 질문 메타데이터와 질문 횟수는 합치거나 변경하지 않는다.

    Raises:
        OutputRejected: 상담 이외의 실행이거나 처리 결과 없는 문제가 남은 경우,
            필수 본문이 누락됐거나 질문과 가이드가 같은 본문으로 충돌하는 경우.

    본문을 자동 절단하지 않는다. 전체 길이와 문자열 형식은 최종 AiResult에서
    다시 검사하므로 4,000자를 넘으면 안전한 실패가 된다.
    """
    if state["input"].kind != RunKind.INQUIRY_ANALYSIS:
        raise OutputRejected("사례 초안 실행에는 상담 본문을 만들 수 없다")
    validate_completion(state)
    if is_reply(state):
        if state["replyKind"] == "THANKS":
            return "감사합니다. 다른 IT 문의가 있으시면 언제든 알려 주세요."
        if state["replyKind"] == "CLARIFY":
            return "보내주신 내용을 정확히 파악하지 못했어요. 문의 내용을 조금 더 알려주시겠어요?"
        if state["input"].problems:
            return "안녕하세요. 이어서 문의 내용을 알려 주세요."
        return "안녕하세요. 어떤 IT 관련 문의가 있으신가요? 내용을 알려 주세요."
    progress = get_problem_progress(state)
    questions = {question.problemRef: question for question in state["questions"].values()}
    question_numbers: dict[str, list[int]] = {}
    for index, item in enumerate(progress, start=1):
        if item["outcome"] == "ASK_USER":
            body = questions[item["problemRef"]].text
            question_numbers.setdefault(body, []).append(index)
    if state["guide"] is not None and state["guide"].messageText in question_numbers:
        raise OutputRejected("질문과 가이드에 같은 본문을 함께 사용할 수 없다")

    # 표시만 공유한다. 같은 문구라도 problemRef와 bundleRef는 각 문제의 질문으로
    # 남아 있어야 Spring이 답변 연결과 질문 횟수를 문제별로 저장할 수 있다.
    emitted_questions: set[str] = set()
    parts: list[str] = []
    for index, item in enumerate(progress, start=1):
        outcome = item["outcome"]
        if outcome == "ASK_USER":
            body = questions[item["problemRef"]].text
            if body in emitted_questions:
                continue
            emitted_questions.add(body)
            numbers = question_numbers[body]
            if len(numbers) > 1:
                label = " · ".join(f"문제 {number}" for number in numbers)
                parts.append(f"{label}에 각각 답해 주세요.\n{body}")
                continue
        elif outcome == "PROVIDE_GUIDE":
            body = state["guide"].messageText
        elif outcome == "PROPOSE_TICKET":
            execution = state["ticketCreationResults"].get(item["problemRef"])
            actual = execution.result.outcome if execution and execution.result else "UNKNOWN"
            body = (
                {
                    "STORED": "티켓 생성과 담당자 배정이 확인되었습니다.",
                    "NOT_CREATED": "티켓이 생성되지 않았습니다. 담당자 배정 상태를 확인해 주세요.",
                    "PENDING": "티켓 생성 처리 중입니다. 저장 결과 확인이 필요합니다.",
                    "UNKNOWN": "티켓 저장 여부를 확인하지 못했습니다. 결과 확인이 필요합니다.",
                }[actual]
                if execution
                else (
                    "담당자에게 전달할 티켓 제안을 준비했습니다. "
                    "접수 및 담당자 배정 결과는 별도 확인이 필요합니다."
                )
            )
        elif outcome == "EXISTING_WORK":
            body = {
                "STORED": "기존 티켓의 저장이 확인되었습니다.",
                "PENDING": "기존 티켓의 저장 결과를 확인 중입니다.",
                "UNKNOWN": "기존 티켓의 저장 여부가 아직 확인되지 않았습니다.",
            }[item["existingWorkOutcome"]]
        elif outcome == "EXISTING_STATE":
            body = {
                "IN_SERVICE": "현재 문제는 처리 중 상태입니다.",
                "WAITING_CONFIRMATION": "현재 문제는 해결 확인 대기 상태입니다.",
                "RESOLVED": "현재 문제는 해결 완료로 기록되어 있습니다.",
            }[item["originalState"]]
        elif outcome == "OUT_OF_SCOPE":
            body = "지원 범위 밖의 문의입니다. 지원 시스템에 관한 문의를 이용해 주세요."
        elif outcome == "WAITING_SYSTEM":
            body = "보고 시스템을 확인하지 못했습니다. 화면에서 해당 시스템을 선택해 주세요."
        else:
            # 완료 정책과 답변 조립이 달라지더라도 조용히 본문을 생략하지 않는다.
            raise OutputRejected("공개 답변을 구성할 처리 결과가 없다")
        parts.append(f"문제 {index}\n{body}" if len(progress) > 1 else body)

    text = "\n\n".join(parts)
    required = [question.text for question in state["questions"].values()]
    if state["guide"] is not None:
        required.append(state["guide"].messageText)
    # 항목 중복은 위 조립 루프에서 막고 여기서는 필수 본문 누락만 확인한다.
    # 짧은 질문이 다른 질문·가이드에 포함됐다는 이유로 실패시키지 않는다.
    # 사용자 답변의 적절성·질문 횟수·실행 예산은 각각 별도 경계에서 검사한다.
    if any(body not in text for body in required):
        raise OutputRejected("최종 답변에 필요한 질문·가이드 본문이 없다")
    return text


def _validate_final_state(state: SupervisorState) -> None:
    """최종화 직전에 서버의 보고 시스템·출처·참조·제안 충돌을 다시 확인한다."""
    systems = {item.id for item in state["input"].systems}
    originals = {item.id: item for item in state["input"].problems}
    original_ids = set(originals)
    if not original_ids <= state["problems"].keys():
        raise OutputRejected("기존 문제가 최종 State에서 사라졌다")
    for key, problem in state["problems"].items():
        if not key.startswith("new:") and key not in original_ids:
            raise OutputRejected("새 문제에 실제 DB UUID를 임의로 부여할 수 없다")
        if key != problem.problemRef:
            raise OutputRejected("문제 map의 키와 참조가 다르다")
        original = originals.get(key)
        if (
            original is not None
            and original.reportedSystemId is not None
            and problem.reportedSystemCandidateId != original.reportedSystemId
        ):
            raise OutputRejected("서버가 확인한 보고 시스템이 변경됐다")
        if (
            problem.reportedSystemCandidateId is not None
            and problem.reportedSystemCandidateId not in systems
        ):
            raise OutputRejected("지원하지 않는 보고 시스템이다")
        validate_statements(state, problem.facts, problem_ref=problem.problemRef, confirmed=True)
        validate_statements(
            state, problem.assumptions, problem_ref=problem.problemRef, confirmed=False
        )
        validate_details(state, problem.problemRef, problem.details)
    for key, evidence in state["evidence"].items():
        if key != evidence.id:
            raise OutputRejected("근거 map의 키와 ID가 다르다")
        validate_evidence_scope(state["input"], evidence)
    validate_questions(state)
    if state["guide"] is not None:
        validate_guide(state, state["guide"])
    for key, ticket in state["ticketProposals"].items():
        if key != ticket.problemRef:
            raise OutputRejected("티켓 map의 키와 문제 참조가 다르다")
        validate_ticket(state, ticket)


def finalize_result(
    state: SupervisorState, *, image_observations: list[ImageObservation] | None = None
) -> SupervisorState:
    """새 제안을 최종 AiResult로 고정한 State를 반환한다.

    Args:
        state: 역할 호출을 마친 RUNNING 또는 FINALIZING State.
            실패 상태라면 제안을 비운 실패 결과만 만든다.
            상담 본문은 검증된 결과로 직접 구성하며 임의의 답변 초안은 받지 않는다.

    Returns:
        finalResult가 고정된 새 State. 같은 최종화 재요청은 기존 결과를 재사용한다.
        CASE_DRAFT는 상담 답변 없이 사례만 반환한다. 오류 결과에는 새 제안을 담지 않는다.

    Raises:
        OutputRejected / ValidationError: 미완료 호출·참조·결과 조합·본문 형식이 잘못된 경우.
        DeadlineExceeded / RunCancelled / BudgetExceeded: 실행 기한·취소·누적 한도 위반.
    """
    if state["finalResult"] is not None:
        if image_observations:
            raise OutputRejected("완료된 결과에 이미지 관찰을 추가할 수 없다")
        return state

    request = state["input"]
    if state["phase"] in ("FAILED", "TIMED_OUT", "SUPERSEDED"):
        result = AiResult(
            contractVersion="hops-system-1",
            runId=request.runId,
            kind=request.kind,
            inquiryId=request.inquiryId,
            inquiryVersion=request.inquiryVersion,
            status=state["phase"],
            answer=None,
            problemProposals=[],
            questions=[],
            guide=None,
            ticketProposals=[],
            caseProposal=None,
            evidence=[],
            error=state["error"],
            ticketCreationResults=list(state["ticketCreationResults"].values()),
        )
        return {**state, "finalResult": result}

    ensure_active(state)
    if state["phase"] not in ("RUNNING", "FINALIZING") or state["activeRole"] is not None:
        raise OutputRejected("역할 실행을 마친 뒤에만 최종화할 수 있다")
    candidate = transition_phase(state, "FINALIZING")
    image_proposals = []
    if image_observations:
        bound = bind_image_questions(
            request,
            list(candidate["questions"].values()),
            image_observations,
            proposals=list(candidate["problems"].values()),
        )
        if bound.unlinked:
            raise OutputRejected("이미지 관찰을 확인 질문에 연결할 수 없다")
        candidate = {
            **candidate,
            "questions": {
                (question.problemRef, question.bundleRef): question for question in bound.questions
            },
        }
        image_proposals = list(bound.observations)
    _validate_final_state(candidate)
    validate_completion(candidate)

    if request.kind == RunKind.INQUIRY_ANALYSIS:
        if candidate["caseProposal"] is not None:
            raise OutputRejected("상담 결과에 사례 초안을 넣을 수 없다")
        body = compose_public_answer(candidate)
        if any(observation.questionRef is None for observation in image_proposals):
            body += "\n\n이미지 관찰 일부는 확인되지 않아 문의 내용으로 확정하지 않았어요."
        result = AiResult(
            contractVersion="hops-system-1",
            runId=request.runId,
            kind=request.kind,
            inquiryId=request.inquiryId,
            inquiryVersion=request.inquiryVersion,
            status="SUCCEEDED",
            answer=body,
            problemProposals=([] if is_reply(candidate) else list(candidate["problems"].values())),
            questions=list(candidate["questions"].values()),
            guide=candidate["guide"],
            ticketProposals=list(candidate["ticketProposals"].values()),
            caseProposal=None,
            evidence=[] if is_reply(candidate) else list(candidate["evidence"].values()),
            error=None,
            # 되묻기만 사용자 답을 기다린다. 인사·감사 응답은 처리 중인 문제의 완료를 막지 않는다.
            requiresFollowUp=is_reply(candidate) and candidate["replyKind"] == "CLARIFY",
            ticketCreationResults=list(candidate["ticketCreationResults"].values()),
            imageObservations=image_proposals,
        )
    else:
        if (
            candidate["answer"] is not None
            or candidate["guide"] is not None
            or candidate["questions"]
            or candidate["ticketProposals"]
        ):
            raise OutputRejected("사례 초안 실행에는 상담 답변·질문·티켓이 없어야 한다")
        result = AiResult(
            contractVersion="hops-system-1",
            runId=request.runId,
            kind=request.kind,
            inquiryId=request.inquiryId,
            inquiryVersion=request.inquiryVersion,
            status="SUCCEEDED",
            answer=None,
            problemProposals=[],
            questions=[],
            guide=None,
            ticketProposals=[],
            caseProposal=candidate["caseProposal"],
            evidence=list(candidate["evidence"].values()),
            error=None,
        )

    # 중첩 모델도 다시 검증하고 복사하여, 최종 결과와 작업 map의 가변 목록을 분리한다.
    result = AiResult.model_validate(result.model_dump(mode="json"))
    if image_proposals:
        try:
            result.validate_image_observations_for(request)
        except ValueError as error:
            raise OutputRejected("이미지 관찰의 출처를 확인할 수 없다") from error
    candidate = {**candidate, "answer": result.answer, "finalResult": result}
    return transition_phase(candidate, "SUCCEEDED")
