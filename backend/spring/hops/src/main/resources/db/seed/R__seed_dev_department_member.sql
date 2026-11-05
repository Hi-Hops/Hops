-- ⚠️ 로컬 개발용 가상 데이터 (실제 회사·실제 사람이 아님)
-- 값의 정본은 docs/knowledge/VIRTUAL_COMPANY.md 4절(조직·직원·담당)이다. 이 파일은 그 문서에서 파생한다.
-- db 프로필에서만 실행된다 (application-db.properties의 spring.flyway.locations). 테스트 DB·운영에는 들어가지 않는다.
--
-- 반복 실행 마이그레이션(R__): 이 파일 내용이 바뀌면 서버 시작 시 다시 실행된다.
-- 그래서 모든 INSERT에 ON CONFLICT DO NOTHING을 붙여 이미 있는 행은 건너뛴다.
-- ⚠️ 이미 있는 행은 바꾸지 않는다. 이전 seed(부서 11개·회원 20명)가 들어간 DB는 부서 코드·사번·로그인 ID가 겹치지
--    않는 새 행만 추가되어 두 구성이 섞이므로, 반드시 DB를 새로 만든 뒤 적재한다(backend/spring/hops/README.md "처음 상태로 되돌리기").
--
-- ID를 고정값으로 넣는 이유: 팀원 모두의 PC에서 같은 회원·부서 ID가 되게 해서
-- "이 ID로 조회해 보세요"처럼 공유할 수 있게 한다. 마지막 12자리는 아래 규칙을 따른다.
--   부서      d0000000-0000-7000-8000-0000000000NN   NN = 정본 4.1절 표의 순서(01~05)
--   회원      a0000000-0000-7000-8000-000000000NNN   NNN = 사번 끝 3자리(사번 20260101 → ...000000000101)
--   역할 부여 b0000000-0000-7000-8000-0000000000NN   NN = 부여 순서
-- 자동 테스트가 직접 넣는 값(...0098·...0099)과 겹치지 않는다.
--
-- 모든 계정의 비밀번호: hops1234!  (아래 password_hash는 이 값의 BCrypt 해시)

-- 부서 5개: IT운영팀만 문의 처리 대상(is_routable = true). 나머지는 소속 정보다.
INSERT INTO department (id, code, name, is_routable) VALUES
    ('d0000000-0000-7000-8000-000000000001', 'MARKETING', '마케팅팀',    false),
    ('d0000000-0000-7000-8000-000000000002', 'SALES',     '영업팀',      false),
    ('d0000000-0000-7000-8000-000000000003', 'HR',        '인사팀',      false),
    ('d0000000-0000-7000-8000-000000000004', 'IT_OPS',    'IT운영팀',    true),
    ('d0000000-0000-7000-8000-000000000005', 'SYS_ADMIN', '시스템관리팀', false)
ON CONFLICT DO NOTHING;

-- 회원 8명: 문의자 4, 시스템 주담당자 3(시스템마다 한 명), 관리자 1
INSERT INTO member (id, login_id, employee_no, password_hash, name, department_id) VALUES
    -- 관리자
    ('a0000000-0000-7000-8000-000000000001', 'admin',       '20260001', '$2a$10$gx56aXrGqAl8Fu5FIOCrIOxb2IsZfxmoo8ir/nkkkGmYHwpy1a/1S', '문관리', 'd0000000-0000-7000-8000-000000000005'),
    -- 시스템 주담당자 (전자결재 / 공통 로그인 / 파일 공유)
    ('a0000000-0000-7000-8000-000000000011', 'ea_owner',    '20260011', '$2a$10$gx56aXrGqAl8Fu5FIOCrIOxb2IsZfxmoo8ir/nkkkGmYHwpy1a/1S', '한도윤', 'd0000000-0000-7000-8000-000000000004'),
    ('a0000000-0000-7000-8000-000000000012', 'login_owner', '20260012', '$2a$10$gx56aXrGqAl8Fu5FIOCrIOxb2IsZfxmoo8ir/nkkkGmYHwpy1a/1S', '장하린', 'd0000000-0000-7000-8000-000000000004'),
    ('a0000000-0000-7000-8000-000000000013', 'file_owner',  '20260013', '$2a$10$gx56aXrGqAl8Fu5FIOCrIOxb2IsZfxmoo8ir/nkkkGmYHwpy1a/1S', '구태현', 'd0000000-0000-7000-8000-000000000004'),
    -- 문의자
    ('a0000000-0000-7000-8000-000000000101', 'requester1',  '20260101', '$2a$10$gx56aXrGqAl8Fu5FIOCrIOxb2IsZfxmoo8ir/nkkkGmYHwpy1a/1S', '정다은', 'd0000000-0000-7000-8000-000000000001'),
    ('a0000000-0000-7000-8000-000000000102', 'requester2',  '20260102', '$2a$10$gx56aXrGqAl8Fu5FIOCrIOxb2IsZfxmoo8ir/nkkkGmYHwpy1a/1S', '오세준', 'd0000000-0000-7000-8000-000000000001'),
    ('a0000000-0000-7000-8000-000000000103', 'requester3',  '20260103', '$2a$10$gx56aXrGqAl8Fu5FIOCrIOxb2IsZfxmoo8ir/nkkkGmYHwpy1a/1S', '한지우', 'd0000000-0000-7000-8000-000000000002'),
    ('a0000000-0000-7000-8000-000000000104', 'requester4',  '20260104', '$2a$10$gx56aXrGqAl8Fu5FIOCrIOxb2IsZfxmoo8ir/nkkkGmYHwpy1a/1S', '윤서아', 'd0000000-0000-7000-8000-000000000003')
ON CONFLICT DO NOTHING;

-- 역할 부여: 역할이 없는 회원은 일반 사용자 (IT운영팀 소속이면 담당자). 관리자는 두 역할을 모두 가진다.
INSERT INTO member_role (id, member_id, role) VALUES
    ('b0000000-0000-7000-8000-000000000001', 'a0000000-0000-7000-8000-000000000001', 'BIZ_ADMIN'),
    ('b0000000-0000-7000-8000-000000000002', 'a0000000-0000-7000-8000-000000000001', 'AGENT_OPS_ADMIN')
ON CONFLICT DO NOTHING;
