package com.hihops.hops.relation.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.hihops.hops.relation.domain.RelationQuestionTemplate;

/** relation_question_template 조회. 직원 상담은 승인되고 공개 표시가 있는 문구만 쓴다. */
public interface RelationQuestionTemplateRepository extends JpaRepository<RelationQuestionTemplate, UUID> {
    Optional<RelationQuestionTemplate> findByCatalogKeyAndDeletedAtIsNull(String catalogKey);

    /** 직원에게 물을 수 있는 문구: 승인, 직원 공개 표시, 직원 공개 범위, 확인 항목도 승인되고 직원 공개 표시가 있는 것. */
    @Query("""
            SELECT t FROM RelationQuestionTemplate t JOIN t.checkItem c
            WHERE c.id = :checkItemId AND t.deletedAt IS NULL AND c.deletedAt IS NULL
              AND t.reviewStatus = com.hihops.hops.knowledge.domain.ReviewStatus.APPROVED
              AND c.reviewStatus = com.hihops.hops.knowledge.domain.ReviewStatus.APPROVED
              AND t.employeeDisclosable = true AND c.employeeDisclosable = true
              AND t.accessLevel = com.hihops.hops.knowledge.domain.AccessLevel.EMPLOYEE
            ORDER BY t.catalogKey
            """)
    List<RelationQuestionTemplate> findAskableByCheckItemId(UUID checkItemId);
}
