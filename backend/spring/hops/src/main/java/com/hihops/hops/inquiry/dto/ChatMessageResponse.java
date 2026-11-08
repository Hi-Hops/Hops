package com.hihops.hops.inquiry.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.hihops.hops.inquiry.attachment.AttachmentHistoryReader.AttachmentRef;
import com.hihops.hops.inquiry.domain.Message;
import com.hihops.hops.inquiry.domain.MessagePayload;
import com.hihops.hops.inquiry.domain.SenderType;

/**
 * 저장된 대화 메시지 (specs/contracts/DATA_TYPES.md 신규 절 ChatMessage).
 *
 * <p>requestId는 사용자 메시지를 만든 변경 요청 ID다. 프론트는 이 값으로 보내는 중이던 말풍선과 저장된 메시지를 합친다.
 * revisions는 정정하기 전 원문만 담는다. 정정하지 않은 메시지는 revision=1, revisions=[]다 (docs/DATA.md 11.3).
 */
public record ChatMessageResponse(UUID id, UUID inquiryId, @Nullable UUID requestId, int revision, int sequenceNo,
        SenderType sender, String content, List<UUID> problemIds, List<MessageRevisionResponse> revisions,
        Instant createdAt, @JsonInclude(JsonInclude.Include.NON_EMPTY) List<AttachmentRef> attachments) {

    /** content: 문의자에게 보여 줄 본문. 저장된 본문에서 지금 볼 수 없는 안내를 숨긴 값일 수 있다. */
    public static ChatMessageResponse of(Message message, String content, MessagePayload payload,
            List<MessageRevisionResponse> revisions, List<AttachmentRef> attachments) {
        return new ChatMessageResponse(message.getId(), message.getInquiry().getId(), payload.requestId(),
                message.getRevision(), message.getSequenceNo(), message.getSenderType(), content,
                payload.problemIds(message.getRevision()), List.copyOf(revisions), message.getCreatedAt(), List.copyOf(attachments));
    }
}
