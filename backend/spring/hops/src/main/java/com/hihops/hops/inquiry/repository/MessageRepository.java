package com.hihops.hops.inquiry.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.hihops.hops.inquiry.domain.Message;

/** message 테이블 저장·조회. 기본 save·findById는 JpaRepository가 제공한다. */
public interface MessageRepository extends JpaRepository<Message, UUID> {
}
