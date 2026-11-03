package com.hihops.hops.inquiry.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.hihops.hops.inquiry.domain.Message;

/** message 테이블 저장·조회. 기본 save·findById는 JpaRepository가 제공한다. */
public interface MessageRepository extends JpaRepository<Message, UUID> {
    @Query(value = "SELECT * FROM message WHERE payload->'resolutionRequest'->>'id'=CAST(:id AS text) AND deleted_at IS NULL AND message_status='COMPLETED'", nativeQuery = true)
    Optional<Message> findResolution(UUID id);
    Optional<Message> findByIdAndInquiryIdAndDeletedAtIsNull(UUID id, UUID inquiryId);

    @Query("SELECT m FROM Message m WHERE m.inquiry.id = :inquiryId AND m.deletedAt IS NULL "
            + "AND m.messageStatus = com.hihops.hops.inquiry.domain.MessageStatus.COMPLETED "
            + "AND m.sequenceNo > :afterSequence ORDER BY m.sequenceNo, m.id")
    List<Message> findCompletedAfter(UUID inquiryId, int afterSequence, Pageable pageable);
}
