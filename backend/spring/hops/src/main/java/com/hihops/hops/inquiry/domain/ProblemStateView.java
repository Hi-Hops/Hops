package com.hihops.hops.inquiry.domain;

import java.util.Optional;

import com.hihops.hops.ticket.domain.Ticket;

/**
 * 문제의 DB 상태를 외부 ProblemState 값으로 바꾼다 (docs/DATA.md 11.2, DATA_TYPES ProblemState).
 * AI 입력(InputProblem.state)과 문의 상세(Problem.state)가 같은 규칙을 쓴다.
 *
 * <ul>
 *   <li>티켓 생성이 진행·미확인·미생성(creation_outcome PENDING·NOT_CREATED·UNKNOWN)이거나 TICKET_FAILED면 TICKET_PENDING</li>
 *   <li>CLARIFYING은 질문 응답 대기면 WAITING_USER, 그 외 ANALYZING. 무엇이 응답 대기인지는 호출하는 쪽이 정한다</li>
 *   <li>SYSTEM_PENDING→WAITING_SYSTEM, GUIDED→WAITING_CONFIRMATION, RESOLVED_WITHOUT_TICKET→RESOLVED, OUT_OF_SCOPE는 그대로</li>
 *   <li>TICKET_CREATED는 연결 티켓 상태로 IN_SERVICE(대기·처리 중)·WAITING_CONFIRMATION·RESOLVED</li>
 * </ul>
 */
public final class ProblemStateView {

    private ProblemStateView() {
    }

    /**
     * @param ticket          이 문제의 (삭제되지 않은) 티켓
     * @param waitingForUser  CLARIFYING 문제가 지금 문의자의 답을 기다리는지
     * @throws IllegalStateException TICKET_CREATED인데 티켓이 없을 때. 저장 규칙이 깨진 것이라 짐작하지 않는다
     */
    public static String of(InquiryProblem problem, Optional<Ticket> ticket, boolean waitingForUser) {
        CreationOutcome outcome = problem.getCreationOutcome();
        if (outcome == CreationOutcome.PENDING || outcome == CreationOutcome.NOT_CREATED
                || outcome == CreationOutcome.UNKNOWN) {
            return "TICKET_PENDING";
        }
        return switch (problem.getProblemStatus()) {
            case CLARIFYING -> waitingForUser ? "WAITING_USER" : "ANALYZING";
            case SYSTEM_PENDING -> "WAITING_SYSTEM";
            case GUIDED -> "WAITING_CONFIRMATION";
            case RESOLVED_WITHOUT_TICKET -> "RESOLVED";
            case TICKET_FAILED -> "TICKET_PENDING";
            case OUT_OF_SCOPE -> "OUT_OF_SCOPE";
            case TICKET_CREATED -> ticket
                    .map(found -> switch (found.getTicketStatus()) {
                        case OPEN, IN_PROGRESS -> "IN_SERVICE";
                        case WAITING_CONFIRM -> "WAITING_CONFIRMATION";
                        case RESOLVED -> "RESOLVED";
                    })
                    .orElseThrow(() -> new IllegalStateException("티켓이 생성된 문제에 티켓이 없다: " + problem.getId()));
        };
    }
}
