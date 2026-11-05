-- 원격 develop 또는 기존 AWS 시연용 계정·시스템을 사용하는 가상 문의 fixture.
-- Flyway 자동 실행 대상이 아니다. 전용 개발 DB에 psql -X -v ON_ERROR_STOP=1로 수동 적용한다.
-- 기존 계정·시스템·업무 데이터를 수정하지 않으며, 이 fixture가 이미 있으면 전체 실행을 중단한다.
BEGIN;
LOCK TABLE member, it_system, inquiry, message IN SHARE ROW EXCLUSIVE MODE;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM member WHERE (id, login_id) IN (
                       ('a0000000-0000-7000-8000-000000000101'::uuid, 'requester1'),
                       ('f5b994d0-b857-5aba-bd46-2c49c7e8d1f0'::uuid, 'demo.requester')) AND deleted_at IS NULL)
       OR NOT EXISTS (SELECT 1 FROM member WHERE (id, login_id) IN (
                       ('a0000000-0000-7000-8000-000000000103'::uuid, 'requester3'),
                       ('ca1fd3bb-10f4-5504-94da-704b8a5f394b'::uuid, 'demo.file')) AND deleted_at IS NULL)
       OR (SELECT count(*) FROM it_system WHERE deleted_at IS NULL AND (id, code) IN (
           ('3320ce84-c47c-592c-b599-6e968e12b0a3'::uuid, 'ELECTRONIC_APPROVAL'),
           ('8ae35be5-1857-58c0-a478-e05b4217ff95'::uuid, 'COMMON_LOGIN'),
           ('22d935d0-2ed8-58a7-a9b8-d73f717ed3c4'::uuid, 'FILE_SHARING'))) <> 3 THEN
        RAISE EXCEPTION '원격 개발용 seed의 활성 문의자와 시스템이 필요합니다.';
    END IF;
    IF EXISTS (SELECT 1 FROM inquiry WHERE id IN (
           '10000000-0000-7000-8000-000000000001', '10000000-0000-7000-8000-000000000002',
           '10000000-0000-7000-8000-000000000003', '10000000-0000-7000-8000-000000000004',
           '10000000-0000-7000-8000-000000000005')
           OR inquiry_number IN ('DEMO-Q-001', 'DEMO-Q-002', 'DEMO-Q-003', 'DEMO-Q-004', 'DEMO-Q-005'))
       OR EXISTS (SELECT 1 FROM message WHERE id IN (
           '20000000-0000-7000-8000-000000000001', '20000000-0000-7000-8000-000000000002',
           '20000000-0000-7000-8000-000000000003', '20000000-0000-7000-8000-000000000004',
           '20000000-0000-7000-8000-000000000005', '20000000-0000-7000-8000-000000000006')) THEN
        RAISE EXCEPTION '시연 문의 또는 메시지의 ID·번호가 이미 존재합니다. 기존 데이터를 변경하지 않습니다.';
    END IF;
END $$;

CREATE TEMP TABLE demo_seed_actor (label text PRIMARY KEY, id uuid NOT NULL) ON COMMIT DROP;
INSERT INTO demo_seed_actor
SELECT 'requester', id FROM member WHERE (id, login_id) IN (
    ('a0000000-0000-7000-8000-000000000101'::uuid, 'requester1'),
    ('f5b994d0-b857-5aba-bd46-2c49c7e8d1f0'::uuid, 'demo.requester'))
    AND deleted_at IS NULL ORDER BY (login_id = 'requester1') DESC LIMIT 1;
INSERT INTO demo_seed_actor
SELECT 'other', id FROM member WHERE (id, login_id) IN (
    ('a0000000-0000-7000-8000-000000000103'::uuid, 'requester3'),
    ('ca1fd3bb-10f4-5504-94da-704b8a5f394b'::uuid, 'demo.file'))
    AND deleted_at IS NULL ORDER BY (login_id = 'requester3') DESC LIMIT 1;

-- 현재 원격 문의 상세 API는 문제·AI 실행이 없는 문의만 조회할 수 있다.
-- 문의자 메시지만 기록하며 AI가 실행되거나 티켓이 생성되었다고 표시하지 않는다.
INSERT INTO inquiry (id, inquiry_number, requester_member_id, title, inquiry_status, version, created_at, updated_at) VALUES
('10000000-0000-7000-8000-000000000001', 'DEMO-Q-001', (SELECT id FROM demo_seed_actor WHERE label = 'requester'), '[가상] 전자결재 첨부 열람 문의', 'IN_PROGRESS', 2, now() - interval '4 hours', now() - interval '3 hours 50 minutes'),
('10000000-0000-7000-8000-000000000002', 'DEMO-Q-002', (SELECT id FROM demo_seed_actor WHERE label = 'requester'), '[가상] 공통 로그인 권한 문의', 'IN_PROGRESS', 1, now() - interval '3 hours', now() - interval '3 hours'),
('10000000-0000-7000-8000-000000000003', 'DEMO-Q-003', (SELECT id FROM demo_seed_actor WHERE label = 'requester'), '[가상] 파일 공유 링크 문의', 'IN_PROGRESS', 1, now() - interval '2 hours', now() - interval '2 hours'),
('10000000-0000-7000-8000-000000000004', 'DEMO-Q-004', (SELECT id FROM demo_seed_actor WHERE label = 'requester'), '[가상] 화면 접속 문의', 'IN_PROGRESS', 1, now() - interval '1 hour', now() - interval '1 hour'),
('10000000-0000-7000-8000-000000000005', 'DEMO-Q-005', (SELECT id FROM demo_seed_actor WHERE label = 'other'), '[가상] 다른 문의자의 전자결재 문의', 'IN_PROGRESS', 1, now(), now());

INSERT INTO message (id, inquiry_id, sequence_no, sender_type, content, message_status, payload, created_at, updated_at) VALUES
('20000000-0000-7000-8000-000000000001', '10000000-0000-7000-8000-000000000001', 1, 'USER', '전자결재에서 첨부파일을 열면 오류가 나요.', 'COMPLETED', '{"contractVersion":"hops-system-1","requestId":"22000000-0000-7000-8000-000000000001","answerRunId":null,"problemLinks":[],"questionBundles":[],"guide":null,"pendingTarget":false}', now() - interval '4 hours', now() - interval '4 hours'),
('20000000-0000-7000-8000-000000000002', '10000000-0000-7000-8000-000000000001', 2, 'USER', '문서 목록은 열리지만 첨부파일은 열리지 않습니다.', 'COMPLETED', '{"contractVersion":"hops-system-1","requestId":"22000000-0000-7000-8000-000000000002","answerRunId":null,"problemLinks":[],"questionBundles":[],"guide":null,"pendingTarget":false}', now() - interval '3 hours 50 minutes', now() - interval '3 hours 50 minutes'),
('20000000-0000-7000-8000-000000000003', '10000000-0000-7000-8000-000000000002', 1, 'USER', '공통 로그인에서 새 업무 권한을 신청하고 싶어요.', 'COMPLETED', '{"contractVersion":"hops-system-1","requestId":"22000000-0000-7000-8000-000000000003","answerRunId":null,"problemLinks":[],"questionBundles":[],"guide":null,"pendingTarget":false}', now() - interval '3 hours', now() - interval '3 hours'),
('20000000-0000-7000-8000-000000000004', '10000000-0000-7000-8000-000000000003', 1, 'USER', '파일 공유 링크를 열면 접근할 수 없다는 메시지가 보여요.', 'COMPLETED', '{"contractVersion":"hops-system-1","requestId":"22000000-0000-7000-8000-000000000004","answerRunId":null,"problemLinks":[],"questionBundles":[],"guide":null,"pendingTarget":false}', now() - interval '2 hours', now() - interval '2 hours'),
('20000000-0000-7000-8000-000000000005', '10000000-0000-7000-8000-000000000004', 1, 'USER', '화면이 열리지 않아요. 어떤 시스템인지는 확인 중이에요.', 'COMPLETED', '{"contractVersion":"hops-system-1","requestId":"22000000-0000-7000-8000-000000000005","answerRunId":null,"problemLinks":[],"questionBundles":[],"guide":null,"pendingTarget":false}', now() - interval '1 hour', now() - interval '1 hour'),
('20000000-0000-7000-8000-000000000006', '10000000-0000-7000-8000-000000000005', 1, 'USER', '전자결재 문서의 상태를 확인하고 싶어요.', 'COMPLETED', '{"contractVersion":"hops-system-1","requestId":"22000000-0000-7000-8000-000000000006","answerRunId":null,"problemLinks":[],"questionBundles":[],"guide":null,"pendingTarget":false}', now(), now());
COMMIT;
