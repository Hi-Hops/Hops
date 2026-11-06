package com.hihops.hops.inquiry.service;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.hihops.hops.agent.domain.AgentRun;
import com.hihops.hops.agent.domain.RunStatus;
import com.hihops.hops.inquiry.domain.Inquiry;
import com.hihops.hops.inquiry.domain.SenderType;
import com.hihops.hops.inquiry.repository.MessageRepository;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.json.JsonMapper;

/**
 * 문의가 사용자의 추가 응답을 기다리는 중인지 판단한다. 문의 완료 집계가 이 판단 하나를 쓴다.
 *
 * <p>현재 실행이 성공했고 결과 적용 때 검증한 {@code requiresFollowUp=true}가 기록돼 있으며,
 * 그 실행의 AI 답변 뒤에 사용자 메시지가 아직 저장되지 않았으면 기다리는 중이다.
 * 사용자가 답하면 새 실행이 시작되지 않는 입력(배정된 문제의 보완 등)이어도 대기가 풀린다.
 * 사용자가 끝내 답하지 않으면 대기가 유지되어 문의는 완료되지 않는다(의도된 동작).
 * 필드 형식 검증은 결과 적용({@link InquiryAnswerHandler})에서 한다.
 */
@Component
@RequiredArgsConstructor
public class FollowUpWait {

    private final MessageRepository messages;
    private final JsonMapper jsonMapper;

    public boolean awaiting(Inquiry inquiry) {
        AgentRun run = inquiry.getCurrentRun();
        if (run == null || run.getRunStatus() != RunStatus.SUCCEEDED || run.getValidationResult() == null
                || !jsonMapper.readTree(run.getValidationResult()).path("requiresFollowUp").asBoolean(false)) {
            return false;
        }
        UUID answerId;
        try {
            answerId = UUID.fromString(jsonMapper.readTree(run.getInputSnapshot()).path("answerMessageId").asString(""));
        } catch (IllegalArgumentException exception) {
            return true; // 답변 위치를 알 수 없으면 대기를 유지하는 안전한 쪽으로 판단한다
        }
        return messages.findByIdAndInquiryIdAndDeletedAtIsNull(answerId, inquiry.getId())
                .map(answer -> !messages.existsByInquiry_IdAndSenderTypeAndSequenceNoGreaterThanAndDeletedAtIsNull(
                        inquiry.getId(), SenderType.USER, answer.getSequenceNo()))
                .orElse(true);
    }
}
