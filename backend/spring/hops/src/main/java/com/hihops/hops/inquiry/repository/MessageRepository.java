package com.hihops.hops.inquiry.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.hihops.hops.inquiry.domain.Message;

/**
 * message 테이블 저장·조회. 기본 save·findById는 JpaRepository가 제공한다.
 *
 * <p>대화 조회는 저장이 끝난(COMPLETED) 메시지만 본다 (WIRE_CONTRACT 12.8). 예약만 된 AI 답변 ID는 행이 없으므로 나오지 않는다.
 */
public interface MessageRepository extends JpaRepository<Message, UUID> {

    /** 가장 최근 메시지부터 limit개 (순번 내림차순). */
    @Query("""
            SELECT m FROM Message m
            WHERE m.inquiry.id = :inquiryId
              AND m.messageStatus = com.hihops.hops.inquiry.domain.MessageStatus.COMPLETED
              AND m.deletedAt IS NULL
            ORDER BY m.sequenceNo DESC
            """)
    List<Message> findLatestCompleted(UUID inquiryId, Limit limit);

    /** beforeSequenceNo보다 앞선 메시지를 가까운 것부터 limit개 (순번 내림차순, 경계 미포함). */
    @Query("""
            SELECT m FROM Message m
            WHERE m.inquiry.id = :inquiryId
              AND m.messageStatus = com.hihops.hops.inquiry.domain.MessageStatus.COMPLETED
              AND m.deletedAt IS NULL
              AND m.sequenceNo < :beforeSequenceNo
            ORDER BY m.sequenceNo DESC
            """)
    List<Message> findCompletedBefore(UUID inquiryId, int beforeSequenceNo, Limit limit);

    /**
     * 보완 대상을 아직 정하지 못한 메시지 ID (InquiryResult.pendingTargetMessageIds), 보낸 순서대로.
     * payload.pendingTarget=true로 표시한다 (docs/DATA.md 11.3).
     */
    @Query(value = """
            SELECT id FROM message
            WHERE inquiry_id = :inquiryId
              AND message_status = 'COMPLETED'
              AND deleted_at IS NULL
              AND payload @> '{"pendingTarget": true}'
            ORDER BY sequence_no
            """, nativeQuery = true)
    List<UUID> findPendingTargetIds(UUID inquiryId);
}
