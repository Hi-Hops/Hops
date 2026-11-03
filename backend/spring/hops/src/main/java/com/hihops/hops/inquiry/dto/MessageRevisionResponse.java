package com.hihops.hops.inquiry.dto;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.hihops.hops.inquiry.domain.Message;
import com.hihops.hops.inquiry.domain.MessageRevision;

/**
 * 정정하기 전 원문 한 건 (specs/contracts/DATA_TYPES.md 신규 절 MessageRevision: revisionNo·content·createdAt).
 *
 * <p>createdAt은 그 원문을 쓴 시각이다. message_revision.created_at은 원문을 이력으로 옮긴 시각, 즉 다음 차수로 고친 시각이라
 * 그대로 쓰면 한 칸씩 밀린다. 그래서 1차는 메시지를 처음 보낸 시각, k차는 (k-1)차를 고친 시각을 쓴다.
 */
public record MessageRevisionResponse(int revisionNo, String content, Instant createdAt) {

    /** @param archived 이 메시지의 정정 전 원문 (차수 순) */
    public static List<MessageRevisionResponse> listOf(Message message, List<MessageRevision> archived) {
        List<MessageRevisionResponse> responses = new ArrayList<>(archived.size());
        Instant writtenAt = message.getCreatedAt();
        for (MessageRevision revision : archived) {
            responses.add(new MessageRevisionResponse(revision.getRevisionNo(), revision.getContent(), writtenAt));
            writtenAt = revision.getCreatedAt(); // 이 원문을 고친 시각 = 다음 차수를 쓴 시각
        }
        return responses;
    }
}
