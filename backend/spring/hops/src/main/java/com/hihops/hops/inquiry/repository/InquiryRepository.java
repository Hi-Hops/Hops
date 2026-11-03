package com.hihops.hops.inquiry.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import com.hihops.hops.inquiry.domain.Inquiry;

import jakarta.persistence.LockModeType;

/** inquiry 테이블 저장·조회. 기본 save·findById는 JpaRepository가 제공한다. */
public interface InquiryRepository extends JpaRepository<Inquiry, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM Inquiry i WHERE i.id=:id AND i.deletedAt IS NULL")
    Optional<Inquiry> findForUpdate(UUID id);
}
