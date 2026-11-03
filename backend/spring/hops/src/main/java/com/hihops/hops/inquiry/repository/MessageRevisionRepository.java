package com.hihops.hops.inquiry.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.hihops.hops.inquiry.domain.MessageRevision;

/** message_revision 테이블 저장·조회. 기본 save·findById는 JpaRepository가 제공한다. */
public interface MessageRevisionRepository extends JpaRepository<MessageRevision, UUID> {

    /** 여러 메시지의 정정 전 원문을 한 번에 읽는다 (메시지마다 따로 조회하지 않게). 차수 순이다. */
    @Query("SELECT r FROM MessageRevision r WHERE r.message.id IN :messageIds ORDER BY r.revisionNo")
    List<MessageRevision> findByMessageIds(Collection<UUID> messageIds);
}
