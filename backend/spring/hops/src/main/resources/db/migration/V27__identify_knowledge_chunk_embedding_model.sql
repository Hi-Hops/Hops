-- 기존 모델 미상 벡터는 그대로 보존하며 P0 검색에 사용하지 않는다.
ALTER TABLE hops_knowledge.knowledge_chunk ADD COLUMN embedding_model VARCHAR(100);
ALTER TABLE hops_knowledge.knowledge_chunk ADD CONSTRAINT ck_knowledge_chunk_small_dimensions
    CHECK (embedding_model IS DISTINCT FROM 'text-embedding-3-small' OR vector_dims(embedding) = 1536);
