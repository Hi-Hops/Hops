package com.hihops.hops.knowledge.domain;

/**
 * 데이터 용도 (docs/ERD.md "지식 문서 knowledge_document" data_split).
 *
 * <p>평가용 정답·숨겨진 요구사항이 검색 자료에 섞이면 AI 평가 점수가 부풀려진다. 그래서 RAG만 검색에 쓴다.
 */
public enum DataSplit {
    /** 검색 자료 */
    RAG,
    /** 개발 중 확인용 */
    DEV,
    /** 평가 정답용 (검색 제외) */
    EVAL
}
