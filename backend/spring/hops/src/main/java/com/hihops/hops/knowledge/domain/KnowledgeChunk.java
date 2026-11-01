package com.hihops.hops.knowledge.domain;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.hihops.hops.global.entity.CreatedOnlyEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 검색 조각. 문서 버전을 검색하기 좋게 잘게 나눈 조각과 임베딩이다 (docs/ERD.md "검색 조각 knowledge_chunk").
 *
 * <p>임베딩(embedding)은 글의 의미를 숫자 목록으로 바꾼 것이다. 표현이 달라도 뜻이 비슷하면 숫자 목록도 가까워서,
 * "비번 바꾸는 주기"로 물어도 "비밀번호는 90일마다 바꾼다" 조각을 찾을 수 있다.
 * DB 타입은 pgvector의 vector이고 Java에서는 float[]로 다룬다 (hibernate-vector).
 *
 * <p>차원 수(숫자 개수)는 임베딩 모델을 정할 때 함께 정한다. 그 전에는 컬럼에 차원 제한이 없어
 * columnDefinition을 "vector"로 적는다. 정해지면 vector(1536)처럼 바꾸는 마이그레이션을 추가한다.
 */
@Entity
@Table(name = "knowledge_chunk",
        uniqueConstraints = @UniqueConstraint(name = "uk_knowledge_chunk_version_chunk",
                columnNames = {"knowledge_document_version_id", "chunk_no"}))
public class KnowledgeChunk extends CreatedOnlyEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "knowledge_document_version_id", nullable = false)
    private KnowledgeDocumentVersion knowledgeDocumentVersion;

    @Column(name = "chunk_no", nullable = false)
    private int chunkNo;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @JdbcTypeCode(SqlTypes.VECTOR)
    @Column(nullable = false, columnDefinition = "vector")
    private float[] embedding;

    protected KnowledgeChunk() {
    }

    public KnowledgeChunk(KnowledgeDocumentVersion knowledgeDocumentVersion, int chunkNo, String content,
            float[] embedding) {
        this.knowledgeDocumentVersion = knowledgeDocumentVersion;
        this.chunkNo = chunkNo;
        this.content = content;
        this.embedding = embedding;
    }

    public KnowledgeDocumentVersion getKnowledgeDocumentVersion() {
        return knowledgeDocumentVersion;
    }

    public int getChunkNo() {
        return chunkNo;
    }

    public String getContent() {
        return content;
    }

    public float[] getEmbedding() {
        return embedding;
    }
}
