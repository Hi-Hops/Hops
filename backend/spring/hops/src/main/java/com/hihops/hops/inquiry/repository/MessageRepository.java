package com.hihops.hops.inquiry.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;

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
    @Query(value = "SELECT * FROM message WHERE payload->'resolutionRequest'->>'id'=CAST(:id AS text) AND deleted_at IS NULL AND message_status='COMPLETED'", nativeQuery = true)
    Optional<Message> findResolution(UUID id);
    Optional<Message> findByIdAndInquiryIdAndDeletedAtIsNull(UUID id, UUID inquiryId);
    boolean existsByInquiry_IdAndSenderTypeAndSequenceNoGreaterThanAndDeletedAtIsNull(UUID inquiryId,
            com.hihops.hops.inquiry.domain.SenderType senderType, int sequenceNo);

    @Query("SELECT m FROM Message m WHERE m.inquiry.id = :inquiryId AND m.deletedAt IS NULL "
            + "AND m.messageStatus = com.hihops.hops.inquiry.domain.MessageStatus.COMPLETED "
            + "AND m.sequenceNo > :afterSequence ORDER BY m.sequenceNo, m.id")
    List<Message> findCompletedAfter(UUID inquiryId, int afterSequence, Pageable pageable);

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

    /** 가장 최근에 저장이 끝난 AI 메시지. 대상 없이 보낸 사용자 메시지가 어느 질문·안내의 답인지 정하는 데 쓴다. */
    Optional<Message> findFirstByInquiry_IdAndSenderTypeAndMessageStatusAndDeletedAtIsNullOrderBySequenceNoDesc(
            UUID inquiryId, com.hihops.hops.inquiry.domain.SenderType senderType,
            com.hihops.hops.inquiry.domain.MessageStatus messageStatus);

    /** 저장이 끝난 메시지 전체, 순번 순. AI 입력의 messages를 만든다. */
    @Query("""
            SELECT m FROM Message m
            WHERE m.inquiry.id = :inquiryId
              AND m.messageStatus = com.hihops.hops.inquiry.domain.MessageStatus.COMPLETED
              AND m.deletedAt IS NULL
            ORDER BY m.sequenceNo
            """)
    List<Message> findAllCompletedInOrder(UUID inquiryId);

    /** 다음 메시지 순번을 정할 마지막 순번. 삭제·미완료 행도 (문의, 순번) 유일 제약에 걸리므로 모두 센다. 메시지가 없으면 0. */
    @Query("SELECT COALESCE(MAX(m.sequenceNo), 0) FROM Message m WHERE m.inquiry.id = :inquiryId")
    int findLastSequenceNo(UUID inquiryId);

    /**
     * 이 문제의 안내를 담은 가장 최근 AI 메시지 (payload.guide.problemId, docs/DATA.md 11.3). 없으면 비어 있다.
     * 가이드 미해결 뒤 다시 안내하면 새 메시지가 생기므로 최신 것을 쓴다.
     */
    @Query(value = """
            SELECT * FROM message
            WHERE inquiry_id = :inquiryId
              AND sender_type = 'AI'
              AND message_status = 'COMPLETED'
              AND deleted_at IS NULL
              AND payload -> 'guide' ->> 'problemId' = CAST(:problemId AS text)
            ORDER BY sequence_no DESC
            LIMIT 1
            """, nativeQuery = true)
    Optional<Message> findLatestGuide(UUID inquiryId, UUID problemId);

    /**
     * 질문 묶음을 담은 AI 메시지, 보낸 순서대로. 문제별로 이미 물은 항목(askedFields)을 모은다 (docs/DATA.md 11.3).
     */
    @Query(value = """
            SELECT * FROM message
            WHERE inquiry_id = :inquiryId
              AND sender_type = 'AI'
              AND message_status = 'COMPLETED'
              AND deleted_at IS NULL
              AND jsonb_array_length(COALESCE(payload -> 'questionBundles', '[]'::jsonb)) > 0
            ORDER BY sequence_no
            """, nativeQuery = true)
    List<Message> findAiMessagesWithQuestionBundles(UUID inquiryId);

    /**
     * 이 문제에 같은 bundleRef의 질문 묶음을 이미 저장한 AI 메시지가 있는지 (payload.questionBundles, docs/DATA.md 11.3).
     * 재분석·재전달이 같은 묶음을 다시 세지 않게 한다. probe는 {"questionBundles":[{"problemId":..,"bundleRef":..}]} 형식 JSON이다.
     */
    @Query(value = """
            SELECT EXISTS (SELECT 1 FROM message
                WHERE inquiry_id = :inquiryId AND sender_type = 'AI' AND deleted_at IS NULL
                  AND payload @> CAST(:probe AS jsonb))
            """, nativeQuery = true)
    boolean existsQuestionBundle(UUID inquiryId, String probe);

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
