-- 회원 권한 문맥 버전 추가 (docs/ERD.md 6.1절 member.policy_revision)
-- V1은 이미 모든 환경에 적용돼 고치지 않고 이 파일로 컬럼을 더한다.
-- 시스템 주담당자나 역할이 바뀔 때 영향받는 회원 행과 같은 트랜잭션에서 1씩 늘려, 예전 권한 문맥으로 만든 토큰·구독을 막는다.
-- 이미 적용된 이 파일은 고치지 않는다. 바꿀 내용이 생기면 다음 번호 파일을 새로 추가한다.
ALTER TABLE member
    ADD COLUMN policy_revision INT NOT NULL DEFAULT 1;
