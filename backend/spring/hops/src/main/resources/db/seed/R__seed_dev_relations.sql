-- ⚠️ 로컬 개발용 가상 데이터 (실제 문의·실제 업무 지식이 아님)
-- ⚠️ 자동 생성 파일이다. 직접 고치지 않는다.
--    원본: 코퍼스 런북·정책의 front matter(증상·원인·확인 항목), docs/knowledge/relations/questions.yml(질문 문구),
--          docs/knowledge/cases/*.yml(사례 연결)
--    다시 만들기: uv run scripts/knowledge/build_relation_seed.py
--    어긋남 검사:  uv run scripts/knowledge/build_relation_seed.py --check
-- 값의 범위와 ID는 docs/knowledge/VIRTUAL_COMPANY.md 6.1절(관계 카탈로그)이 정한다.
-- db 프로필에서만 실행된다. 테스트 DB·운영에는 들어가지 않는다.
--
-- R__ 파일 실행 순서: department_member → it_system → knowledge → knowledge_cases → relations (이름 순).
-- 근거 문서 버전과 사례 문서 버전이 먼저 있어야 하므로 이 파일이 마지막이다.
--
-- ID는 카탈로그 ID에서 정해지는 고정값이다: uuid5(NAMESPACE_URL, "hops-relation.example/" + 종류 + "/" + 키).
-- 모든 행은 승인(APPROVED) 상태이며 근거 문서 버전을 가진다. 연결은 후보이며 원인을 확정하지 않는다.
--
-- 모든 INSERT는 ON CONFLICT DO NOTHING이라 이미 있는 행은 바꾸지 않는다. 원본을 고치면 팀원은 DB를 새로 만들어 적재한다
-- (backend/spring/hops/README.md "처음 상태로 되돌리기").

-- 증상 (직원이 말하는 현상)
INSERT INTO relation_symptom (id, catalog_key, label, it_system_id, system_feature_id, access_level, review_status, source_document_version_id)
VALUES
    ('a4498651-19cb-59a5-8eff-8d15abb911ab', 'sym-login-permission-pending', '권한 신청이 오래 승인되지 않음', (SELECT id FROM it_system WHERE code = 'COMMON_LOGIN'), (SELECT f.id FROM system_feature f JOIN it_system s ON s.id = f.it_system_id WHERE s.code = 'COMMON_LOGIN' AND f.code = 'PERMISSION_REQUEST'), 'EMPLOYEE', 'APPROVED', '1bac2f3d-b47a-5cdc-91c1-b9408f02c4c7'),
    ('8a8ad9e9-b844-51ad-a8b0-e48bd8d25f2f', 'sym-ea-attach-not-open', '첨부 파일이 열리지 않음', (SELECT id FROM it_system WHERE code = 'ELECTRONIC_APPROVAL'), (SELECT f.id FROM system_feature f JOIN it_system s ON s.id = f.it_system_id WHERE s.code = 'ELECTRONIC_APPROVAL' AND f.code = 'ATTACHMENT_VIEW'), 'EMPLOYEE', 'APPROVED', 'cf830c91-fe02-5749-b73b-5d929a95af6a'),
    ('9700e62c-dfde-52a5-881d-8abbc634dced', 'sym-ea-doc-not-listed', '문서가 문서함에 보이지 않음', (SELECT id FROM it_system WHERE code = 'ELECTRONIC_APPROVAL'), (SELECT f.id FROM system_feature f JOIN it_system s ON s.id = f.it_system_id WHERE s.code = 'ELECTRONIC_APPROVAL' AND f.code = 'DOCUMENT_VIEW'), 'EMPLOYEE', 'APPROVED', 'ad95ebbc-909d-5b37-a74c-3e75a82371ca'),
    ('d1997e85-cd0b-546d-9f22-5e2302f89804', 'sym-file-not-open', '공유 파일이나 폴더가 열리지 않음', (SELECT id FROM it_system WHERE code = 'FILE_SHARING'), (SELECT f.id FROM system_feature f JOIN it_system s ON s.id = f.it_system_id WHERE s.code = 'FILE_SHARING' AND f.code = 'FILE_VIEW'), 'EMPLOYEE', 'APPROVED', '6698dff8-74f2-52a5-9223-802608959695'),
    ('bed926e5-95ef-51ad-a97c-42175e33a5e8', 'sym-login-loop', '로그인했는데 다시 로그인 화면으로 돌아옴', (SELECT id FROM it_system WHERE code = 'COMMON_LOGIN'), (SELECT f.id FROM system_feature f JOIN it_system s ON s.id = f.it_system_id WHERE s.code = 'COMMON_LOGIN' AND f.code = 'LOGIN_SSO'), 'EMPLOYEE', 'APPROVED', '1c19dc5a-c91d-5256-b16a-7c89de592266')
ON CONFLICT DO NOTHING;

-- 가능 원인 (담당자 전용)
INSERT INTO relation_cause (id, catalog_key, label, it_system_id, access_level, review_status, source_document_version_id)
VALUES
    ('9549d923-6257-517b-927f-4cf3b9393171', 'cause-login-teamlead-confirm-missing', '쓰기·관리 권한에 필요한 팀장 확인이 누락됨', (SELECT id FROM it_system WHERE code = 'COMMON_LOGIN'), 'STAFF', 'APPROVED', '1bac2f3d-b47a-5cdc-91c1-b9408f02c4c7'),
    ('74f16074-0423-53c5-8cc1-2bb550f722fd', 'cause-ea-preview-unsupported', '미리보기를 지원하지 않는 형식', (SELECT id FROM it_system WHERE code = 'ELECTRONIC_APPROVAL'), 'STAFF', 'APPROVED', 'cf830c91-fe02-5749-b73b-5d929a95af6a'),
    ('5b27c619-b0ca-5d74-bc21-46f209e8164d', 'cause-ea-attach-scanning', '업로드 직후 보안 검사 중', (SELECT id FROM it_system WHERE code = 'ELECTRONIC_APPROVAL'), 'STAFF', 'APPROVED', 'cf830c91-fe02-5749-b73b-5d929a95af6a'),
    ('1322523b-07df-5ffa-a20a-fe95a2d3fc34', 'cause-ea-storage-error', '첨부 저장소의 일시 오류', (SELECT id FROM it_system WHERE code = 'ELECTRONIC_APPROVAL'), 'STAFF', 'APPROVED', 'cf830c91-fe02-5749-b73b-5d929a95af6a'),
    ('86f5ba5b-34d4-5855-992b-86a76c8b9506', 'cause-ea-no-doc-permission', '문서 열람 권한이 없어 첨부를 볼 수 없음', (SELECT id FROM it_system WHERE code = 'ELECTRONIC_APPROVAL'), 'STAFF', 'APPROVED', 'cf830c91-fe02-5749-b73b-5d929a95af6a'),
    ('0bb3cca4-aabd-5539-8af7-c5daea416069', 'cause-ea-period-out-of-range', '문서의 기안일이 조회 기간 밖', (SELECT id FROM it_system WHERE code = 'ELECTRONIC_APPROVAL'), 'STAFF', 'APPROVED', 'ad95ebbc-909d-5b37-a74c-3e75a82371ca'),
    ('61c5d651-b7fe-5c33-b3cf-00ee9998de55', 'cause-ea-no-view-permission', '문의자가 기안자·결재선·참조에 없어 열람 권한이 없음', (SELECT id FROM it_system WHERE code = 'ELECTRONIC_APPROVAL'), 'STAFF', 'APPROVED', 'ad95ebbc-909d-5b37-a74c-3e75a82371ca'),
    ('a6410f1a-3115-5955-9ee6-71554878d994', 'cause-ea-draft-not-submitted', '상신하지 않은 임시 저장 문서', (SELECT id FROM it_system WHERE code = 'ELECTRONIC_APPROVAL'), 'STAFF', 'APPROVED', 'ad95ebbc-909d-5b37-a74c-3e75a82371ca'),
    ('0eb3ea97-8a36-5ca2-a16a-44074278f9c7', 'cause-ea-filter-narrow', '입력한 상태·제목 키워드 조건이 좁음', (SELECT id FROM it_system WHERE code = 'ELECTRONIC_APPROVAL'), 'STAFF', 'APPROVED', 'ad95ebbc-909d-5b37-a74c-3e75a82371ca'),
    ('76262c45-d403-548e-8e07-209380a4c986', 'cause-file-folder-no-access', '폴더 접근 권한이 없음', (SELECT id FROM it_system WHERE code = 'FILE_SHARING'), 'STAFF', 'APPROVED', '6698dff8-74f2-52a5-9223-802608959695'),
    ('efa2e023-3e8f-54ed-ad23-0c87d6d2341e', 'cause-file-moved-deleted', '파일이 삭제되었거나 이동됨', (SELECT id FROM it_system WHERE code = 'FILE_SHARING'), 'STAFF', 'APPROVED', '6698dff8-74f2-52a5-9223-802608959695'),
    ('2c991309-28fb-5193-9fce-b0d8471d911d', 'cause-file-link-expired', '공유 링크의 만료일이 지남', (SELECT id FROM it_system WHERE code = 'FILE_SHARING'), 'STAFF', 'APPROVED', '6698dff8-74f2-52a5-9223-802608959695'),
    ('e62d9087-3138-5f03-90b6-585cdc18d4e9', 'cause-login-cookie-blocked', '브라우저가 쿠키를 차단해 SSO 세션이 저장되지 않음', (SELECT id FROM it_system WHERE code = 'COMMON_LOGIN'), 'STAFF', 'APPROVED', '1c19dc5a-c91d-5256-b16a-7c89de592266'),
    ('29707867-49be-5104-9966-e89a5773c6c1', 'cause-login-clock-skew', '단말 시각이 서버와 5분 넘게 어긋나 세션이 곧바로 만료로 판정됨', (SELECT id FROM it_system WHERE code = 'COMMON_LOGIN'), 'STAFF', 'APPROVED', '1c19dc5a-c91d-5256-b16a-7c89de592266'),
    ('b8a4331d-7139-520f-b9b5-5816b8ff08f8', 'cause-login-linked-system-auth', '연동 시스템의 SSO 세션 검증이 실패함', (SELECT id FROM it_system WHERE code = 'COMMON_LOGIN'), 'STAFF', 'APPROVED', '1c19dc5a-c91d-5256-b16a-7c89de592266')
ON CONFLICT DO NOTHING;

-- 확인 항목 (직원이 스스로 답할 수 있는 항목만 employee_disclosable = true)
INSERT INTO relation_check_item (id, catalog_key, label, it_system_id, employee_disclosable, access_level, review_status, source_document_version_id)
VALUES
    ('ba6502bf-9f97-5cd9-ad6e-52d519e32fec', 'check-login-permission-level', '신청한 권한 종류(읽기·쓰기·관리)', (SELECT id FROM it_system WHERE code = 'COMMON_LOGIN'), true, 'STAFF', 'APPROVED', '1bac2f3d-b47a-5cdc-91c1-b9408f02c4c7'),
    ('9a1c525d-db15-5000-8d2c-4386cf968cc6', 'check-login-teamlead-confirm', '소속 팀장 확인을 요청했는지', (SELECT id FROM it_system WHERE code = 'COMMON_LOGIN'), true, 'STAFF', 'APPROVED', '1bac2f3d-b47a-5cdc-91c1-b9408f02c4c7'),
    ('6a5bf11a-7f04-5880-94e8-4d50e71a0c5d', 'check-ea-file-format', '열리지 않는 첨부 파일의 형식', (SELECT id FROM it_system WHERE code = 'ELECTRONIC_APPROVAL'), true, 'STAFF', 'APPROVED', 'cf830c91-fe02-5749-b73b-5d929a95af6a'),
    ('f2577cf9-9d9c-5382-85e0-e1ed1ce9a0fc', 'check-ea-upload-time', '첨부 파일을 올린 시점', (SELECT id FROM it_system WHERE code = 'ELECTRONIC_APPROVAL'), true, 'STAFF', 'APPROVED', 'cf830c91-fe02-5749-b73b-5d929a95af6a'),
    ('45d1dac1-5ffe-5418-a646-6f4de00072ae', 'check-ea-error-message', '화면에 표시된 문구', (SELECT id FROM it_system WHERE code = 'ELECTRONIC_APPROVAL'), true, 'STAFF', 'APPROVED', 'cf830c91-fe02-5749-b73b-5d929a95af6a'),
    ('3be2bce9-ff98-520d-8223-55a5e4d46690', 'check-ea-retry-result', '잠시 뒤 다시 시도해도 같은지', (SELECT id FROM it_system WHERE code = 'ELECTRONIC_APPROVAL'), true, 'STAFF', 'APPROVED', 'cf830c91-fe02-5749-b73b-5d929a95af6a'),
    ('85554a73-4f69-5279-b63b-14687c05127e', 'check-ea-search-period', '문의자가 설정한 조회 기간', (SELECT id FROM it_system WHERE code = 'ELECTRONIC_APPROVAL'), true, 'STAFF', 'APPROVED', 'ad95ebbc-909d-5b37-a74c-3e75a82371ca'),
    ('523020e0-2d32-555e-94a7-d7a4f588d89b', 'check-ea-draft-date', '찾는 문서를 기안한 시기', (SELECT id FROM it_system WHERE code = 'ELECTRONIC_APPROVAL'), true, 'STAFF', 'APPROVED', 'ad95ebbc-909d-5b37-a74c-3e75a82371ca'),
    ('1f6fca67-d74b-51bf-b72f-c41252383e6b', 'check-ea-filter-condition', '조회할 때 입력한 상태·제목 키워드', (SELECT id FROM it_system WHERE code = 'ELECTRONIC_APPROVAL'), true, 'STAFF', 'APPROVED', 'ad95ebbc-909d-5b37-a74c-3e75a82371ca'),
    ('d8f0e7d1-fd4a-5c99-8dc7-3d962ea2eb2e', 'check-ea-submit-state', '문서를 상신했는지, 임시 저장 상태인지', (SELECT id FROM it_system WHERE code = 'ELECTRONIC_APPROVAL'), true, 'STAFF', 'APPROVED', 'ad95ebbc-909d-5b37-a74c-3e75a82371ca'),
    ('9fac2b79-c9bf-54fb-83e2-1fbe20f79249', 'check-ea-participation', '문의자가 그 문서의 기안자인지, 결재선·참조에 있는지', (SELECT id FROM it_system WHERE code = 'ELECTRONIC_APPROVAL'), true, 'STAFF', 'APPROVED', 'ad95ebbc-909d-5b37-a74c-3e75a82371ca'),
    ('b318b727-10ab-53ea-ae72-07977daa754d', 'check-ea-view-permission-record', '시스템에 남은 문서의 기안자·결재선·참조 기록', (SELECT id FROM it_system WHERE code = 'ELECTRONIC_APPROVAL'), false, 'STAFF', 'APPROVED', 'ad95ebbc-909d-5b37-a74c-3e75a82371ca'),
    ('c9bf79b7-84f6-50f4-b95c-23edd5235b4a', 'check-file-error-message', '화면에 표시된 문구', (SELECT id FROM it_system WHERE code = 'FILE_SHARING'), true, 'STAFF', 'APPROVED', '6698dff8-74f2-52a5-9223-802608959695'),
    ('562fe407-0681-548f-baf0-ee60f88b4051', 'check-file-access-route', '폴더 목록에서 열었는지, 링크로 열었는지', (SELECT id FROM it_system WHERE code = 'FILE_SHARING'), true, 'STAFF', 'APPROVED', '6698dff8-74f2-52a5-9223-802608959695'),
    ('0977f720-0864-5bdf-9ab5-a0706c460563', 'check-file-recent-change', '그 파일이 최근 삭제되거나 이동됐는지 아는지', (SELECT id FROM it_system WHERE code = 'FILE_SHARING'), true, 'STAFF', 'APPROVED', '6698dff8-74f2-52a5-9223-802608959695'),
    ('bdd07d15-85d2-54ae-9a86-51fcf944d03f', 'check-file-link-received', '공유 링크를 받은 시점', (SELECT id FROM it_system WHERE code = 'FILE_SHARING'), true, 'STAFF', 'APPROVED', '6698dff8-74f2-52a5-9223-802608959695'),
    ('a9e27417-a5c2-52eb-a94d-46187438e8e1', 'check-login-browser-cookie', '브라우저의 쿠키 차단 설정 여부', (SELECT id FROM it_system WHERE code = 'COMMON_LOGIN'), true, 'STAFF', 'APPROVED', '1c19dc5a-c91d-5256-b16a-7c89de592266'),
    ('21874d96-aaf7-5c39-8193-2e56ed9ff053', 'check-login-browser-other', '다른 브라우저나 시크릿 창에서도 같은지', (SELECT id FROM it_system WHERE code = 'COMMON_LOGIN'), true, 'STAFF', 'APPROVED', '1c19dc5a-c91d-5256-b16a-7c89de592266'),
    ('4a778d21-85a4-5b41-a29c-3496d4a66b82', 'check-login-device-time', '단말의 날짜·시간 설정이 맞는지', (SELECT id FROM it_system WHERE code = 'COMMON_LOGIN'), true, 'STAFF', 'APPROVED', '1c19dc5a-c91d-5256-b16a-7c89de592266'),
    ('cd063c6a-b98e-5c6f-bb1e-943d256f6067', 'check-login-portal-direct', '사내 포털에서 직접 로그인하면 되는지', (SELECT id FROM it_system WHERE code = 'COMMON_LOGIN'), true, 'STAFF', 'APPROVED', '1c19dc5a-c91d-5256-b16a-7c89de592266'),
    ('abc1ab88-b3e0-5fab-b3ba-f5934df194b8', 'check-login-reported-system', '어느 시스템에서 로그인 화면이 반복되는지', (SELECT id FROM it_system WHERE code = 'COMMON_LOGIN'), true, 'STAFF', 'APPROVED', '1c19dc5a-c91d-5256-b16a-7c89de592266')
ON CONFLICT DO NOTHING;

-- 직원용 질문 문구 (근거는 그 확인 항목을 선언한 문서 버전)
INSERT INTO relation_question_template (id, catalog_key, check_item_id, question_text, employee_disclosable, access_level, review_status, source_document_version_id)
VALUES
    ('57b462e9-bcd0-52c0-ae65-60740036e1c8', 'q-ea-search-period-01', '85554a73-4f69-5279-b63b-14687c05127e', '전자결재 문서함에서 조회 기간을 어떻게 설정하셨나요?', true, 'EMPLOYEE', 'APPROVED', 'ad95ebbc-909d-5b37-a74c-3e75a82371ca'),
    ('eb102aa4-773c-5051-a6b8-06964c4bf63f', 'q-ea-draft-date-01', '523020e0-2d32-555e-94a7-d7a4f588d89b', '찾으시는 문서를 기안한 시기가 대략 언제인가요?', true, 'EMPLOYEE', 'APPROVED', 'ad95ebbc-909d-5b37-a74c-3e75a82371ca'),
    ('2672d390-be45-53d4-86f1-2ed16eb4652a', 'q-ea-filter-condition-01', '1f6fca67-d74b-51bf-b72f-c41252383e6b', '조회할 때 상태나 제목 키워드를 입력하셨나요? 입력하셨다면 무엇인가요?', true, 'EMPLOYEE', 'APPROVED', 'ad95ebbc-909d-5b37-a74c-3e75a82371ca'),
    ('1ab5db97-4482-51cd-899b-b7c1e4639aa8', 'q-ea-submit-state-01', 'd8f0e7d1-fd4a-5c99-8dc7-3d962ea2eb2e', '그 문서를 상신하셨나요, 아니면 임시 저장만 해 두셨나요?', true, 'EMPLOYEE', 'APPROVED', 'ad95ebbc-909d-5b37-a74c-3e75a82371ca'),
    ('90149c0d-efc8-52bb-9f4d-567a65b83020', 'q-ea-participation-01', '9fac2b79-c9bf-54fb-83e2-1fbe20f79249', '그 문서를 직접 기안하셨나요, 아니면 결재선이나 참조로 받으셨나요?', true, 'EMPLOYEE', 'APPROVED', 'ad95ebbc-909d-5b37-a74c-3e75a82371ca'),
    ('38909a5d-5aba-565f-b55b-0af0d33f0fdb', 'q-ea-file-format-01', '6a5bf11a-7f04-5880-94e8-4d50e71a0c5d', '열리지 않는 첨부 파일의 형식이 무엇인가요? (예: pdf, docx, xlsx)', true, 'EMPLOYEE', 'APPROVED', 'cf830c91-fe02-5749-b73b-5d929a95af6a'),
    ('16772847-8e60-573a-ba6b-c78f2b1545cc', 'q-ea-upload-time-01', 'f2577cf9-9d9c-5382-85e0-e1ed1ce9a0fc', '첨부 파일을 올린 지 얼마나 되었나요?', true, 'EMPLOYEE', 'APPROVED', 'cf830c91-fe02-5749-b73b-5d929a95af6a'),
    ('454c9bf6-04ac-517a-b9b2-849a98e79f18', 'q-ea-error-message-01', '45d1dac1-5ffe-5418-a646-6f4de00072ae', '첨부 파일을 열 때 화면에 어떤 문구가 표시되나요?', true, 'EMPLOYEE', 'APPROVED', 'cf830c91-fe02-5749-b73b-5d929a95af6a'),
    ('310c4910-9e72-5ff2-aaf0-a8aa6ffd2acf', 'q-ea-retry-result-01', '3be2bce9-ff98-520d-8223-55a5e4d46690', '잠시 뒤에 다시 열어 보셔도 같은가요?', true, 'EMPLOYEE', 'APPROVED', 'cf830c91-fe02-5749-b73b-5d929a95af6a'),
    ('b7a86a79-20af-5029-aad3-a9aa73b4e4dd', 'q-login-browser-cookie-01', 'a9e27417-a5c2-52eb-a94d-46187438e8e1', '사용 중인 브라우저에서 쿠키를 차단하는 설정을 해 두셨나요?', true, 'EMPLOYEE', 'APPROVED', '1c19dc5a-c91d-5256-b16a-7c89de592266'),
    ('643d0459-811e-573e-bd82-241ce98a022c', 'q-login-browser-other-01', '21874d96-aaf7-5c39-8193-2e56ed9ff053', '다른 브라우저나 시크릿 창에서 로그인하면 어떤가요?', true, 'EMPLOYEE', 'APPROVED', '1c19dc5a-c91d-5256-b16a-7c89de592266'),
    ('9ee41c1b-eb71-5288-a077-d08c6adbd3ea', 'q-login-device-time-01', '4a778d21-85a4-5b41-a29c-3496d4a66b82', '사용 중인 컴퓨터의 날짜와 시간이 맞게 설정되어 있나요?', true, 'EMPLOYEE', 'APPROVED', '1c19dc5a-c91d-5256-b16a-7c89de592266'),
    ('0575e86c-b6f7-5152-a3ff-fcd1e8442b22', 'q-login-portal-direct-01', 'cd063c6a-b98e-5c6f-bb1e-943d256f6067', '사내 포털에서 직접 로그인하면 정상적으로 되나요?', true, 'EMPLOYEE', 'APPROVED', '1c19dc5a-c91d-5256-b16a-7c89de592266'),
    ('23f64dbb-44d2-5709-a02b-feac34d072e3', 'q-login-reported-system-01', 'abc1ab88-b3e0-5fab-b3ba-f5934df194b8', '어느 시스템에서 로그인 화면이 반복되나요?', true, 'EMPLOYEE', 'APPROVED', '1c19dc5a-c91d-5256-b16a-7c89de592266'),
    ('88eba800-6652-5f48-a3e1-5ca2cfa34f05', 'q-login-permission-level-01', 'ba6502bf-9f97-5cd9-ad6e-52d519e32fec', '어떤 권한 종류(읽기, 쓰기, 관리)로 신청하셨나요?', true, 'EMPLOYEE', 'APPROVED', '1bac2f3d-b47a-5cdc-91c1-b9408f02c4c7'),
    ('c382ff6f-85ab-5276-8958-9a39d94c5583', 'q-login-teamlead-confirm-01', '9a1c525d-db15-5000-8d2c-4386cf968cc6', '소속 팀장에게 확인을 요청하셨나요?', true, 'EMPLOYEE', 'APPROVED', '1bac2f3d-b47a-5cdc-91c1-b9408f02c4c7'),
    ('f6dd553d-8c9f-51c1-b08f-9ef2fc2625c5', 'q-file-error-message-01', 'c9bf79b7-84f6-50f4-b95c-23edd5235b4a', '화면에 어떤 문구가 표시되나요?', true, 'EMPLOYEE', 'APPROVED', '6698dff8-74f2-52a5-9223-802608959695'),
    ('225749ad-a319-5ab0-ae4d-bf8320e783db', 'q-file-access-route-01', '562fe407-0681-548f-baf0-ee60f88b4051', '폴더 목록에서 열려고 하셨나요, 링크로 여셨나요?', true, 'EMPLOYEE', 'APPROVED', '6698dff8-74f2-52a5-9223-802608959695'),
    ('dcedb988-b17a-5731-a004-4bccdaba1137', 'q-file-recent-change-01', '0977f720-0864-5bdf-9ab5-a0706c460563', '그 파일이 최근에 삭제되거나 옮겨졌는지 알고 계신가요?', true, 'EMPLOYEE', 'APPROVED', '6698dff8-74f2-52a5-9223-802608959695'),
    ('dfb3314f-1ce3-5e69-8ea7-ac887f174413', 'q-file-link-received-01', 'bdd07d15-85d2-54ae-9a86-51fcf944d03f', '공유 링크를 받은 게 언제쯤인가요?', true, 'EMPLOYEE', 'APPROVED', '6698dff8-74f2-52a5-9223-802608959695')
ON CONFLICT DO NOTHING;

-- 증상 → 가능 원인 (MAY_HAVE_CAUSE)
INSERT INTO relation_symptom_cause (id, symptom_id, cause_id, access_level, review_status, source_document_version_id)
VALUES
    ('7d822abb-f40b-5d8b-97aa-006ed2105881', 'a4498651-19cb-59a5-8eff-8d15abb911ab', '9549d923-6257-517b-927f-4cf3b9393171', 'STAFF', 'APPROVED', '1bac2f3d-b47a-5cdc-91c1-b9408f02c4c7'),
    ('8f108dea-a038-534f-b6ad-2d07e8df9059', '8a8ad9e9-b844-51ad-a8b0-e48bd8d25f2f', '74f16074-0423-53c5-8cc1-2bb550f722fd', 'STAFF', 'APPROVED', 'cf830c91-fe02-5749-b73b-5d929a95af6a'),
    ('92620e5f-77fd-5b32-9577-02dc46e84c92', '8a8ad9e9-b844-51ad-a8b0-e48bd8d25f2f', '5b27c619-b0ca-5d74-bc21-46f209e8164d', 'STAFF', 'APPROVED', 'cf830c91-fe02-5749-b73b-5d929a95af6a'),
    ('0834a410-df8a-5a8f-8198-47df6d842cff', '8a8ad9e9-b844-51ad-a8b0-e48bd8d25f2f', '1322523b-07df-5ffa-a20a-fe95a2d3fc34', 'STAFF', 'APPROVED', 'cf830c91-fe02-5749-b73b-5d929a95af6a'),
    ('8c1cbf78-590a-5fd7-94ed-7871c81c51a8', '8a8ad9e9-b844-51ad-a8b0-e48bd8d25f2f', '86f5ba5b-34d4-5855-992b-86a76c8b9506', 'STAFF', 'APPROVED', 'cf830c91-fe02-5749-b73b-5d929a95af6a'),
    ('b3199f70-bf88-5060-b6f2-c909b4b97cc8', '9700e62c-dfde-52a5-881d-8abbc634dced', '0bb3cca4-aabd-5539-8af7-c5daea416069', 'STAFF', 'APPROVED', 'ad95ebbc-909d-5b37-a74c-3e75a82371ca'),
    ('22852f9d-9f95-5317-9f9b-175acdb56515', '9700e62c-dfde-52a5-881d-8abbc634dced', '61c5d651-b7fe-5c33-b3cf-00ee9998de55', 'STAFF', 'APPROVED', 'ad95ebbc-909d-5b37-a74c-3e75a82371ca'),
    ('5ad6143c-068e-55e8-b119-f401b62d547f', '9700e62c-dfde-52a5-881d-8abbc634dced', 'a6410f1a-3115-5955-9ee6-71554878d994', 'STAFF', 'APPROVED', 'ad95ebbc-909d-5b37-a74c-3e75a82371ca'),
    ('ad4d5451-103e-5ec0-8d7c-ac1b8b23a11a', '9700e62c-dfde-52a5-881d-8abbc634dced', '0eb3ea97-8a36-5ca2-a16a-44074278f9c7', 'STAFF', 'APPROVED', 'ad95ebbc-909d-5b37-a74c-3e75a82371ca'),
    ('670773f9-fb5c-54c7-b956-6297b8dcee4e', 'd1997e85-cd0b-546d-9f22-5e2302f89804', '76262c45-d403-548e-8e07-209380a4c986', 'STAFF', 'APPROVED', '6698dff8-74f2-52a5-9223-802608959695'),
    ('19271f3a-226d-571a-92bf-3568a35bbf99', 'd1997e85-cd0b-546d-9f22-5e2302f89804', 'efa2e023-3e8f-54ed-ad23-0c87d6d2341e', 'STAFF', 'APPROVED', '6698dff8-74f2-52a5-9223-802608959695'),
    ('b1f7cb5f-9e32-50a9-8503-e80803cc22e2', 'd1997e85-cd0b-546d-9f22-5e2302f89804', '2c991309-28fb-5193-9fce-b0d8471d911d', 'STAFF', 'APPROVED', '6698dff8-74f2-52a5-9223-802608959695'),
    ('b18098f2-8328-587c-a833-b4c6c23dcc8f', 'bed926e5-95ef-51ad-a97c-42175e33a5e8', 'e62d9087-3138-5f03-90b6-585cdc18d4e9', 'STAFF', 'APPROVED', '1c19dc5a-c91d-5256-b16a-7c89de592266'),
    ('92277fab-7615-5ba1-b5ab-614fe86ab90b', 'bed926e5-95ef-51ad-a97c-42175e33a5e8', '29707867-49be-5104-9966-e89a5773c6c1', 'STAFF', 'APPROVED', '1c19dc5a-c91d-5256-b16a-7c89de592266'),
    ('d2ad81de-653b-5071-b718-0f555ec9ab7f', 'bed926e5-95ef-51ad-a97c-42175e33a5e8', 'b8a4331d-7139-520f-b9b5-5816b8ff08f8', 'STAFF', 'APPROVED', '1c19dc5a-c91d-5256-b16a-7c89de592266')
ON CONFLICT DO NOTHING;

-- 원인 → 원인을 가르는 확인 항목 (CHECKED_BY)
INSERT INTO relation_cause_check (id, cause_id, check_item_id, access_level, review_status, source_document_version_id)
VALUES
    ('52a6ee4d-559c-5ec6-90b9-becdcba58033', '9549d923-6257-517b-927f-4cf3b9393171', 'ba6502bf-9f97-5cd9-ad6e-52d519e32fec', 'STAFF', 'APPROVED', '1bac2f3d-b47a-5cdc-91c1-b9408f02c4c7'),
    ('246d589b-3f09-511a-b5d5-cfa6db14f5d1', '9549d923-6257-517b-927f-4cf3b9393171', '9a1c525d-db15-5000-8d2c-4386cf968cc6', 'STAFF', 'APPROVED', '1bac2f3d-b47a-5cdc-91c1-b9408f02c4c7'),
    ('b243e27c-9340-5be0-a24d-ed59d307fb4c', '74f16074-0423-53c5-8cc1-2bb550f722fd', '6a5bf11a-7f04-5880-94e8-4d50e71a0c5d', 'STAFF', 'APPROVED', 'cf830c91-fe02-5749-b73b-5d929a95af6a'),
    ('558176fb-f09e-54aa-bedc-0c2e57e39b7e', '5b27c619-b0ca-5d74-bc21-46f209e8164d', 'f2577cf9-9d9c-5382-85e0-e1ed1ce9a0fc', 'STAFF', 'APPROVED', 'cf830c91-fe02-5749-b73b-5d929a95af6a'),
    ('2a8a2e34-574f-58f4-a75e-65c3e1df868c', '1322523b-07df-5ffa-a20a-fe95a2d3fc34', '45d1dac1-5ffe-5418-a646-6f4de00072ae', 'STAFF', 'APPROVED', 'cf830c91-fe02-5749-b73b-5d929a95af6a'),
    ('e887eb61-a7c5-526b-8abd-c953b38b143d', '1322523b-07df-5ffa-a20a-fe95a2d3fc34', '3be2bce9-ff98-520d-8223-55a5e4d46690', 'STAFF', 'APPROVED', 'cf830c91-fe02-5749-b73b-5d929a95af6a'),
    ('e0ea9707-074a-5d31-a768-a0ca26519525', '86f5ba5b-34d4-5855-992b-86a76c8b9506', '45d1dac1-5ffe-5418-a646-6f4de00072ae', 'STAFF', 'APPROVED', 'cf830c91-fe02-5749-b73b-5d929a95af6a'),
    ('6fe0fd39-557b-505d-a69f-4d5d4446a703', '86f5ba5b-34d4-5855-992b-86a76c8b9506', '9fac2b79-c9bf-54fb-83e2-1fbe20f79249', 'STAFF', 'APPROVED', 'cf830c91-fe02-5749-b73b-5d929a95af6a'),
    ('46601da3-246a-5c69-ad89-708aa4a6a6f4', '0bb3cca4-aabd-5539-8af7-c5daea416069', '523020e0-2d32-555e-94a7-d7a4f588d89b', 'STAFF', 'APPROVED', 'ad95ebbc-909d-5b37-a74c-3e75a82371ca'),
    ('1bc20954-6a01-565e-ae32-593f5499a5ff', '0bb3cca4-aabd-5539-8af7-c5daea416069', '85554a73-4f69-5279-b63b-14687c05127e', 'STAFF', 'APPROVED', 'ad95ebbc-909d-5b37-a74c-3e75a82371ca'),
    ('faa04e4b-5c70-5085-a75f-7ee69dc10b9a', '61c5d651-b7fe-5c33-b3cf-00ee9998de55', '9fac2b79-c9bf-54fb-83e2-1fbe20f79249', 'STAFF', 'APPROVED', 'ad95ebbc-909d-5b37-a74c-3e75a82371ca'),
    ('6374c27b-438e-59af-a34d-0b6ef75f18dd', '61c5d651-b7fe-5c33-b3cf-00ee9998de55', 'b318b727-10ab-53ea-ae72-07977daa754d', 'STAFF', 'APPROVED', 'ad95ebbc-909d-5b37-a74c-3e75a82371ca'),
    ('7f15ae88-78a6-532a-80ad-131ea98619c4', 'a6410f1a-3115-5955-9ee6-71554878d994', 'd8f0e7d1-fd4a-5c99-8dc7-3d962ea2eb2e', 'STAFF', 'APPROVED', 'ad95ebbc-909d-5b37-a74c-3e75a82371ca'),
    ('1e28a7d2-469d-5c1d-bb35-decc3eba56e1', '0eb3ea97-8a36-5ca2-a16a-44074278f9c7', '1f6fca67-d74b-51bf-b72f-c41252383e6b', 'STAFF', 'APPROVED', 'ad95ebbc-909d-5b37-a74c-3e75a82371ca'),
    ('4345c4f4-535f-5860-a305-5e467839271d', '76262c45-d403-548e-8e07-209380a4c986', 'c9bf79b7-84f6-50f4-b95c-23edd5235b4a', 'STAFF', 'APPROVED', '6698dff8-74f2-52a5-9223-802608959695'),
    ('bb1ba10c-7672-57a5-b01d-e656f10e915e', '76262c45-d403-548e-8e07-209380a4c986', '562fe407-0681-548f-baf0-ee60f88b4051', 'STAFF', 'APPROVED', '6698dff8-74f2-52a5-9223-802608959695'),
    ('696aaea0-512a-5996-92f7-6fb4ad2013b2', 'efa2e023-3e8f-54ed-ad23-0c87d6d2341e', 'c9bf79b7-84f6-50f4-b95c-23edd5235b4a', 'STAFF', 'APPROVED', '6698dff8-74f2-52a5-9223-802608959695'),
    ('fba6c0b0-b12e-59ec-bc03-e0f2dfa9f2af', 'efa2e023-3e8f-54ed-ad23-0c87d6d2341e', '0977f720-0864-5bdf-9ab5-a0706c460563', 'STAFF', 'APPROVED', '6698dff8-74f2-52a5-9223-802608959695'),
    ('b25aef3c-48e2-511c-950d-42a6b9b83563', '2c991309-28fb-5193-9fce-b0d8471d911d', 'c9bf79b7-84f6-50f4-b95c-23edd5235b4a', 'STAFF', 'APPROVED', '6698dff8-74f2-52a5-9223-802608959695'),
    ('08615b57-14a2-5063-93c0-ef9621f75601', '2c991309-28fb-5193-9fce-b0d8471d911d', 'bdd07d15-85d2-54ae-9a86-51fcf944d03f', 'STAFF', 'APPROVED', '6698dff8-74f2-52a5-9223-802608959695'),
    ('3e564103-6a8f-5434-9157-7f9b2e6223ce', 'e62d9087-3138-5f03-90b6-585cdc18d4e9', 'a9e27417-a5c2-52eb-a94d-46187438e8e1', 'STAFF', 'APPROVED', '1c19dc5a-c91d-5256-b16a-7c89de592266'),
    ('abf2fcc7-e1fd-59b4-b7e1-d158c2c1ea3a', 'e62d9087-3138-5f03-90b6-585cdc18d4e9', '21874d96-aaf7-5c39-8193-2e56ed9ff053', 'STAFF', 'APPROVED', '1c19dc5a-c91d-5256-b16a-7c89de592266'),
    ('4a8cdebe-ffd7-5ffc-8cad-6b61966ff640', '29707867-49be-5104-9966-e89a5773c6c1', '4a778d21-85a4-5b41-a29c-3496d4a66b82', 'STAFF', 'APPROVED', '1c19dc5a-c91d-5256-b16a-7c89de592266'),
    ('6863d8d1-efba-542a-ad95-b064745a6773', 'b8a4331d-7139-520f-b9b5-5816b8ff08f8', 'cd063c6a-b98e-5c6f-bb1e-943d256f6067', 'STAFF', 'APPROVED', '1c19dc5a-c91d-5256-b16a-7c89de592266'),
    ('89130848-e67b-5aef-9197-78508e276587', 'b8a4331d-7139-520f-b9b5-5816b8ff08f8', 'abc1ab88-b3e0-5fab-b3ba-f5934df194b8', 'STAFF', 'APPROVED', '1c19dc5a-c91d-5256-b16a-7c89de592266')
ON CONFLICT DO NOTHING;

-- 승인 사례 → 사례가 다룬 증상 (ADDRESSES). 근거는 그 사례의 CASE 문서 버전
INSERT INTO relation_case_symptom (id, resolved_case_id, symptom_id, access_level, review_status, source_document_version_id)
VALUES
    ('0d106df3-2ab3-5048-b9e0-5891555fae8f', '9f2bd51e-6892-5f75-8ab8-d30b2bf58e21', '9700e62c-dfde-52a5-881d-8abbc634dced', 'STAFF', 'APPROVED', '85d48745-ec4a-5ed0-b9bd-6271a0a069e0'),
    ('5db3557f-796b-5f60-bb86-1e274e957c12', '98e7a55a-b43a-5db0-bf37-97ac2ada89ef', '8a8ad9e9-b844-51ad-a8b0-e48bd8d25f2f', 'STAFF', 'APPROVED', '04646303-cd5f-5313-aaef-23ebe081c102'),
    ('0012ac78-e5a9-5bcf-83e4-4b28fbf6808d', '7c7a3a13-2972-5c14-8ad4-b00cc9b0a6ed', 'bed926e5-95ef-51ad-a97c-42175e33a5e8', 'STAFF', 'APPROVED', '70004886-5c41-552e-8dbd-6c73453e2583'),
    ('45b6df55-ada0-55be-9d2d-cca96c95ad71', 'c79206a4-e6ea-5ee0-8feb-80dbd16884d6', 'a4498651-19cb-59a5-8eff-8d15abb911ab', 'STAFF', 'APPROVED', '072daf95-dc24-5d89-8062-3069157ada02'),
    ('39026503-fbeb-5dbc-8271-9cf8c0c020eb', 'ba4a75a0-0558-5555-8ac8-f5b745fd0759', 'd1997e85-cd0b-546d-9f22-5e2302f89804', 'STAFF', 'APPROVED', 'e9ab4804-50eb-5bfb-91bf-1c3907e70211'),
    ('0827da73-21b6-5922-868a-d6375e0c18dc', '91c8709e-7b9e-54f1-874c-3abdcb1dd926', 'd1997e85-cd0b-546d-9f22-5e2302f89804', 'STAFF', 'APPROVED', '71d20666-0599-5a0f-a645-da4b13cc98f8')
ON CONFLICT DO NOTHING;

-- 승인 사례 → 사례가 쓴 확인 항목 (USES_CHECK)
INSERT INTO relation_case_check (id, resolved_case_id, check_item_id, access_level, review_status, source_document_version_id)
VALUES
    ('3149a0fe-0370-5ba4-9ebe-a9db47c20e1c', '9f2bd51e-6892-5f75-8ab8-d30b2bf58e21', '9fac2b79-c9bf-54fb-83e2-1fbe20f79249', 'STAFF', 'APPROVED', '85d48745-ec4a-5ed0-b9bd-6271a0a069e0'),
    ('7e74fc97-646c-5f54-b6f3-69ba88a7f774', '9f2bd51e-6892-5f75-8ab8-d30b2bf58e21', 'b318b727-10ab-53ea-ae72-07977daa754d', 'STAFF', 'APPROVED', '85d48745-ec4a-5ed0-b9bd-6271a0a069e0'),
    ('2ef6e0f5-dab1-5b4d-b9b4-c9cf4a6fcc2a', '98e7a55a-b43a-5db0-bf37-97ac2ada89ef', 'f2577cf9-9d9c-5382-85e0-e1ed1ce9a0fc', 'STAFF', 'APPROVED', '04646303-cd5f-5313-aaef-23ebe081c102'),
    ('db970a97-597e-53f1-aa26-7e510385cb7d', '7c7a3a13-2972-5c14-8ad4-b00cc9b0a6ed', 'a9e27417-a5c2-52eb-a94d-46187438e8e1', 'STAFF', 'APPROVED', '70004886-5c41-552e-8dbd-6c73453e2583'),
    ('09c1f2ec-9a5a-5485-846d-9f08aa08f8f4', '7c7a3a13-2972-5c14-8ad4-b00cc9b0a6ed', '21874d96-aaf7-5c39-8193-2e56ed9ff053', 'STAFF', 'APPROVED', '70004886-5c41-552e-8dbd-6c73453e2583'),
    ('04e73214-a5d5-53d9-87c7-fd5a1b4016ce', 'c79206a4-e6ea-5ee0-8feb-80dbd16884d6', 'ba6502bf-9f97-5cd9-ad6e-52d519e32fec', 'STAFF', 'APPROVED', '072daf95-dc24-5d89-8062-3069157ada02'),
    ('c4faca3b-087e-5b27-b823-06c9921d8bd6', 'c79206a4-e6ea-5ee0-8feb-80dbd16884d6', '9a1c525d-db15-5000-8d2c-4386cf968cc6', 'STAFF', 'APPROVED', '072daf95-dc24-5d89-8062-3069157ada02'),
    ('84d86528-0844-5476-9ae8-2d76b7b89230', 'ba4a75a0-0558-5555-8ac8-f5b745fd0759', 'c9bf79b7-84f6-50f4-b95c-23edd5235b4a', 'STAFF', 'APPROVED', 'e9ab4804-50eb-5bfb-91bf-1c3907e70211'),
    ('753d4dcb-ff2c-58b7-a4cc-877f5542e4a6', 'ba4a75a0-0558-5555-8ac8-f5b745fd0759', '562fe407-0681-548f-baf0-ee60f88b4051', 'STAFF', 'APPROVED', 'e9ab4804-50eb-5bfb-91bf-1c3907e70211'),
    ('2dc316d1-223b-5bc4-8c36-65d27357ecc8', '91c8709e-7b9e-54f1-874c-3abdcb1dd926', 'c9bf79b7-84f6-50f4-b95c-23edd5235b4a', 'STAFF', 'APPROVED', '71d20666-0599-5a0f-a645-da4b13cc98f8'),
    ('2663c00b-4a4d-52e1-ae31-eafab53d2c6d', '91c8709e-7b9e-54f1-874c-3abdcb1dd926', 'bdd07d15-85d2-54ae-9a86-51fcf944d03f', 'STAFF', 'APPROVED', '71d20666-0599-5a0f-a645-da4b13cc98f8')
ON CONFLICT DO NOTHING;
