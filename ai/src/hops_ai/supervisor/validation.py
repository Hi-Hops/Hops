"""병합 함수가 함께 사용하는 작은 검증 함수. 실제 최신 권한 조회는 어댑터에서 수행한다."""

import re
from datetime import UTC, datetime

from hops_ai.contracts.roles import InquiryResult
from hops_ai.contracts.state import SupervisorState
from hops_ai.contracts.types import QUESTION_LIMIT
from hops_ai.contracts.wire import AiRunRequest
from hops_ai.harness.errors import (
    BudgetExceeded,
    DeadlineExceeded,
    InputRejected,
    OutputRejected,
    RunCancelled,
)
from hops_ai.rules.progress import get_problem_progress
from hops_ai.rules.proposals import validate_problem_ref

# 코드가 단순 인사로 인정하는 표현. 목록 밖의 인사는 되묻기로 처리되는 안전한 쪽으로 실패한다.
_GREETING_WORDS = (
    "안녕하십니까",
    "안녕하세요",
    "안녕하셔요",
    "안녕",
    "반갑습니다",
    "반가워요",
    "hello",
    "hi",
    "hey",
)


def only_greetings(text: str) -> bool:
    """확인된 인사 표현만으로 이뤄진 문장인지. 미확인 표현에는 문의 내용이 있을 수 있다."""
    remaining = re.sub(r"[\s.,!?~…，。！？～]+", "", text).casefold()
    if not remaining:
        return False
    while remaining:
        greeting = next((word for word in _GREETING_WORDS if remaining.startswith(word)), None)
        if greeting is None:
            return False
        remaining = remaining[len(greeting) :]
    return True


def greeting_confirmed(state: "SupervisorState") -> bool:
    """아직 분석하지 않은 사용자 메시지가 모두 확인된 인사 표현인지.

    기존 문제가 없으면 전체 사용자 메시지를 본다. 앞선 실행이 실패해 분석되지 못한 문의가
    인사 뒤에 묻히지 않게 한다. 기존 문제가 있으면 마지막 AI 답변 이후의 사용자 메시지만
    본다. Spring은 사용자가 대상을 고르지 않은 메시지를 문제에 연결하지 않으므로, 연결
    여부로는 이미 분석한 메시지와 새 메시지를 구분할 수 없다.
    """
    request = state["input"]
    user_messages = [message for message in request.messages if message.sender == "USER"]
    if request.problems:
        answered = max(
            (message.sequenceNo for message in request.messages if message.sender == "AI"),
            default=0,
        )
        user_messages = [message for message in user_messages if message.sequenceNo > answered]
    return bool(user_messages) and all(only_greetings(message.content) for message in user_messages)


def reply_allowed(state: "SupervisorState") -> bool:
    """역할 제안 없이 대화 응답(REPLY)으로 끝낼 수 있는 상담 State인지.

    아직 역할이 실행되지 않았거나, 실행된 Inquiry가 문제를 하나도 찾지 못한 경우만 허용한다.
    질문·안내·티켓이 준비됐다면 그 제안을 최종화해야 하므로 대화 응답으로 덮지 않는다.
    """
    return (
        state["input"].kind == "INQUIRY_ANALYSIS"
        and any(message.sender == "USER" for message in state["input"].messages)
        and not state["questions"]
        and not state["ticketProposals"]
        and not state["ticketCreationResults"]
        and state["guide"] is None
        and state["problems"].keys() == {problem.id for problem in state["input"].problems}
        and all(
            isinstance(result, InquiryResult) and not result.problems and not result.questions
            for result in state["roleResults"].values()
        )
    )


def is_reply(state: "SupervisorState") -> bool:
    """검증된 REPLY 결정으로 끝나는 상담인지."""
    return state["nextAction"] == "REPLY" and state["replyKind"] is not None


def validate_completion(state: "SupervisorState") -> None:
    """실행 종류별 최소 종료 조건을 검사하고 미처리 대상의 조기 종료를 막는다.

    상담은 모든 대상 문제에 질문·안내·티켓 제안 또는 안내 가능한 기존 결과와
    범위/시스템 대기 안내가 있어야 한다. 질문을 반환하는 것은 이번 run의 종료이며
    문제 해결 완료를 뜻하지 않는다. CASE_DRAFT의 reusable=false도 유효한 완료다.

    이 검사는 결과의 존재를 확인한다. 출처·권한·질문 횟수·제안 충돌 검증은 병합과
    최종화에서 함께 수행하며, 문자열이나 search EMPTY만으로 완료를 인정하지 않는다.
    실패·취소 결과를 만들 때에는 이 성공 조건을 적용하지 않는다.

    Raises:
        OutputRejected: 사례 결과가 없거나, 상담 대상이 없거나, 미처리 문제가 남은 경우.
    """
    if state["input"].kind == "CASE_DRAFT":
        if state["caseProposal"] is None:
            raise OutputRejected("사례 제안이 준비되기 전에 종료할 수 없다")
        return
    if is_reply(state):
        if not reply_allowed(state):
            raise OutputRejected("역할 제안이 준비된 상담을 대화 응답으로 끝낼 수 없다")
        return
    progress = get_problem_progress(state)
    if not progress or any(item["outcome"] is None for item in progress):
        raise OutputRejected("모든 대상 문제에 결과 또는 대기 안내가 준비돼야 종료할 수 있다")


def validate_request(request: AiRunRequest) -> None:
    """입력 목록의 중복과 문제·시스템·기존 작업의 연결 관계를 확인한다."""
    groups = [
        request.problemIds,
        request.accessContext.systemIds,
        request.accessContext.allowedDocumentScopes,
        [item.id for item in request.problems],
        [item.id for item in request.messages],
        [item.sequenceNo for item in request.messages],
        [item.id for item in request.systems],
        [item.code for item in request.systems],
        [item.workItemId for item in request.existingWorkItems],
        [feature.id for system in request.systems for feature in system.features],
    ]
    if any(len(group) != len(set(group)) for group in groups):
        raise InputRejected("실행 입력의 식별자 또는 순번이 중복됐다")
    problems = {item.id for item in request.problems}
    systems = {item.id for item in request.systems}
    if (
        not set(request.problemIds) <= problems
        or not set(request.accessContext.systemIds) <= systems
    ):
        raise InputRejected("입력의 문제 또는 접근 시스템 참조가 유효하지 않다")
    if (
        request.accessContext.purpose == "EMPLOYEE_HELP"
        and "SYSTEM_STAFF" in request.accessContext.allowedDocumentScopes
    ):
        raise InputRejected("직원 상담에 담당자 내부 자료 범위를 포함할 수 없다")
    if (
        request.accessContext.retrievalScopes is not None
        and request.accessContext.purpose != "EMPLOYEE_HELP"
    ):
        # 내부 판단 경계(옵션)는 직원 상담에서만 쓴다.
        # 다른 목적은 allowedDocumentScopes가 읽기 범위다.
        raise InputRejected("직원 상담이 아닌 실행에 내부 판단 범위를 둘 수 없다")
    for message in request.messages:
        if len(message.problemIds) != len(set(message.problemIds)):
            raise InputRejected("메시지의 문제 참조가 중복됐다")
        if not set(message.problemIds) <= problems:
            raise InputRejected("메시지의 문제 참조가 입력에 없다")
    for problem in request.problems:
        if problem.reportedSystemId is not None and problem.reportedSystemId not in systems:
            raise InputRejected("보고 시스템이 지원 시스템에 없다")
        features = {
            feature.id
            for system in request.systems
            if system.id == problem.reportedSystemId
            for feature in system.features
        }
        if problem.reportedFeatureId is not None and problem.reportedFeatureId not in features:
            raise InputRejected("보고 기능이 해당 시스템에 없다")
    for work in request.existingWorkItems:
        if (
            work.workItemId != work.problemId
            or work.problemId not in problems
            or work.inquiryVersion > request.inquiryVersion
        ):
            raise InputRejected("기존 업무가 다른 문제나 미래 입력 버전을 참조한다")
    if request.workRecord is not None:
        record = request.workRecord
        if (
            request.problemIds != [record.problemId]
            or record.problemId not in problems
            or record.systemId not in systems
        ):
            raise InputRejected("사례 원 기록과 대상 문제·시스템이 다르다")
        for message in request.messages:
            if message.problemIds != [record.problemId]:
                raise InputRejected("사례 실행에는 원 기록 문제의 관련 원문만 제공해야 한다")


def ensure_active(state: "SupervisorState") -> None:
    """취소·기한·전체 누적 한도를 검사하며 사용량을 초기화하거나 증가시키지 않는다."""
    if state["cancellation"]["cancelled"]:
        raise RunCancelled
    if datetime.fromisoformat(state["input"].expiresAt) <= datetime.now(UTC):
        raise DeadlineExceeded
    limits = state["input"].limits
    maxima = {
        "calls": limits.maxModelCalls,
        "inputTokens": limits.maxInputTokens,
        "outputTokens": limits.maxOutputTokens,
        "toolCalls": limits.maxToolCalls,
        "retries": limits.maxRetries,
    }
    for name, maximum in maxima.items():
        used = state["usage"][name]
        if type(used) is not int or used < 0:
            raise OutputRejected("누적 사용량은 0 이상의 정수여야 한다")
        if used > maximum:
            raise BudgetExceeded("실행 전체의 누적 한도를 초과했다")


def validate_questions(state: "SupervisorState") -> None:
    """문제별 질문 하나·서버의 남은 질문 수·다른 제안과의 충돌을 검사한다."""
    seen: set[str] = set()
    counts = {problem.id: problem.questionCount for problem in state["input"].problems}
    for key, question in state["questions"].items():
        validate_problem_ref(state, question.problemRef)
        if key != (question.problemRef, question.bundleRef) or question.problemRef in seen:
            raise OutputRejected("문제당 하나의 질문 묶음만 허용한다")
        if counts.get(question.problemRef, 0) >= QUESTION_LIMIT:
            raise OutputRejected("문제의 질문 한도를 초과했다")
        if question.problemRef in state["ticketProposals"] or (
            state["guide"] is not None and state["guide"].problemRef == question.problemRef
        ):
            raise OutputRejected("같은 문제에 질문과 안내·티켓을 동시에 제안할 수 없다")
        seen.add(question.problemRef)
