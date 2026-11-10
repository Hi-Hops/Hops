package com.hihops.hops.inquiry.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import com.hihops.hops.inquiry.domain.Inquiry;
import com.hihops.hops.inquiry.domain.InquiryStatus;

import jakarta.persistence.LockModeType;

/** inquiry 테이블 저장·조회. 기본 save·findById는 JpaRepository가 제공한다. */
public interface InquiryRepository extends JpaRepository<Inquiry, UUID> {

    /** 본인 문의 목록 (삭제된 문의 제외). 정렬·페이지는 넘겨받은 Pageable을 따른다. */
    Page<Inquiry> findByRequester_IdAndDeletedAtIsNull(UUID requesterId, Pageable pageable);

    Page<Inquiry> findByRequester_IdAndInquiryStatusAndDeletedAtIsNull(UUID requesterId, InquiryStatus status,
            Pageable pageable);

    /** 본인 문의 한 건. 남의 문의·삭제된 문의·없는 문의는 모두 비어 있다(존재 여부를 구분해 알려 주지 않으려고). */
    Optional<Inquiry> findByIdAndRequester_IdAndDeletedAtIsNull(UUID id, UUID requesterId);

    /** 변경 명령용. 같은 문의의 버전·현재 실행을 바꾸는 명령이 동시에 오면 행 잠금으로 하나씩 처리한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Inquiry> findByIdAndDeletedAtIsNull(UUID id);
}
