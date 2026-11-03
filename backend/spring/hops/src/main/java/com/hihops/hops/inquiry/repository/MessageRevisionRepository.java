package com.hihops.hops.inquiry.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.hihops.hops.inquiry.domain.MessageRevision;

/** message_revision 테이블 저장·조회. 기본 save·findById는 JpaRepository가 제공한다. */
public interface MessageRevisionRepository extends JpaRepository<MessageRevision, UUID> {
    List<MessageRevision> findByMessageIdInOrderByRevisionNoAsc(Collection<UUID> messageIds);
}
