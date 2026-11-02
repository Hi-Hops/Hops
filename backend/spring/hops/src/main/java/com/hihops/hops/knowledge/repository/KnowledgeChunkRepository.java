package com.hihops.hops.knowledge.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.hihops.hops.knowledge.domain.KnowledgeChunk;

/** knowledge_chunk 테이블 저장·조회. 기본 save·findById는 JpaRepository가 제공한다. 유사도 검색 쿼리는 검색 기능에서 추가한다. */
public interface KnowledgeChunkRepository extends JpaRepository<KnowledgeChunk, UUID> {
}
