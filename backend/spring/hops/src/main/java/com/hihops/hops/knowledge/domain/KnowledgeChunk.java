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
 *
 * <p>테이블은 같은 DB의 hops_knowledge 스키마에 있다 (docs/ERD.md 6.1절). 업무 테이블과 FK를 유지하면서,
 * 나중에 AI 서버 계정에 이 스키마의 조각 쓰기 권한만 주기 위해서다.
 * contentDigest(원문 버전의 해시)와 chunkDigest(이 조각 본문의 해시)로 모델에 넣기 전에 원문과 대조한다.
 */
@Entity
@Table(name = "knowledge_chunk", schema = "hops_knowledge",
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

    // 고정 길이 CHAR(64). VARCHAR로 기대하지 않게 JDBC 타입을 CHAR로 알려준다
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "content_digest", nullable = false, length = 64)
    private String contentDigest;

    // 고정 길이 CHAR(64). VARCHAR로 기대하지 않게 JDBC 타입을 CHAR로 알려준다
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "chunk_digest", nullable = false, length = 64)
    private String chunkDigest;

    protected KnowledgeChunk() {
    }

    public KnowledgeChunk(KnowledgeDocumentVersion knowledgeDocumentVersion, int chunkNo, String content,
            float[] embedding, String contentDigest, String chunkDigest) {
        this.knowledgeDocumentVersion = knowledgeDocumentVersion;
        this.chunkNo = chunkNo;
        this.content = content;
        this.embedding = embedding;
        this.contentDigest = contentDigest;
        this.chunkDigest = chunkDigest;
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

    public String getContentDigest() {
        return contentDigest;
    }

    public String getChunkDigest() {
        return chunkDigest;
    }
}
