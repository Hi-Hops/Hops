package com.hihops.hops.inquiry.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.hihops.hops.inquiry.domain.Inquiry;

/** inquiry 테이블 저장·조회. 기본 save·findById는 JpaRepository가 제공한다. */
public interface InquiryRepository extends JpaRepository<Inquiry, UUID> {

    /** 본인 문의 목록 (삭제된 문의 제외). 정렬·페이지는 넘겨받은 Pageable을 따른다. */
    Page<Inquiry> findByRequester_IdAndDeletedAtIsNull(UUID requesterId, Pageable pageable);

    /** 본인 문의 한 건. 남의 문의·삭제된 문의·없는 문의는 모두 비어 있다(존재 여부를 구분해 알려 주지 않으려고). */
    Optional<Inquiry> findByIdAndRequester_IdAndDeletedAtIsNull(UUID id, UUID requesterId);
}
