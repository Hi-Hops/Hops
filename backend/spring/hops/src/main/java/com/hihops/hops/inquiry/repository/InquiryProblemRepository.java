package com.hihops.hops.inquiry.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.hihops.hops.inquiry.domain.InquiryProblem;

/** inquiry_problem 테이블 저장·조회. 기본 save·findById는 JpaRepository가 제공한다. */
public interface InquiryProblemRepository extends JpaRepository<InquiryProblem, UUID> {
}
