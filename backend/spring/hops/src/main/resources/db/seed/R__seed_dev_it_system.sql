-- ⚠️ 로컬 개발용 가상 데이터 (실제 회사·실제 시스템이 아님)
-- db 프로필에서만 실행된다 (application-db.properties의 spring.flyway.locations). 테스트 DB·운영에는 들어가지 않는다.
--
-- 반복 실행 마이그레이션(R__): 내용이 바뀌면 서버 시작 시 다시 실행되므로 모든 INSERT에 ON CONFLICT DO NOTHING을 붙인다.
-- R__ 파일은 이름 순으로 실행되어 회원 시드(R__seed_dev_department_member) 뒤에 실행된다. 주담당자가 그 회원이다.
--
-- 시스템·기능 ID는 docs/DATA.md 2절 고정 카탈로그(hops-demo-v1)의 값을 그대로 쓴다. 프론트 Mock·AI 테스트도 같은 ID를 쓴다.
-- 기능 코드는 DATA.md에 값이 없어 이 파일에서 정했다 (system_feature.code는 AI가 기능을 제안할 때 쓰는 코드).

-- 시스템 3개. 주담당자는 시스템마다 한 명이다 (정본 docs/knowledge/VIRTUAL_COMPANY.md 4.2절):
-- 전자결재 ea_owner(한도윤), 공통 로그인 login_owner(장하린), 파일 공유 file_owner(구태현)
-- ⚠️ ON CONFLICT DO NOTHING이라 이전 seed가 들어간 DB의 주담당자는 바뀌지 않는다. DB를 새로 만들어 적재한다.
INSERT INTO it_system (id, code, name, description, primary_owner_member_id) VALUES
    ('3320ce84-c47c-592c-b599-6e968e12b0a3', 'ELECTRONIC_APPROVAL', '전자결재',
     '결재 문서 작성·조회, 결재선과 결재 진행 상태, 첨부 파일 열람', 'a0000000-0000-7000-8000-000000000011'),
    ('8ae35be5-1857-58c0-a478-e05b4217ff95', 'COMMON_LOGIN', '공통 로그인',
     '사내 시스템 로그인·SSO, 계정 잠금·비밀번호, 시스템 접근 권한 신청', 'a0000000-0000-7000-8000-000000000012'),
    ('22d935d0-2ed8-58a7-a9b8-d73f717ed3c4', 'FILE_SHARING', '파일 공유',
     '공유 폴더·파일 열람과 공유 링크, 폴더별 접근 권한', 'a0000000-0000-7000-8000-000000000013')
ON CONFLICT DO NOTHING;

-- 같은 코드의 수동 개발 데이터가 있으면 그 시스템 ID를 사용해 기존 티켓과 FK를 보존한다.
-- 기능 10개
INSERT INTO system_feature (id, it_system_id, code, name, description) VALUES
    -- 전자결재
    ('8c3ade64-251a-5b46-ba24-7bacb5384900', (SELECT id FROM it_system WHERE code='ELECTRONIC_APPROVAL'), 'DOCUMENT_CREATE',    '문서 작성', '결재 문서 작성·임시 저장·상신'),
    ('5b540d0d-ef38-58b3-bfe6-9cfb5b20109e', (SELECT id FROM it_system WHERE code='ELECTRONIC_APPROVAL'), 'DOCUMENT_VIEW',      '문서 조회', '결재 문서 목록·상세 조회'),
    ('74c8f41e-e3a1-5828-a317-2ccca15895c7', (SELECT id FROM it_system WHERE code='ELECTRONIC_APPROVAL'), 'APPROVAL_STATUS',    '결재 상태', '결재선·승인·반려 등 진행 상태'),
    ('1da6defb-2244-5cc5-ae70-bc75d8c5d4d4', (SELECT id FROM it_system WHERE code='ELECTRONIC_APPROVAL'), 'ATTACHMENT_VIEW',    '첨부 열람', '결재 문서 첨부 파일 열기·내려받기'),
    -- 공통 로그인
    ('213718e2-a478-56c0-a8ad-456b3607516c', (SELECT id FROM it_system WHERE code='COMMON_LOGIN'), 'LOGIN_SSO',          '로그인·SSO', '사내 시스템 로그인과 통합 인증(SSO)'),
    ('0da3984c-7a69-5337-ba51-7b282f5f2659', (SELECT id FROM it_system WHERE code='COMMON_LOGIN'), 'ACCOUNT_ACCESS',     '계정 접근', '계정 잠금·비밀번호 초기화'),
    ('7a361da4-7df4-592d-845f-75829318cd7c', (SELECT id FROM it_system WHERE code='COMMON_LOGIN'), 'PERMISSION_REQUEST', '권한 신청', '시스템별 접근 권한 신청·승인'),
    -- 파일 공유
    ('152e8439-1e01-5e26-9749-f77df1cb65fc', (SELECT id FROM it_system WHERE code='FILE_SHARING'), 'FILE_VIEW',          '파일 열람', '공유 폴더의 파일 열기·내려받기'),
    ('06bb5aba-b278-5cc1-9e5b-41c38e674991', (SELECT id FROM it_system WHERE code='FILE_SHARING'), 'FILE_SHARE',         '파일 공유', '파일·폴더 공유와 공유 링크'),
    ('b7b05865-02c9-5e39-a22e-d103b2a00057', (SELECT id FROM it_system WHERE code='FILE_SHARING'), 'ACCESS_PERMISSION',  '접근 권한', '폴더별 접근 권한 부여·회수')
ON CONFLICT DO NOTHING;
