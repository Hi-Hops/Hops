-- ⚠️ 로컬 개발용 가상 데이터 (실제 문의·실제 처리 기록이 아님)
-- ⚠️ 자동 생성 파일이다. 직접 고치지 않는다. 원본: docs/knowledge/cases/*.yml (사례 카드)
--    다시 만들기: uv run scripts/knowledge/build_case_seed.py
--    어긋남 검사:  uv run scripts/knowledge/build_case_seed.py --check
-- 값의 정본은 docs/knowledge/VIRTUAL_COMPANY.md 7절(시드 해결 사례)이다.
-- db 프로필에서만 실행된다. 테스트 DB·운영에는 들어가지 않는다.
--
-- R__ 파일 실행 순서: department_member → it_system → knowledge → knowledge_cases (이름 순).
--
-- 사례 한 건은 과거에 해결됐다고 가정한 처리 이력 체인이다. resolved_case.source_work_log_id가 필수이고
-- 사례 조회 API가 큐레이터 실행과 해결 확인 이벤트를 읽기 때문에 아래 행을 모두 만든다.
--   문의 → 메시지 4 → 상담 실행 2 → 문제 → 표준 요청 → 티켓 → 배정 → 처리 기록 2 → 티켓 이벤트 2
--   → 큐레이터 실행 → 사례 → 지식 문서(CASE) → 문서 버전
-- 번호는 SEED- 접두사로 실제 번호(Q-00000001, T-0000000000000001)와 구분한다.
-- 해결 확인은 합성 설정이라 confirmationOrigin = SYNTHETIC_PRESET 으로 표시한다.
--
-- 사례는 승인(APPROVED)이고 지식 문서(CASE) 스냅샷은 실제 승인 거래(CaseApprovalWriter)와 같은 형식이다: 본문, 승인 스냅샷의
-- digest, 저장소별 반영 요청(indexRequests)을 보존한다. 반영 요청은 저장만 했고 AI에 전달하지 않았다(승인 직후 전달 전 상태).
-- 그래서 vector_status·graph_status는 PENDING이다. Vector·Neo4j에 색인하지 않았으므로 SUCCEEDED로 적는 것은 사실이 아니다.
-- 현재 공개 판정은 둘 다 SUCCEEDED를 요구하므로 아직 검색에는 나오지 않는다(docs/knowledge/VIRTUAL_COMPANY.md 10절 열린 문제 1).
--
-- 모든 INSERT는 ON CONFLICT DO NOTHING이라 이미 있는 행은 바꾸지 않는다. 카드를 고치면 팀원은 DB를 새로 만들어 적재한다
-- (backend/spring/hops/README.md "처음 상태로 되돌리기").

-- ===== case-ea-doc-no-permission (SEED-Q-001, SEED-T-001, SEED-C-001) =====
INSERT INTO inquiry (id, inquiry_number, requester_member_id, title, inquiry_status, version, current_run_id, result_revision, created_at, updated_at)
VALUES ('774d5adf-3496-596d-8865-e5ae74ee36c4', 'SEED-Q-001', (SELECT id FROM member WHERE login_id = 'requester1'), '전자결재 문서함에 다른 팀이 올린 문서가 보이지 않음',
        'RESOLVED', 2, NULL, 3, '2026-09-14T10:02:00+09:00'::timestamptz, '2026-09-15T10:02:00+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO message (id, inquiry_id, sequence_no, sender_type, content, revision, payload, message_status, created_at, updated_at)
VALUES ('4118bce8-2f2c-5496-ab41-8902821a92f2', '774d5adf-3496-596d-8865-e5ae74ee36c4', 1, 'USER', '지난주에 옆 팀에서 올린 결재 문서를 보려는데 전자결재 문서함에 안 나와요.',
        1, '{"answerRunId": null, "contractVersion": "hops-system-1", "guide": null, "pendingTarget": false, "problemLinks": [{"problemId": "2e7b9f75-1d8f-54bc-84e4-0853592749f1", "revision": 1, "spans": [{"end": 42, "start": 0}]}], "questionBundles": [], "reportedSystemId": "3320ce84-c47c-592c-b599-6e968e12b0a3", "requestId": "0d88affd-3c36-5801-85b4-28960607a0e9", "revisionRequestIds": {}, "runRequestIds": {}}'::jsonb, 'COMPLETED', '2026-09-14T10:02:00+09:00'::timestamptz, '2026-09-14T10:02:00+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO agent_run (id, inquiry_id, trigger_message_id, task, route, run_status, input_version, input_snapshot, model_version, prompt_version, schema_version, validation_result, started_at, completed_at, created_at, updated_at)
VALUES ('8f04369c-d824-5868-abe2-e1e0edbe37e3', '774d5adf-3496-596d-8865-e5ae74ee36c4', '4118bce8-2f2c-5496-ab41-8902821a92f2', 'INQUIRY_ANALYSIS', 'SYMPTOM',
        'SUCCEEDED', 1, '{"answerMessageId": "f1821f0a-25b3-5e80-b247-8c4142aae630", "note": "합성 시드. 실제 AI 실행이 아니다.", "seed": true}'::jsonb, 'seed-synthetic', 'seed', 'hops-system-1',
        '{"applied": true, "resources": [], "seed": true}'::jsonb, '2026-09-14T10:02:02+09:00'::timestamptz, '2026-09-14T10:02:20+09:00'::timestamptz, '2026-09-14T10:02:02+09:00'::timestamptz, '2026-09-14T10:02:20+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO message (id, inquiry_id, sequence_no, sender_type, content, revision, payload, message_status, created_at, updated_at)
VALUES ('f1821f0a-25b3-5e80-b247-8c4142aae630', '774d5adf-3496-596d-8865-e5ae74ee36c4', 2, 'AI', '문서가 보이지 않아 불편하시겠어요. 두 가지만 여쭤볼게요. 이 문서를 직접 기안하셨나요, 아니면 결재선이나 참조로 받으셨나요? 그리고 문서함에서 조회 기간은 어떻게 설정하셨나요?',
        1, '{"answerRunId": "8f04369c-d824-5868-abe2-e1e0edbe37e3", "contractVersion": "hops-system-1", "guide": null, "pendingTarget": false, "problemLinks": [], "questionBundles": [], "requestId": null}'::jsonb, 'COMPLETED', '2026-09-14T10:02:20+09:00'::timestamptz, '2026-09-14T10:02:20+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO message (id, inquiry_id, sequence_no, sender_type, content, revision, payload, message_status, created_at, updated_at)
VALUES ('b6e7dfac-0bbb-503c-9925-87f45ed738d0', '774d5adf-3496-596d-8865-e5ae74ee36c4', 3, 'USER', '제가 기안한 문서는 아니고 마케팅팀 다른 분이 올린 문서예요. 결재선에 제가 있는지는 모르겠어요. 기간은 기본값 그대로 최근 3개월이에요.',
        1, '{"answerRunId": null, "contractVersion": "hops-system-1", "guide": null, "pendingTarget": false, "problemLinks": [{"problemId": "2e7b9f75-1d8f-54bc-84e4-0853592749f1", "revision": 1, "spans": [{"end": 77, "start": 0}]}], "questionBundles": [], "reportedSystemId": null, "requestId": "048d68ec-1807-509a-86dd-93b217a21907", "revisionRequestIds": {}, "runRequestIds": {}}'::jsonb, 'COMPLETED', '2026-09-14T10:05:00+09:00'::timestamptz, '2026-09-14T10:05:00+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO agent_run (id, inquiry_id, trigger_message_id, task, route, run_status, input_version, input_snapshot, model_version, prompt_version, schema_version, validation_result, started_at, completed_at, created_at, updated_at)
VALUES ('10103008-e695-5a78-aaff-41717ffe81b7', '774d5adf-3496-596d-8865-e5ae74ee36c4', 'b6e7dfac-0bbb-503c-9925-87f45ed738d0', 'INQUIRY_ANALYSIS', 'SYMPTOM',
        'SUCCEEDED', 2, '{"answerMessageId": "7388a2b3-d697-5288-93bf-74024adef89d", "note": "합성 시드. 실제 AI 실행이 아니다.", "seed": true}'::jsonb, 'seed-synthetic', 'seed', 'hops-system-1',
        '{"applied": true, "resources": [], "seed": true}'::jsonb, '2026-09-14T10:05:02+09:00'::timestamptz, '2026-09-14T10:05:40+09:00'::timestamptz, '2026-09-14T10:05:02+09:00'::timestamptz, '2026-09-14T10:05:40+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO message (id, inquiry_id, sequence_no, sender_type, content, revision, payload, message_status, created_at, updated_at)
VALUES ('7388a2b3-d697-5288-93bf-74024adef89d', '774d5adf-3496-596d-8865-e5ae74ee36c4', 4, 'AI', '알려 주셔서 감사합니다. 확인된 내용을 정리해 전자결재 담당자에게 접수했습니다. 담당자가 확인한 뒤 결과를 이 대화로 알려 드리겠습니다.',
        1, '{"answerRunId": "10103008-e695-5a78-aaff-41717ffe81b7", "contractVersion": "hops-system-1", "guide": null, "pendingTarget": false, "problemLinks": [], "questionBundles": [], "requestId": null}'::jsonb, 'COMPLETED', '2026-09-14T10:05:40+09:00'::timestamptz, '2026-09-14T10:05:40+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO inquiry_problem (id, inquiry_id, problem_no, title, detail, problem_status, clarify_count, creation_outcome, creation_input_version, agent_run_id, created_at, updated_at)
VALUES ('2e7b9f75-1d8f-54bc-84e4-0853592749f1', '774d5adf-3496-596d-8865-e5ae74ee36c4', 1, '전자결재 문서함에서 다른 팀이 올린 문서가 보이지 않음', '다른 사람이 기안한 결재 문서가 문의자의 문서함 전체 문서 목록에 나타나지 않는다. 조회 기간은 기본값(최근 3개월)이다.',
        'TICKET_CREATED', 1, 'STORED', 2, '10103008-e695-5a78-aaff-41717ffe81b7', '2026-09-14T10:05:40+09:00'::timestamptz, '2026-09-14T10:05:40+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO standard_request (id, inquiry_problem_id, inquiry_version, agent_run_id, intent, work_type, reported_system_id, reported_feature_id, responsible_system_id, system_candidates, summary, confirmed_facts, assumptions, missing_items, type_detail, related_knowledge, created_at)
VALUES ('937f3f90-1b87-5147-9dea-6c75286161f5', '2e7b9f75-1d8f-54bc-84e4-0853592749f1', 2, '10103008-e695-5a78-aaff-41717ffe81b7', 'OBSERVED_SYMPTOM', 'INVESTIGATION',
        (SELECT id FROM it_system WHERE code = 'ELECTRONIC_APPROVAL'), (SELECT f.id FROM system_feature f JOIN it_system s ON s.id = f.it_system_id WHERE s.code = 'ELECTRONIC_APPROVAL' AND f.code = 'DOCUMENT_VIEW'), (SELECT id FROM it_system WHERE code = 'ELECTRONIC_APPROVAL'), '[{"evidenceIds": ["4118bce8-2f2c-5496-ab41-8902821a92f2"], "systemId": "3320ce84-c47c-592c-b599-6e968e12b0a3"}]'::jsonb, '다른 사람이 기안한 결재 문서가 문의자의 문서함 전체 문서 목록에 나타나지 않는다. 조회 기간은 기본값(최근 3개월)이다.',
        '[{"knowledgeEvidenceIds": [], "sourceMessageIds": ["b6e7dfac-0bbb-503c-9925-87f45ed738d0"], "text": "문의자가 기안한 문서가 아니다."}, {"knowledgeEvidenceIds": [], "sourceMessageIds": ["b6e7dfac-0bbb-503c-9925-87f45ed738d0"], "text": "조회 기간은 기본값인 최근 3개월이다."}]'::jsonb, '[]'::jsonb, '["문의자가 문서의 결재선·참조에 포함되어 있는지"]'::jsonb, '{"acceptanceCriteria": [], "actualBehavior": "문서가 목록에 나타나지 않음", "expectedBehavior": "문서가 목록에 나타남", "occurredAt": null, "taskOrScreen": "전자결재 문서함 > 전체 문서에서 다른 팀이 올린 문서를 찾으려 함"}'::jsonb, '[]'::jsonb, '2026-09-14T10:05:40+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO ticket (id, ticket_number, inquiry_id, inquiry_problem_id, standard_request_id, title, detail, work_type, ticket_status, responsible_system_id, assignee_member_id, confirm_requested_at, resolved_at, version, created_at, updated_at)
VALUES ('0a8d4516-dbe5-5a41-b64d-f49d17d00c08', 'SEED-T-001', '774d5adf-3496-596d-8865-e5ae74ee36c4', '2e7b9f75-1d8f-54bc-84e4-0853592749f1', '937f3f90-1b87-5147-9dea-6c75286161f5',
        '전자결재 문서함에서 다른 팀이 올린 문서가 보이지 않음', '문의자가 기안자가 아닌 결재 문서를 문서함 전체 문서에서 찾지 못한다. 조회 기간은 최근 3개월이다. 결재선·참조 포함 여부는 문의자가 모른다.', 'INVESTIGATION', 'RESOLVED', (SELECT id FROM it_system WHERE code = 'ELECTRONIC_APPROVAL'), (SELECT id FROM member WHERE login_id = 'ea_owner'),
        '2026-09-14T12:02:00+09:00'::timestamptz, '2026-09-14T13:02:00+09:00'::timestamptz, 3, '2026-09-14T10:05:40+09:00'::timestamptz, '2026-09-14T13:02:00+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO ticket_assignment (id, ticket_id, it_system_id, assignee_member_id, source, reason, evidence, assigned_at, created_at, updated_at)
VALUES ('38e58b12-c849-5572-9b07-9d0bc992453c', '0a8d4516-dbe5-5a41-b64d-f49d17d00c08', (SELECT id FROM it_system WHERE code = 'ELECTRONIC_APPROVAL'), (SELECT id FROM member WHERE login_id = 'ea_owner'), 'AUTO',
        '책임 시스템의 현재 주담당자에게 자동 배정(합성 시드)', '{"messageIds": ["4118bce8-2f2c-5496-ab41-8902821a92f2", "b6e7dfac-0bbb-503c-9925-87f45ed738d0"], "note": null}'::jsonb,
        '2026-09-14T10:05:40+09:00'::timestamptz, '2026-09-14T10:05:40+09:00'::timestamptz, '2026-09-14T10:05:40+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO ticket_work_log (id, ticket_id, author_member_id, log_type, action_detail, confirmed_cause, verification_result, applied_version, created_at)
VALUES ('94cebf5b-0c62-5187-85af-a0cf2b3dea4f', '0a8d4516-dbe5-5a41-b64d-f49d17d00c08', (SELECT id FROM member WHERE login_id = 'ea_owner'), 'PROGRESS', '문서의 기안일과 기안자·결재선·참조 기록을 확인했다. 기안일은 문의자의 조회 기간 안이다.',
        NULL, NULL, NULL, '2026-09-14T11:02:00+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO ticket_work_log (id, ticket_id, author_member_id, log_type, action_detail, confirmed_cause, verification_result, applied_version, created_at)
VALUES ('b14c6c7a-c7b9-5755-b700-cf0955bbeccd', '0a8d4516-dbe5-5a41-b64d-f49d17d00c08', (SELECT id FROM member WHERE login_id = 'ea_owner'), 'RESOLVED', '기안자에게 문의자를 열람자로 추가해 달라고 요청했고, 기안자가 문서 상세의 [참조 추가]로 문의자를 참조에 추가했다.',
        '문의자가 문서의 기안자·결재선·참조에 없어 열람 권한이 없었다. 기안일은 조회 기간 안이었다.', '참조 추가 뒤 문의자 계정으로 문서함 > 전체 문서를 조회하자 문서가 목록에 나타났다.', NULL, '2026-09-14T12:02:00+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO ticket_event (id, ticket_id, event_type, actor_type, actor_member_id, before_data, after_data, created_at)
VALUES ('a04d414e-da8e-54f1-8c21-9ba79d56ad87', '0a8d4516-dbe5-5a41-b64d-f49d17d00c08', 'STATUS_CHANGED', 'MEMBER', (SELECT id FROM member WHERE login_id = 'ea_owner'),
        '{"state": "IN_PROGRESS"}'::jsonb, '{"resolutionRequest": {"confirmationOrigin": "SYNTHETIC_PRESET", "createdAt": "2026-09-14T03:02:00Z", "id": "bf13203d-a57d-5812-8d78-174e2b3be123", "inquiryId": "774d5adf-3496-596d-8865-e5ae74ee36c4", "problemId": "2e7b9f75-1d8f-54bc-84e4-0853592749f1", "requestRevision": 1, "respondedAt": null, "resultSummary": "기안자에게 문의자를 열람자로 추가해 달라고 요청했고, 기안자가 문서 상세의 [참조 추가]로 문의자를 참조에 추가했다.", "source": "WORK_RECORD", "sourceId": "b14c6c7a-c7b9-5755-b700-cf0955bbeccd", "sourceVersion": 1, "state": "PENDING", "ticketId": "0a8d4516-dbe5-5a41-b64d-f49d17d00c08"}, "state": "WAITING_CONFIRM"}'::jsonb, '2026-09-14T12:02:00+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO ticket_event (id, ticket_id, event_type, actor_type, actor_member_id, before_data, after_data, created_at)
VALUES ('abb8a37e-176b-5687-9593-e7f5eb2d5233', '0a8d4516-dbe5-5a41-b64d-f49d17d00c08', 'USER_CONFIRMED', 'MEMBER', (SELECT id FROM member WHERE login_id = 'requester1'),
        '{"resolutionRequest": {"confirmationOrigin": "SYNTHETIC_PRESET", "createdAt": "2026-09-14T03:02:00Z", "id": "bf13203d-a57d-5812-8d78-174e2b3be123", "inquiryId": "774d5adf-3496-596d-8865-e5ae74ee36c4", "problemId": "2e7b9f75-1d8f-54bc-84e4-0853592749f1", "requestRevision": 1, "respondedAt": null, "resultSummary": "기안자에게 문의자를 열람자로 추가해 달라고 요청했고, 기안자가 문서 상세의 [참조 추가]로 문의자를 참조에 추가했다.", "source": "WORK_RECORD", "sourceId": "b14c6c7a-c7b9-5755-b700-cf0955bbeccd", "sourceVersion": 1, "state": "PENDING", "ticketId": "0a8d4516-dbe5-5a41-b64d-f49d17d00c08"}}'::jsonb, '{"resolutionRequest": {"confirmationOrigin": "SYNTHETIC_PRESET", "createdAt": "2026-09-14T03:02:00Z", "id": "bf13203d-a57d-5812-8d78-174e2b3be123", "inquiryId": "774d5adf-3496-596d-8865-e5ae74ee36c4", "problemId": "2e7b9f75-1d8f-54bc-84e4-0853592749f1", "requestRevision": 2, "respondedAt": "2026-09-14T04:02:00Z", "resultSummary": "기안자에게 문의자를 열람자로 추가해 달라고 요청했고, 기안자가 문서 상세의 [참조 추가]로 문의자를 참조에 추가했다.", "source": "WORK_RECORD", "sourceId": "b14c6c7a-c7b9-5755-b700-cf0955bbeccd", "sourceVersion": 1, "state": "RESOLVED", "ticketId": "0a8d4516-dbe5-5a41-b64d-f49d17d00c08"}}'::jsonb, '2026-09-14T13:02:00+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO agent_run (id, inquiry_id, ticket_id, trigger_work_log_id, task, run_status, input_version, input_snapshot, model_version, prompt_version, schema_version, result, validation_result, started_at, completed_at, created_at, updated_at)
VALUES ('5d8580e9-a3c0-5372-a9b2-4d406b34df39', '774d5adf-3496-596d-8865-e5ae74ee36c4', '0a8d4516-dbe5-5a41-b64d-f49d17d00c08', 'b14c6c7a-c7b9-5755-b700-cf0955bbeccd', 'CURATION',
        'SUCCEEDED', 2, '{"note": "합성 시드. 실제 AI 실행이 아니다.", "seed": true}'::jsonb, 'seed-synthetic', 'seed', 'hops-system-1',
        '{"caseProposal": {"reusable": true}, "seed": true}'::jsonb, '{"applied": true, "caseGenerationState": "SUCCEEDED", "resources": [{"id": "9f2bd51e-6892-5f75-8ab8-d30b2bf58e21", "type": "CASE"}], "seed": true}'::jsonb, '2026-09-14T12:02:05+09:00'::timestamptz, '2026-09-14T12:03:00+09:00'::timestamptz, '2026-09-14T12:02:05+09:00'::timestamptz, '2026-09-14T12:03:00+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO resolved_case (id, case_number, version_no, source_work_log_id, it_system_id, system_feature_id, case_type, title, problem_statement, preconditions, confirmed_facts, confirmed_cause, action_detail, verification_result, applicability, applied_version, access_level, origin_type, case_status, curator_run_id, relation_candidates, approved_by_member_id, approved_at, vector_status, graph_status, created_at, updated_at)
VALUES ('9f2bd51e-6892-5f75-8ab8-d30b2bf58e21', 'SEED-C-001', 1, 'b14c6c7a-c7b9-5755-b700-cf0955bbeccd', (SELECT id FROM it_system WHERE code = 'ELECTRONIC_APPROVAL'), (SELECT f.id FROM system_feature f JOIN it_system s ON s.id = f.it_system_id WHERE s.code = 'ELECTRONIC_APPROVAL' AND f.code = 'DOCUMENT_VIEW'), 'RESOLUTION',
        '전자결재 문서가 문서함에 안 보임 - 기안자·결재선·참조에 없어 열람 권한이 없음', '다른 사람이 기안한 결재 문서가 문의자의 문서함 > 전체 문서 목록에 나타나지 않는다. 조회 기간은 기본값이다.', '문서의 기안일이 조회 기간 안이다.', '{"facts": [{"knowledgeEvidenceIds": [], "sourceMessageIds": ["b6e7dfac-0bbb-503c-9925-87f45ed738d0"], "text": "문의자가 기안한 문서가 아니다."}, {"knowledgeEvidenceIds": [], "sourceMessageIds": ["b6e7dfac-0bbb-503c-9925-87f45ed738d0"], "text": "조회 기간은 기본값인 최근 3개월이다."}], "knowledge": [], "limitations": ["열람자 추가는 문서의 기안자가 한다.", "기안일이 조회 기간 밖인 경우는 다른 원인이다."], "sourceMessageIds": ["4118bce8-2f2c-5496-ab41-8902821a92f2", "b6e7dfac-0bbb-503c-9925-87f45ed738d0"]}'::jsonb,
        '문의자가 문서의 기안자·결재선·참조에 없어 열람 권한이 없었다. 기안일은 조회 기간 안이었다.', '기안자에게 문의자를 열람자로 추가해 달라고 요청했고, 기안자가 문서 상세의 [참조 추가]로 문의자를 참조에 추가했다.', '참조 추가 뒤 문의자 계정으로 문서함 > 전체 문서를 조회하자 문서가 목록에 나타났다.', '기안일이 조회 기간 안인데 문서가 목록에 없고, 문의자가 문서의 기안자도 결재선·참조도 아닌 경우에 한한다. 기안일이 조회 기간 밖이거나, 임시 저장 문서이거나, 상태·제목 키워드 조건 때문에 안 보이는 경우에는 적용하지 않는다.',
        NULL, 'STAFF', 'SYNTHETIC', 'APPROVED', '5d8580e9-a3c0-5372-a9b2-4d406b34df39', '[{"evidenceIds": ["4118bce8-2f2c-5496-ab41-8902821a92f2", "b6e7dfac-0bbb-503c-9925-87f45ed738d0"], "relation": "APPLIES_TO", "targetId": "3320ce84-c47c-592c-b599-6e968e12b0a3"}]'::jsonb, (SELECT id FROM member WHERE login_id = 'ea_owner'),
        '2026-09-15T10:02:00+09:00'::timestamptz, 'PENDING', 'PENDING', '2026-09-14T12:03:00+09:00'::timestamptz, '2026-09-15T10:02:00+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO knowledge_document (id, title, document_type, it_system_id, access_level, source_data, data_split, review_status, is_synthetic, created_at, updated_at)
VALUES ('9f2bd51e-6892-5f75-8ab8-d30b2bf58e21', '전자결재 문서가 문서함에 안 보임 - 기안자·결재선·참조에 없어 열람 권한이 없음', 'CASE', (SELECT id FROM it_system WHERE code = 'ELECTRONIC_APPROVAL'), 'STAFF', '{"cardPath": "docs/knowledge/cases/case-ea-doc-no-permission.yml", "caseId": "9f2bd51e-6892-5f75-8ab8-d30b2bf58e21", "caseNumber": "SEED-C-001", "origin": "SYNTHETIC"}'::jsonb, 'RAG', 'APPROVED', true,
        '2026-09-15T10:02:00+09:00'::timestamptz, '2026-09-15T10:02:00+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO knowledge_document_version (id, knowledge_document_id, version_no, content, content_digest, source_metadata, review_status, created_at)
VALUES ('85d48745-ec4a-5ed0-b9bd-6271a0a069e0', '9f2bd51e-6892-5f75-8ab8-d30b2bf58e21', 1,
        $kb$전자결재 문서가 문서함에 안 보임 - 기안자·결재선·참조에 없어 열람 권한이 없음

다른 사람이 기안한 결재 문서가 문의자의 문서함 > 전체 문서 목록에 나타나지 않는다. 조회 기간은 기본값이다.

기안자에게 문의자를 열람자로 추가해 달라고 요청했고, 기안자가 문서 상세의 [참조 추가]로 문의자를 참조에 추가했다.

참조 추가 뒤 문의자 계정으로 문서함 > 전체 문서를 조회하자 문서가 목록에 나타났다.

기안일이 조회 기간 안인데 문서가 목록에 없고, 문의자가 문서의 기안자도 결재선·참조도 아닌 경우에 한한다. 기안일이 조회 기간 밖이거나, 임시 저장 문서이거나, 상태·제목 키워드 조건 때문에 안 보이는 경우에는 적용하지 않는다.

열람자 추가는 문서의 기안자가 한다.
기안일이 조회 기간 밖인 경우는 다른 원인이다.$kb$,
        '16e038d5ac117c422e3cfc3894dd8a19c8cd4a1a9cabd661f6a9bc2bc17c0596', '{"caseId": "9f2bd51e-6892-5f75-8ab8-d30b2bf58e21", "caseVersion": 1, "indexRequests": {"NEO4J": {"approvedCase": {"accessScope": "SYSTEM_STAFF", "action": "기안자에게 문의자를 열람자로 추가해 달라고 요청했고, 기안자가 문서 상세의 [참조 추가]로 문의자를 참조에 추가했다.", "applicability": "기안일이 조회 기간 안인데 문서가 목록에 없고, 문의자가 문서의 기안자도 결재선·참조도 아닌 경우에 한한다. 기안일이 조회 기간 밖이거나, 임시 저장 문서이거나, 상태·제목 키워드 조건 때문에 안 보이는 경우에는 적용하지 않는다.", "appliesToSystemVersion": null, "approvedAt": "2026-09-15T01:02:00Z", "approvedBy": "a0000000-0000-7000-8000-000000000011", "caseType": "RESOLUTION", "confirmationOrigin": "SYNTHETIC_PRESET", "confirmedCause": "문의자가 문서의 기안자·결재선·참조에 없어 열람 권한이 없었다. 기안일은 조회 기간 안이었다.", "entityLinks": [{"evidenceIds": ["4118bce8-2f2c-5496-ab41-8902821a92f2", "b6e7dfac-0bbb-503c-9925-87f45ed738d0"], "relation": "APPLIES_TO", "targetId": "3320ce84-c47c-592c-b599-6e968e12b0a3"}], "limitations": ["열람자 추가는 문서의 기안자가 한다.", "기안일이 조회 기간 밖인 경우는 다른 원인이다."], "origin": "SYNTHETIC", "problemStatement": "다른 사람이 기안한 결재 문서가 문의자의 문서함 > 전체 문서 목록에 나타나지 않는다. 조회 기간은 기본값이다.", "resolutionRequestId": "bf13203d-a57d-5812-8d78-174e2b3be123", "sourceMessageIds": ["4118bce8-2f2c-5496-ab41-8902821a92f2", "b6e7dfac-0bbb-503c-9925-87f45ed738d0"], "systemId": "3320ce84-c47c-592c-b599-6e968e12b0a3", "title": "전자결재 문서가 문서함에 안 보임 - 기안자·결재선·참조에 없어 열람 권한이 없음", "verification": "참조 추가 뒤 문의자 계정으로 문서함 > 전체 문서를 조회하자 문서가 목록에 나타났다.", "workRecordId": "b14c6c7a-c7b9-5755-b700-cf0955bbeccd"}, "attempt": 1, "caseId": "9f2bd51e-6892-5f75-8ab8-d30b2bf58e21", "caseVersion": 1, "contentDigest": "db81c09d2a290c5a9c7c1561ed637cd7297423094902165ea3ed30b6c1f9daf5", "contractVersion": "hops-system-1", "jobId": "6078b799-3751-559d-9606-7283451007b5", "store": "NEO4J"}, "VECTOR": {"approvedCase": {"accessScope": "SYSTEM_STAFF", "action": "기안자에게 문의자를 열람자로 추가해 달라고 요청했고, 기안자가 문서 상세의 [참조 추가]로 문의자를 참조에 추가했다.", "applicability": "기안일이 조회 기간 안인데 문서가 목록에 없고, 문의자가 문서의 기안자도 결재선·참조도 아닌 경우에 한한다. 기안일이 조회 기간 밖이거나, 임시 저장 문서이거나, 상태·제목 키워드 조건 때문에 안 보이는 경우에는 적용하지 않는다.", "appliesToSystemVersion": null, "approvedAt": "2026-09-15T01:02:00Z", "approvedBy": "a0000000-0000-7000-8000-000000000011", "caseType": "RESOLUTION", "confirmationOrigin": "SYNTHETIC_PRESET", "confirmedCause": "문의자가 문서의 기안자·결재선·참조에 없어 열람 권한이 없었다. 기안일은 조회 기간 안이었다.", "entityLinks": [{"evidenceIds": ["4118bce8-2f2c-5496-ab41-8902821a92f2", "b6e7dfac-0bbb-503c-9925-87f45ed738d0"], "relation": "APPLIES_TO", "targetId": "3320ce84-c47c-592c-b599-6e968e12b0a3"}], "limitations": ["열람자 추가는 문서의 기안자가 한다.", "기안일이 조회 기간 밖인 경우는 다른 원인이다."], "origin": "SYNTHETIC", "problemStatement": "다른 사람이 기안한 결재 문서가 문의자의 문서함 > 전체 문서 목록에 나타나지 않는다. 조회 기간은 기본값이다.", "resolutionRequestId": "bf13203d-a57d-5812-8d78-174e2b3be123", "sourceMessageIds": ["4118bce8-2f2c-5496-ab41-8902821a92f2", "b6e7dfac-0bbb-503c-9925-87f45ed738d0"], "systemId": "3320ce84-c47c-592c-b599-6e968e12b0a3", "title": "전자결재 문서가 문서함에 안 보임 - 기안자·결재선·참조에 없어 열람 권한이 없음", "verification": "참조 추가 뒤 문의자 계정으로 문서함 > 전체 문서를 조회하자 문서가 목록에 나타났다.", "workRecordId": "b14c6c7a-c7b9-5755-b700-cf0955bbeccd"}, "attempt": 1, "caseId": "9f2bd51e-6892-5f75-8ab8-d30b2bf58e21", "caseVersion": 1, "contentDigest": "8e7049dcf6a712da401f3ce3b75701d6eafd9532e026ff95ba8def967ca0bae0", "contractVersion": "hops-system-1", "jobId": "5cfd9ed4-22fe-5cf2-8eeb-672f84d30a2f", "store": "VECTOR"}}}'::jsonb, 'APPROVED', '2026-09-15T10:02:00+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
UPDATE inquiry SET current_run_id = '10103008-e695-5a78-aaff-41717ffe81b7' WHERE id = '774d5adf-3496-596d-8865-e5ae74ee36c4' AND current_run_id IS NULL;

-- ===== case-ea-attach-scanning (SEED-Q-002, SEED-T-002, SEED-C-002) =====
INSERT INTO inquiry (id, inquiry_number, requester_member_id, title, inquiry_status, version, current_run_id, result_revision, created_at, updated_at)
VALUES ('3da49514-cfd5-50f1-8068-655b6cf3b3b7', 'SEED-Q-002', (SELECT id FROM member WHERE login_id = 'requester2'), '방금 올린 전자결재 첨부 파일이 열리지 않음',
        'RESOLVED', 2, NULL, 3, '2026-09-16T14:20:00+09:00'::timestamptz, '2026-09-17T14:20:00+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO message (id, inquiry_id, sequence_no, sender_type, content, revision, payload, message_status, created_at, updated_at)
VALUES ('65df9077-4823-54b1-ac85-2ee11e38af56', '3da49514-cfd5-50f1-8068-655b6cf3b3b7', 1, 'USER', '방금 결재 문서에 파일을 올렸는데 첨부 파일이 안 열려요.',
        1, '{"answerRunId": null, "contractVersion": "hops-system-1", "guide": null, "pendingTarget": false, "problemLinks": [{"problemId": "913db334-0095-5009-8584-9f826e243bc1", "revision": 1, "spans": [{"end": 32, "start": 0}]}], "questionBundles": [], "reportedSystemId": "3320ce84-c47c-592c-b599-6e968e12b0a3", "requestId": "bf5ee89d-e7ae-50fa-a017-f1382d124606", "revisionRequestIds": {}, "runRequestIds": {}}'::jsonb, 'COMPLETED', '2026-09-16T14:20:00+09:00'::timestamptz, '2026-09-16T14:20:00+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO agent_run (id, inquiry_id, trigger_message_id, task, route, run_status, input_version, input_snapshot, model_version, prompt_version, schema_version, validation_result, started_at, completed_at, created_at, updated_at)
VALUES ('dd061b09-5955-5687-97a6-18dd1c3b729b', '3da49514-cfd5-50f1-8068-655b6cf3b3b7', '65df9077-4823-54b1-ac85-2ee11e38af56', 'INQUIRY_ANALYSIS', 'SYMPTOM',
        'SUCCEEDED', 1, '{"answerMessageId": "12d82154-3010-564e-a2c3-4afe4fca23b7", "note": "합성 시드. 실제 AI 실행이 아니다.", "seed": true}'::jsonb, 'seed-synthetic', 'seed', 'hops-system-1',
        '{"applied": true, "resources": [], "seed": true}'::jsonb, '2026-09-16T14:20:02+09:00'::timestamptz, '2026-09-16T14:20:20+09:00'::timestamptz, '2026-09-16T14:20:02+09:00'::timestamptz, '2026-09-16T14:20:20+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO message (id, inquiry_id, sequence_no, sender_type, content, revision, payload, message_status, created_at, updated_at)
VALUES ('12d82154-3010-564e-a2c3-4afe4fca23b7', '3da49514-cfd5-50f1-8068-655b6cf3b3b7', 2, 'AI', '첨부 파일이 열리지 않아 곤란하시겠어요. 몇 가지만 확인할게요. 열리지 않는 파일의 형식이 무엇인가요? 그리고 파일을 올린 지 얼마나 되었나요?',
        1, '{"answerRunId": "dd061b09-5955-5687-97a6-18dd1c3b729b", "contractVersion": "hops-system-1", "guide": null, "pendingTarget": false, "problemLinks": [], "questionBundles": [], "requestId": null}'::jsonb, 'COMPLETED', '2026-09-16T14:20:20+09:00'::timestamptz, '2026-09-16T14:20:20+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO message (id, inquiry_id, sequence_no, sender_type, content, revision, payload, message_status, created_at, updated_at)
VALUES ('e3c9b749-b3ef-5134-a920-8c4702179714', '3da49514-cfd5-50f1-8068-655b6cf3b3b7', 3, 'USER', 'PDF 파일이에요. 올리자마자 바로 열어 보려고 했어요.',
        1, '{"answerRunId": null, "contractVersion": "hops-system-1", "guide": null, "pendingTarget": false, "problemLinks": [{"problemId": "913db334-0095-5009-8584-9f826e243bc1", "revision": 1, "spans": [{"end": 31, "start": 0}]}], "questionBundles": [], "reportedSystemId": null, "requestId": "1535751f-9c0c-564e-88c7-78f366455a73", "revisionRequestIds": {}, "runRequestIds": {}}'::jsonb, 'COMPLETED', '2026-09-16T14:23:00+09:00'::timestamptz, '2026-09-16T14:23:00+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO agent_run (id, inquiry_id, trigger_message_id, task, route, run_status, input_version, input_snapshot, model_version, prompt_version, schema_version, validation_result, started_at, completed_at, created_at, updated_at)
VALUES ('1439e648-06fd-591e-a1d2-860d2faebc73', '3da49514-cfd5-50f1-8068-655b6cf3b3b7', 'e3c9b749-b3ef-5134-a920-8c4702179714', 'INQUIRY_ANALYSIS', 'SYMPTOM',
        'SUCCEEDED', 2, '{"answerMessageId": "3806f134-8d14-56c9-8133-7ac41dcdf911", "note": "합성 시드. 실제 AI 실행이 아니다.", "seed": true}'::jsonb, 'seed-synthetic', 'seed', 'hops-system-1',
        '{"applied": true, "resources": [], "seed": true}'::jsonb, '2026-09-16T14:23:02+09:00'::timestamptz, '2026-09-16T14:23:40+09:00'::timestamptz, '2026-09-16T14:23:02+09:00'::timestamptz, '2026-09-16T14:23:40+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO message (id, inquiry_id, sequence_no, sender_type, content, revision, payload, message_status, created_at, updated_at)
VALUES ('3806f134-8d14-56c9-8133-7ac41dcdf911', '3da49514-cfd5-50f1-8068-655b6cf3b3b7', 4, 'AI', '알려 주셔서 감사합니다. 확인된 내용을 정리해 전자결재 담당자에게 접수했습니다. 담당자가 확인한 뒤 결과를 이 대화로 알려 드리겠습니다.',
        1, '{"answerRunId": "1439e648-06fd-591e-a1d2-860d2faebc73", "contractVersion": "hops-system-1", "guide": null, "pendingTarget": false, "problemLinks": [], "questionBundles": [], "requestId": null}'::jsonb, 'COMPLETED', '2026-09-16T14:23:40+09:00'::timestamptz, '2026-09-16T14:23:40+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO inquiry_problem (id, inquiry_id, problem_no, title, detail, problem_status, clarify_count, creation_outcome, creation_input_version, agent_run_id, created_at, updated_at)
VALUES ('913db334-0095-5009-8584-9f826e243bc1', '3da49514-cfd5-50f1-8068-655b6cf3b3b7', 1, '전자결재 문서에 방금 올린 PDF 첨부가 열리지 않음', '방금 전자결재 문서에 올린 PDF 첨부 파일이 열리지 않는다.',
        'TICKET_CREATED', 1, 'STORED', 2, '1439e648-06fd-591e-a1d2-860d2faebc73', '2026-09-16T14:23:40+09:00'::timestamptz, '2026-09-16T14:23:40+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO standard_request (id, inquiry_problem_id, inquiry_version, agent_run_id, intent, work_type, reported_system_id, reported_feature_id, responsible_system_id, system_candidates, summary, confirmed_facts, assumptions, missing_items, type_detail, related_knowledge, created_at)
VALUES ('28efec80-5a59-5aef-8195-e62cc0d10704', '913db334-0095-5009-8584-9f826e243bc1', 2, '1439e648-06fd-591e-a1d2-860d2faebc73', 'OBSERVED_SYMPTOM', 'INVESTIGATION',
        (SELECT id FROM it_system WHERE code = 'ELECTRONIC_APPROVAL'), (SELECT f.id FROM system_feature f JOIN it_system s ON s.id = f.it_system_id WHERE s.code = 'ELECTRONIC_APPROVAL' AND f.code = 'ATTACHMENT_VIEW'), (SELECT id FROM it_system WHERE code = 'ELECTRONIC_APPROVAL'), '[{"evidenceIds": ["65df9077-4823-54b1-ac85-2ee11e38af56"], "systemId": "3320ce84-c47c-592c-b599-6e968e12b0a3"}]'::jsonb, '방금 전자결재 문서에 올린 PDF 첨부 파일이 열리지 않는다.',
        '[{"knowledgeEvidenceIds": [], "sourceMessageIds": ["e3c9b749-b3ef-5134-a920-8c4702179714"], "text": "열리지 않는 파일은 PDF이다."}, {"knowledgeEvidenceIds": [], "sourceMessageIds": ["e3c9b749-b3ef-5134-a920-8c4702179714"], "text": "파일을 올린 직후에 열어 보려고 했다."}]'::jsonb, '[]'::jsonb, '["화면에 표시된 문구"]'::jsonb, '{"acceptanceCriteria": [], "actualBehavior": "첨부 파일이 열리지 않음", "expectedBehavior": "[열기]로 PDF 미리보기가 열림", "occurredAt": null, "taskOrScreen": "전자결재 문서 상세의 첨부 파일 영역에서 방금 올린 PDF를 열려 함"}'::jsonb, '[]'::jsonb, '2026-09-16T14:23:40+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO ticket (id, ticket_number, inquiry_id, inquiry_problem_id, standard_request_id, title, detail, work_type, ticket_status, responsible_system_id, assignee_member_id, confirm_requested_at, resolved_at, version, created_at, updated_at)
VALUES ('cfb47fcd-e06a-533b-9f49-341c3d7888c5', 'SEED-T-002', '3da49514-cfd5-50f1-8068-655b6cf3b3b7', '913db334-0095-5009-8584-9f826e243bc1', '28efec80-5a59-5aef-8195-e62cc0d10704',
        '전자결재 문서에 방금 올린 PDF 첨부가 열리지 않음', '문의자가 전자결재 문서에 PDF를 올린 직후 첨부 파일을 열려 했으나 열리지 않는다. 화면에 표시된 문구는 확인하지 못했다.', 'INVESTIGATION', 'RESOLVED', (SELECT id FROM it_system WHERE code = 'ELECTRONIC_APPROVAL'), (SELECT id FROM member WHERE login_id = 'ea_owner'),
        '2026-09-16T16:20:00+09:00'::timestamptz, '2026-09-16T17:20:00+09:00'::timestamptz, 3, '2026-09-16T14:23:40+09:00'::timestamptz, '2026-09-16T17:20:00+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO ticket_assignment (id, ticket_id, it_system_id, assignee_member_id, source, reason, evidence, assigned_at, created_at, updated_at)
VALUES ('572675da-6539-5b2a-a238-11e05e090c51', 'cfb47fcd-e06a-533b-9f49-341c3d7888c5', (SELECT id FROM it_system WHERE code = 'ELECTRONIC_APPROVAL'), (SELECT id FROM member WHERE login_id = 'ea_owner'), 'AUTO',
        '책임 시스템의 현재 주담당자에게 자동 배정(합성 시드)', '{"messageIds": ["65df9077-4823-54b1-ac85-2ee11e38af56", "e3c9b749-b3ef-5134-a920-8c4702179714"], "note": null}'::jsonb,
        '2026-09-16T14:23:40+09:00'::timestamptz, '2026-09-16T14:23:40+09:00'::timestamptz, '2026-09-16T14:23:40+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO ticket_work_log (id, ticket_id, author_member_id, log_type, action_detail, confirmed_cause, verification_result, applied_version, created_at)
VALUES ('ce0969f1-4fc3-5827-8606-b813ae27edc8', 'cfb47fcd-e06a-533b-9f49-341c3d7888c5', (SELECT id FROM member WHERE login_id = 'ea_owner'), 'PROGRESS', '첨부 파일의 형식(pdf, 미리보기 지원 형식)과 업로드 시각을 확인했다. 업로드한 지 10분이 지나지 않았다.',
        NULL, NULL, NULL, '2026-09-16T15:20:00+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO ticket_work_log (id, ticket_id, author_member_id, log_type, action_detail, confirmed_cause, verification_result, applied_version, created_at)
VALUES ('26b56e55-51ea-52c4-8242-df6d6cecfa84', 'cfb47fcd-e06a-533b-9f49-341c3d7888c5', (SELECT id FROM member WHERE login_id = 'ea_owner'), 'RESOLVED', '문의자에게 보안 검사가 끝날 때까지(최대 10분) 기다린 뒤 다시 열어 보도록 안내했다.',
        '업로드 직후 첨부 파일이 보안 검사 중이어서 열기와 내려받기가 되지 않는 상태였다.', '업로드 약 10분 뒤 문의자가 [열기]로 PDF 미리보기가 열리는 것을 확인했다.', NULL, '2026-09-16T16:20:00+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO ticket_event (id, ticket_id, event_type, actor_type, actor_member_id, before_data, after_data, created_at)
VALUES ('5fc360e3-a6c6-516a-883e-cb79eec621cf', 'cfb47fcd-e06a-533b-9f49-341c3d7888c5', 'STATUS_CHANGED', 'MEMBER', (SELECT id FROM member WHERE login_id = 'ea_owner'),
        '{"state": "IN_PROGRESS"}'::jsonb, '{"resolutionRequest": {"confirmationOrigin": "SYNTHETIC_PRESET", "createdAt": "2026-09-16T07:20:00Z", "id": "9620f0f2-58eb-5200-835f-291454f15b8c", "inquiryId": "3da49514-cfd5-50f1-8068-655b6cf3b3b7", "problemId": "913db334-0095-5009-8584-9f826e243bc1", "requestRevision": 1, "respondedAt": null, "resultSummary": "문의자에게 보안 검사가 끝날 때까지(최대 10분) 기다린 뒤 다시 열어 보도록 안내했다.", "source": "WORK_RECORD", "sourceId": "26b56e55-51ea-52c4-8242-df6d6cecfa84", "sourceVersion": 1, "state": "PENDING", "ticketId": "cfb47fcd-e06a-533b-9f49-341c3d7888c5"}, "state": "WAITING_CONFIRM"}'::jsonb, '2026-09-16T16:20:00+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO ticket_event (id, ticket_id, event_type, actor_type, actor_member_id, before_data, after_data, created_at)
VALUES ('0d71c545-ffc7-5243-9aed-c84e9f22b880', 'cfb47fcd-e06a-533b-9f49-341c3d7888c5', 'USER_CONFIRMED', 'MEMBER', (SELECT id FROM member WHERE login_id = 'requester2'),
        '{"resolutionRequest": {"confirmationOrigin": "SYNTHETIC_PRESET", "createdAt": "2026-09-16T07:20:00Z", "id": "9620f0f2-58eb-5200-835f-291454f15b8c", "inquiryId": "3da49514-cfd5-50f1-8068-655b6cf3b3b7", "problemId": "913db334-0095-5009-8584-9f826e243bc1", "requestRevision": 1, "respondedAt": null, "resultSummary": "문의자에게 보안 검사가 끝날 때까지(최대 10분) 기다린 뒤 다시 열어 보도록 안내했다.", "source": "WORK_RECORD", "sourceId": "26b56e55-51ea-52c4-8242-df6d6cecfa84", "sourceVersion": 1, "state": "PENDING", "ticketId": "cfb47fcd-e06a-533b-9f49-341c3d7888c5"}}'::jsonb, '{"resolutionRequest": {"confirmationOrigin": "SYNTHETIC_PRESET", "createdAt": "2026-09-16T07:20:00Z", "id": "9620f0f2-58eb-5200-835f-291454f15b8c", "inquiryId": "3da49514-cfd5-50f1-8068-655b6cf3b3b7", "problemId": "913db334-0095-5009-8584-9f826e243bc1", "requestRevision": 2, "respondedAt": "2026-09-16T08:20:00Z", "resultSummary": "문의자에게 보안 검사가 끝날 때까지(최대 10분) 기다린 뒤 다시 열어 보도록 안내했다.", "source": "WORK_RECORD", "sourceId": "26b56e55-51ea-52c4-8242-df6d6cecfa84", "sourceVersion": 1, "state": "RESOLVED", "ticketId": "cfb47fcd-e06a-533b-9f49-341c3d7888c5"}}'::jsonb, '2026-09-16T17:20:00+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO agent_run (id, inquiry_id, ticket_id, trigger_work_log_id, task, run_status, input_version, input_snapshot, model_version, prompt_version, schema_version, result, validation_result, started_at, completed_at, created_at, updated_at)
VALUES ('9a2f67ff-7bb0-54e5-b363-240852f9db74', '3da49514-cfd5-50f1-8068-655b6cf3b3b7', 'cfb47fcd-e06a-533b-9f49-341c3d7888c5', '26b56e55-51ea-52c4-8242-df6d6cecfa84', 'CURATION',
        'SUCCEEDED', 2, '{"note": "합성 시드. 실제 AI 실행이 아니다.", "seed": true}'::jsonb, 'seed-synthetic', 'seed', 'hops-system-1',
        '{"caseProposal": {"reusable": true}, "seed": true}'::jsonb, '{"applied": true, "caseGenerationState": "SUCCEEDED", "resources": [{"id": "98e7a55a-b43a-5db0-bf37-97ac2ada89ef", "type": "CASE"}], "seed": true}'::jsonb, '2026-09-16T16:20:05+09:00'::timestamptz, '2026-09-16T16:21:00+09:00'::timestamptz, '2026-09-16T16:20:05+09:00'::timestamptz, '2026-09-16T16:21:00+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO resolved_case (id, case_number, version_no, source_work_log_id, it_system_id, system_feature_id, case_type, title, problem_statement, preconditions, confirmed_facts, confirmed_cause, action_detail, verification_result, applicability, applied_version, access_level, origin_type, case_status, curator_run_id, relation_candidates, approved_by_member_id, approved_at, vector_status, graph_status, created_at, updated_at)
VALUES ('98e7a55a-b43a-5db0-bf37-97ac2ada89ef', 'SEED-C-002', 1, '26b56e55-51ea-52c4-8242-df6d6cecfa84', (SELECT id FROM it_system WHERE code = 'ELECTRONIC_APPROVAL'), (SELECT f.id FROM system_feature f JOIN it_system s ON s.id = f.it_system_id WHERE s.code = 'ELECTRONIC_APPROVAL' AND f.code = 'ATTACHMENT_VIEW'), 'RESOLUTION',
        '전자결재 첨부가 안 열림 - 업로드 직후 보안 검사 중', '전자결재 문서에 방금 올린 첨부 파일이 열리지 않는다.', '첨부 파일 형식이 미리보기 지원 형식(pdf, png, jpg, txt)이다.', '{"facts": [{"knowledgeEvidenceIds": [], "sourceMessageIds": ["e3c9b749-b3ef-5134-a920-8c4702179714"], "text": "열리지 않는 파일은 PDF이다."}, {"knowledgeEvidenceIds": [], "sourceMessageIds": ["e3c9b749-b3ef-5134-a920-8c4702179714"], "text": "파일을 올린 직후에 열어 보려고 했다."}], "knowledge": [], "limitations": ["보안 검사는 최대 10분이 걸린다.", "검사가 끝난 뒤에도 열리지 않으면 이 사례를 적용하지 않는다."], "sourceMessageIds": ["65df9077-4823-54b1-ac85-2ee11e38af56", "e3c9b749-b3ef-5134-a920-8c4702179714"]}'::jsonb,
        '업로드 직후 첨부 파일이 보안 검사 중이어서 열기와 내려받기가 되지 않는 상태였다.', '문의자에게 보안 검사가 끝날 때까지(최대 10분) 기다린 뒤 다시 열어 보도록 안내했다.', '업로드 약 10분 뒤 문의자가 [열기]로 PDF 미리보기가 열리는 것을 확인했다.', '첨부 파일을 올린 지 10분 이내이고 보안 검사 중인 경우에 한한다. 10분이 지나도 열리지 않으면 형식, 열람 권한, 저장소 오류를 따로 확인하며 이 사례를 적용하지 않는다.',
        NULL, 'STAFF', 'SYNTHETIC', 'APPROVED', '9a2f67ff-7bb0-54e5-b363-240852f9db74', '[{"evidenceIds": ["65df9077-4823-54b1-ac85-2ee11e38af56", "e3c9b749-b3ef-5134-a920-8c4702179714"], "relation": "APPLIES_TO", "targetId": "3320ce84-c47c-592c-b599-6e968e12b0a3"}]'::jsonb, (SELECT id FROM member WHERE login_id = 'ea_owner'),
        '2026-09-17T14:20:00+09:00'::timestamptz, 'PENDING', 'PENDING', '2026-09-16T16:21:00+09:00'::timestamptz, '2026-09-17T14:20:00+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO knowledge_document (id, title, document_type, it_system_id, access_level, source_data, data_split, review_status, is_synthetic, created_at, updated_at)
VALUES ('98e7a55a-b43a-5db0-bf37-97ac2ada89ef', '전자결재 첨부가 안 열림 - 업로드 직후 보안 검사 중', 'CASE', (SELECT id FROM it_system WHERE code = 'ELECTRONIC_APPROVAL'), 'STAFF', '{"cardPath": "docs/knowledge/cases/case-ea-attach-scanning.yml", "caseId": "98e7a55a-b43a-5db0-bf37-97ac2ada89ef", "caseNumber": "SEED-C-002", "origin": "SYNTHETIC"}'::jsonb, 'RAG', 'APPROVED', true,
        '2026-09-17T14:20:00+09:00'::timestamptz, '2026-09-17T14:20:00+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO knowledge_document_version (id, knowledge_document_id, version_no, content, content_digest, source_metadata, review_status, created_at)
VALUES ('04646303-cd5f-5313-aaef-23ebe081c102', '98e7a55a-b43a-5db0-bf37-97ac2ada89ef', 1,
        $kb$전자결재 첨부가 안 열림 - 업로드 직후 보안 검사 중

전자결재 문서에 방금 올린 첨부 파일이 열리지 않는다.

문의자에게 보안 검사가 끝날 때까지(최대 10분) 기다린 뒤 다시 열어 보도록 안내했다.

업로드 약 10분 뒤 문의자가 [열기]로 PDF 미리보기가 열리는 것을 확인했다.

첨부 파일을 올린 지 10분 이내이고 보안 검사 중인 경우에 한한다. 10분이 지나도 열리지 않으면 형식, 열람 권한, 저장소 오류를 따로 확인하며 이 사례를 적용하지 않는다.

보안 검사는 최대 10분이 걸린다.
검사가 끝난 뒤에도 열리지 않으면 이 사례를 적용하지 않는다.$kb$,
        '92eb1999e8f506e97b9dddc99cd21a7245da12a88010182152fd3a17cf60729c', '{"caseId": "98e7a55a-b43a-5db0-bf37-97ac2ada89ef", "caseVersion": 1, "indexRequests": {"NEO4J": {"approvedCase": {"accessScope": "SYSTEM_STAFF", "action": "문의자에게 보안 검사가 끝날 때까지(최대 10분) 기다린 뒤 다시 열어 보도록 안내했다.", "applicability": "첨부 파일을 올린 지 10분 이내이고 보안 검사 중인 경우에 한한다. 10분이 지나도 열리지 않으면 형식, 열람 권한, 저장소 오류를 따로 확인하며 이 사례를 적용하지 않는다.", "appliesToSystemVersion": null, "approvedAt": "2026-09-17T05:20:00Z", "approvedBy": "a0000000-0000-7000-8000-000000000011", "caseType": "RESOLUTION", "confirmationOrigin": "SYNTHETIC_PRESET", "confirmedCause": "업로드 직후 첨부 파일이 보안 검사 중이어서 열기와 내려받기가 되지 않는 상태였다.", "entityLinks": [{"evidenceIds": ["65df9077-4823-54b1-ac85-2ee11e38af56", "e3c9b749-b3ef-5134-a920-8c4702179714"], "relation": "APPLIES_TO", "targetId": "3320ce84-c47c-592c-b599-6e968e12b0a3"}], "limitations": ["보안 검사는 최대 10분이 걸린다.", "검사가 끝난 뒤에도 열리지 않으면 이 사례를 적용하지 않는다."], "origin": "SYNTHETIC", "problemStatement": "전자결재 문서에 방금 올린 첨부 파일이 열리지 않는다.", "resolutionRequestId": "9620f0f2-58eb-5200-835f-291454f15b8c", "sourceMessageIds": ["65df9077-4823-54b1-ac85-2ee11e38af56", "e3c9b749-b3ef-5134-a920-8c4702179714"], "systemId": "3320ce84-c47c-592c-b599-6e968e12b0a3", "title": "전자결재 첨부가 안 열림 - 업로드 직후 보안 검사 중", "verification": "업로드 약 10분 뒤 문의자가 [열기]로 PDF 미리보기가 열리는 것을 확인했다.", "workRecordId": "26b56e55-51ea-52c4-8242-df6d6cecfa84"}, "attempt": 1, "caseId": "98e7a55a-b43a-5db0-bf37-97ac2ada89ef", "caseVersion": 1, "contentDigest": "320e1cdee1ebb8d215ce27ebc02712a46fb47dbf840f3d9d9107e75541d55a99", "contractVersion": "hops-system-1", "jobId": "4d339e6c-7145-5934-810b-34da42f3e5f0", "store": "NEO4J"}, "VECTOR": {"approvedCase": {"accessScope": "SYSTEM_STAFF", "action": "문의자에게 보안 검사가 끝날 때까지(최대 10분) 기다린 뒤 다시 열어 보도록 안내했다.", "applicability": "첨부 파일을 올린 지 10분 이내이고 보안 검사 중인 경우에 한한다. 10분이 지나도 열리지 않으면 형식, 열람 권한, 저장소 오류를 따로 확인하며 이 사례를 적용하지 않는다.", "appliesToSystemVersion": null, "approvedAt": "2026-09-17T05:20:00Z", "approvedBy": "a0000000-0000-7000-8000-000000000011", "caseType": "RESOLUTION", "confirmationOrigin": "SYNTHETIC_PRESET", "confirmedCause": "업로드 직후 첨부 파일이 보안 검사 중이어서 열기와 내려받기가 되지 않는 상태였다.", "entityLinks": [{"evidenceIds": ["65df9077-4823-54b1-ac85-2ee11e38af56", "e3c9b749-b3ef-5134-a920-8c4702179714"], "relation": "APPLIES_TO", "targetId": "3320ce84-c47c-592c-b599-6e968e12b0a3"}], "limitations": ["보안 검사는 최대 10분이 걸린다.", "검사가 끝난 뒤에도 열리지 않으면 이 사례를 적용하지 않는다."], "origin": "SYNTHETIC", "problemStatement": "전자결재 문서에 방금 올린 첨부 파일이 열리지 않는다.", "resolutionRequestId": "9620f0f2-58eb-5200-835f-291454f15b8c", "sourceMessageIds": ["65df9077-4823-54b1-ac85-2ee11e38af56", "e3c9b749-b3ef-5134-a920-8c4702179714"], "systemId": "3320ce84-c47c-592c-b599-6e968e12b0a3", "title": "전자결재 첨부가 안 열림 - 업로드 직후 보안 검사 중", "verification": "업로드 약 10분 뒤 문의자가 [열기]로 PDF 미리보기가 열리는 것을 확인했다.", "workRecordId": "26b56e55-51ea-52c4-8242-df6d6cecfa84"}, "attempt": 1, "caseId": "98e7a55a-b43a-5db0-bf37-97ac2ada89ef", "caseVersion": 1, "contentDigest": "b05a914b0be0f24437123fd604f427b4bc444128d59c919a42c408fe3d24993a", "contractVersion": "hops-system-1", "jobId": "72ef8e61-3cd2-5d2b-935b-c082b7aa3dbf", "store": "VECTOR"}}}'::jsonb, 'APPROVED', '2026-09-17T14:20:00+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
UPDATE inquiry SET current_run_id = '1439e648-06fd-591e-a1d2-860d2faebc73' WHERE id = '3da49514-cfd5-50f1-8068-655b6cf3b3b7' AND current_run_id IS NULL;

-- ===== case-login-cookie-block (SEED-Q-003, SEED-T-003, SEED-C-003) =====
INSERT INTO inquiry (id, inquiry_number, requester_member_id, title, inquiry_status, version, current_run_id, result_revision, created_at, updated_at)
VALUES ('566323f9-e257-5144-8744-6f38bfaba911', 'SEED-Q-003', (SELECT id FROM member WHERE login_id = 'requester3'), '사내 포털 로그인 후 다시 로그인 화면으로 돌아옴',
        'RESOLVED', 2, NULL, 3, '2026-09-18T09:30:00+09:00'::timestamptz, '2026-09-19T09:30:00+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO message (id, inquiry_id, sequence_no, sender_type, content, revision, payload, message_status, created_at, updated_at)
VALUES ('0daef54e-4611-5b23-928d-50a09e439b38', '566323f9-e257-5144-8744-6f38bfaba911', 1, 'USER', '사내 포털에 로그인하면 다시 로그인 화면으로 돌아와요. 계속 반복돼요.',
        1, '{"answerRunId": null, "contractVersion": "hops-system-1", "guide": null, "pendingTarget": false, "problemLinks": [{"problemId": "3b3ddf83-b41f-545b-88c4-dbad4b92bd2c", "revision": 1, "spans": [{"end": 39, "start": 0}]}], "questionBundles": [], "reportedSystemId": "8ae35be5-1857-58c0-a478-e05b4217ff95", "requestId": "e191dc21-185f-58de-a223-89281ac2e1eb", "revisionRequestIds": {}, "runRequestIds": {}}'::jsonb, 'COMPLETED', '2026-09-18T09:30:00+09:00'::timestamptz, '2026-09-18T09:30:00+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO agent_run (id, inquiry_id, trigger_message_id, task, route, run_status, input_version, input_snapshot, model_version, prompt_version, schema_version, validation_result, started_at, completed_at, created_at, updated_at)
VALUES ('b93aa7f7-433b-5a3d-a7d0-8ac403616004', '566323f9-e257-5144-8744-6f38bfaba911', '0daef54e-4611-5b23-928d-50a09e439b38', 'INQUIRY_ANALYSIS', 'SYMPTOM',
        'SUCCEEDED', 1, '{"answerMessageId": "c6aae58f-f51a-58b9-bf2c-78e266b89f27", "note": "합성 시드. 실제 AI 실행이 아니다.", "seed": true}'::jsonb, 'seed-synthetic', 'seed', 'hops-system-1',
        '{"applied": true, "resources": [], "seed": true}'::jsonb, '2026-09-18T09:30:02+09:00'::timestamptz, '2026-09-18T09:30:20+09:00'::timestamptz, '2026-09-18T09:30:02+09:00'::timestamptz, '2026-09-18T09:30:20+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO message (id, inquiry_id, sequence_no, sender_type, content, revision, payload, message_status, created_at, updated_at)
VALUES ('c6aae58f-f51a-58b9-bf2c-78e266b89f27', '566323f9-e257-5144-8744-6f38bfaba911', 2, 'AI', '로그인이 반복되면 업무가 어려우시겠어요. 확인할게요. 브라우저에서 쿠키를 차단하는 설정을 해 두셨나요? 그리고 다른 브라우저나 시크릿 창에서 로그인하면 어떤가요?',
        1, '{"answerRunId": "b93aa7f7-433b-5a3d-a7d0-8ac403616004", "contractVersion": "hops-system-1", "guide": null, "pendingTarget": false, "problemLinks": [], "questionBundles": [], "requestId": null}'::jsonb, 'COMPLETED', '2026-09-18T09:30:20+09:00'::timestamptz, '2026-09-18T09:30:20+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO message (id, inquiry_id, sequence_no, sender_type, content, revision, payload, message_status, created_at, updated_at)
VALUES ('0db3051d-0a82-5190-ba3c-38865253ac35', '566323f9-e257-5144-8744-6f38bfaba911', 3, 'USER', '쿠키 설정은 잘 모르겠어요. 크롬에서만 그렇고 엣지에서는 로그인이 잘 돼요.',
        1, '{"answerRunId": null, "contractVersion": "hops-system-1", "guide": null, "pendingTarget": false, "problemLinks": [{"problemId": "3b3ddf83-b41f-545b-88c4-dbad4b92bd2c", "revision": 1, "spans": [{"end": 42, "start": 0}]}], "questionBundles": [], "reportedSystemId": null, "requestId": "417f10b8-f1a8-5e22-84af-dd8811dd2803", "revisionRequestIds": {}, "runRequestIds": {}}'::jsonb, 'COMPLETED', '2026-09-18T09:33:00+09:00'::timestamptz, '2026-09-18T09:33:00+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO agent_run (id, inquiry_id, trigger_message_id, task, route, run_status, input_version, input_snapshot, model_version, prompt_version, schema_version, validation_result, started_at, completed_at, created_at, updated_at)
VALUES ('70cd3518-da95-51a6-a478-0f87c7412fcb', '566323f9-e257-5144-8744-6f38bfaba911', '0db3051d-0a82-5190-ba3c-38865253ac35', 'INQUIRY_ANALYSIS', 'SYMPTOM',
        'SUCCEEDED', 2, '{"answerMessageId": "b2f56843-61e3-5035-add8-ff23051923a3", "note": "합성 시드. 실제 AI 실행이 아니다.", "seed": true}'::jsonb, 'seed-synthetic', 'seed', 'hops-system-1',
        '{"applied": true, "resources": [], "seed": true}'::jsonb, '2026-09-18T09:33:02+09:00'::timestamptz, '2026-09-18T09:33:40+09:00'::timestamptz, '2026-09-18T09:33:02+09:00'::timestamptz, '2026-09-18T09:33:40+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO message (id, inquiry_id, sequence_no, sender_type, content, revision, payload, message_status, created_at, updated_at)
VALUES ('b2f56843-61e3-5035-add8-ff23051923a3', '566323f9-e257-5144-8744-6f38bfaba911', 4, 'AI', '알려 주셔서 감사합니다. 확인된 내용을 정리해 공통 로그인 담당자에게 접수했습니다. 담당자가 확인한 뒤 결과를 이 대화로 알려 드리겠습니다.',
        1, '{"answerRunId": "70cd3518-da95-51a6-a478-0f87c7412fcb", "contractVersion": "hops-system-1", "guide": null, "pendingTarget": false, "problemLinks": [], "questionBundles": [], "requestId": null}'::jsonb, 'COMPLETED', '2026-09-18T09:33:40+09:00'::timestamptz, '2026-09-18T09:33:40+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO inquiry_problem (id, inquiry_id, problem_no, title, detail, problem_status, clarify_count, creation_outcome, creation_input_version, agent_run_id, created_at, updated_at)
VALUES ('3b3ddf83-b41f-545b-88c4-dbad4b92bd2c', '566323f9-e257-5144-8744-6f38bfaba911', 1, '포털 로그인 후 로그인 화면 반복(크롬에서만)', '사내 포털에 로그인해도 다시 로그인 화면으로 돌아온다. 크롬에서만 반복되고 엣지에서는 정상이다.',
        'TICKET_CREATED', 1, 'STORED', 2, '70cd3518-da95-51a6-a478-0f87c7412fcb', '2026-09-18T09:33:40+09:00'::timestamptz, '2026-09-18T09:33:40+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO standard_request (id, inquiry_problem_id, inquiry_version, agent_run_id, intent, work_type, reported_system_id, reported_feature_id, responsible_system_id, system_candidates, summary, confirmed_facts, assumptions, missing_items, type_detail, related_knowledge, created_at)
VALUES ('74bad3ed-1b93-56bb-a5b6-71782aa88bc4', '3b3ddf83-b41f-545b-88c4-dbad4b92bd2c', 2, '70cd3518-da95-51a6-a478-0f87c7412fcb', 'OBSERVED_SYMPTOM', 'INVESTIGATION',
        (SELECT id FROM it_system WHERE code = 'COMMON_LOGIN'), (SELECT f.id FROM system_feature f JOIN it_system s ON s.id = f.it_system_id WHERE s.code = 'COMMON_LOGIN' AND f.code = 'LOGIN_SSO'), (SELECT id FROM it_system WHERE code = 'COMMON_LOGIN'), '[{"evidenceIds": ["0daef54e-4611-5b23-928d-50a09e439b38"], "systemId": "8ae35be5-1857-58c0-a478-e05b4217ff95"}]'::jsonb, '사내 포털에 로그인해도 다시 로그인 화면으로 돌아온다. 크롬에서만 반복되고 엣지에서는 정상이다.',
        '[{"knowledgeEvidenceIds": [], "sourceMessageIds": ["0db3051d-0a82-5190-ba3c-38865253ac35"], "text": "크롬에서만 로그인 후 로그인 화면이 반복된다."}, {"knowledgeEvidenceIds": [], "sourceMessageIds": ["0db3051d-0a82-5190-ba3c-38865253ac35"], "text": "엣지에서는 로그인이 정상이다."}]'::jsonb, '[]'::jsonb, '["브라우저의 쿠키 차단 설정 여부"]'::jsonb, '{"acceptanceCriteria": [], "actualBehavior": "다시 로그인 화면으로 돌아옴", "expectedBehavior": "로그인 뒤 대상 시스템 화면이 열림", "occurredAt": null, "taskOrScreen": "사내 포털 로그인 화면에서 로그인함"}'::jsonb, '[]'::jsonb, '2026-09-18T09:33:40+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO ticket (id, ticket_number, inquiry_id, inquiry_problem_id, standard_request_id, title, detail, work_type, ticket_status, responsible_system_id, assignee_member_id, confirm_requested_at, resolved_at, version, created_at, updated_at)
VALUES ('c3b744ee-a414-52de-9b58-e26f6b7c0b9f', 'SEED-T-003', '566323f9-e257-5144-8744-6f38bfaba911', '3b3ddf83-b41f-545b-88c4-dbad4b92bd2c', '74bad3ed-1b93-56bb-a5b6-71782aa88bc4',
        '포털 로그인 후 로그인 화면 반복(크롬에서만)', '문의자가 사내 포털에 로그인한 뒤 다시 로그인 화면으로 돌아온다. 크롬에서만 반복되고 엣지에서는 정상이다. 쿠키 설정은 문의자가 모른다.', 'INVESTIGATION', 'RESOLVED', (SELECT id FROM it_system WHERE code = 'COMMON_LOGIN'), (SELECT id FROM member WHERE login_id = 'login_owner'),
        '2026-09-18T11:30:00+09:00'::timestamptz, '2026-09-18T12:30:00+09:00'::timestamptz, 3, '2026-09-18T09:33:40+09:00'::timestamptz, '2026-09-18T12:30:00+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO ticket_assignment (id, ticket_id, it_system_id, assignee_member_id, source, reason, evidence, assigned_at, created_at, updated_at)
VALUES ('bc3e74c7-c974-5014-a48b-5e1c2fa33648', 'c3b744ee-a414-52de-9b58-e26f6b7c0b9f', (SELECT id FROM it_system WHERE code = 'COMMON_LOGIN'), (SELECT id FROM member WHERE login_id = 'login_owner'), 'AUTO',
        '책임 시스템의 현재 주담당자에게 자동 배정(합성 시드)', '{"messageIds": ["0daef54e-4611-5b23-928d-50a09e439b38", "0db3051d-0a82-5190-ba3c-38865253ac35"], "note": null}'::jsonb,
        '2026-09-18T09:33:40+09:00'::timestamptz, '2026-09-18T09:33:40+09:00'::timestamptz, '2026-09-18T09:33:40+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO ticket_work_log (id, ticket_id, author_member_id, log_type, action_detail, confirmed_cause, verification_result, applied_version, created_at)
VALUES ('be338409-0d92-5db7-9209-5086d08d3a12', 'c3b744ee-a414-52de-9b58-e26f6b7c0b9f', (SELECT id FROM member WHERE login_id = 'login_owner'), 'PROGRESS', '문의자 단말의 날짜·시간이 맞는 것을 확인했고, 크롬에서만 반복되는 점을 근거로 브라우저 설정을 확인하기로 했다.',
        NULL, NULL, NULL, '2026-09-18T10:30:00+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO ticket_work_log (id, ticket_id, author_member_id, log_type, action_detail, confirmed_cause, verification_result, applied_version, created_at)
VALUES ('c58bd7dc-ec6c-5212-a448-874d220d93e8', 'c3b744ee-a414-52de-9b58-e26f6b7c0b9f', (SELECT id FROM member WHERE login_id = 'login_owner'), 'RESOLVED', '문의자에게 크롬에서 포털 도메인의 쿠키를 허용하도록 안내했다.',
        '문의자 크롬의 쿠키 차단 설정 때문에 SSO 세션이 저장되지 않았다.', '쿠키를 허용한 뒤 크롬에서 로그인하자 대상 시스템 화면이 유지되고 로그인 화면으로 돌아오지 않았다.', NULL, '2026-09-18T11:30:00+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO ticket_event (id, ticket_id, event_type, actor_type, actor_member_id, before_data, after_data, created_at)
VALUES ('ae5568f0-7fcc-5fff-b84e-48818f48bd4c', 'c3b744ee-a414-52de-9b58-e26f6b7c0b9f', 'STATUS_CHANGED', 'MEMBER', (SELECT id FROM member WHERE login_id = 'login_owner'),
        '{"state": "IN_PROGRESS"}'::jsonb, '{"resolutionRequest": {"confirmationOrigin": "SYNTHETIC_PRESET", "createdAt": "2026-09-18T02:30:00Z", "id": "e85608e7-3fd0-5041-a786-5557984a7ad9", "inquiryId": "566323f9-e257-5144-8744-6f38bfaba911", "problemId": "3b3ddf83-b41f-545b-88c4-dbad4b92bd2c", "requestRevision": 1, "respondedAt": null, "resultSummary": "문의자에게 크롬에서 포털 도메인의 쿠키를 허용하도록 안내했다.", "source": "WORK_RECORD", "sourceId": "c58bd7dc-ec6c-5212-a448-874d220d93e8", "sourceVersion": 1, "state": "PENDING", "ticketId": "c3b744ee-a414-52de-9b58-e26f6b7c0b9f"}, "state": "WAITING_CONFIRM"}'::jsonb, '2026-09-18T11:30:00+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO ticket_event (id, ticket_id, event_type, actor_type, actor_member_id, before_data, after_data, created_at)
VALUES ('7ea3b43d-5440-52dc-9923-9f1672e05187', 'c3b744ee-a414-52de-9b58-e26f6b7c0b9f', 'USER_CONFIRMED', 'MEMBER', (SELECT id FROM member WHERE login_id = 'requester3'),
        '{"resolutionRequest": {"confirmationOrigin": "SYNTHETIC_PRESET", "createdAt": "2026-09-18T02:30:00Z", "id": "e85608e7-3fd0-5041-a786-5557984a7ad9", "inquiryId": "566323f9-e257-5144-8744-6f38bfaba911", "problemId": "3b3ddf83-b41f-545b-88c4-dbad4b92bd2c", "requestRevision": 1, "respondedAt": null, "resultSummary": "문의자에게 크롬에서 포털 도메인의 쿠키를 허용하도록 안내했다.", "source": "WORK_RECORD", "sourceId": "c58bd7dc-ec6c-5212-a448-874d220d93e8", "sourceVersion": 1, "state": "PENDING", "ticketId": "c3b744ee-a414-52de-9b58-e26f6b7c0b9f"}}'::jsonb, '{"resolutionRequest": {"confirmationOrigin": "SYNTHETIC_PRESET", "createdAt": "2026-09-18T02:30:00Z", "id": "e85608e7-3fd0-5041-a786-5557984a7ad9", "inquiryId": "566323f9-e257-5144-8744-6f38bfaba911", "problemId": "3b3ddf83-b41f-545b-88c4-dbad4b92bd2c", "requestRevision": 2, "respondedAt": "2026-09-18T03:30:00Z", "resultSummary": "문의자에게 크롬에서 포털 도메인의 쿠키를 허용하도록 안내했다.", "source": "WORK_RECORD", "sourceId": "c58bd7dc-ec6c-5212-a448-874d220d93e8", "sourceVersion": 1, "state": "RESOLVED", "ticketId": "c3b744ee-a414-52de-9b58-e26f6b7c0b9f"}}'::jsonb, '2026-09-18T12:30:00+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO agent_run (id, inquiry_id, ticket_id, trigger_work_log_id, task, run_status, input_version, input_snapshot, model_version, prompt_version, schema_version, result, validation_result, started_at, completed_at, created_at, updated_at)
VALUES ('ec1a43cf-c743-536a-b0b1-ab6e0bc1fa26', '566323f9-e257-5144-8744-6f38bfaba911', 'c3b744ee-a414-52de-9b58-e26f6b7c0b9f', 'c58bd7dc-ec6c-5212-a448-874d220d93e8', 'CURATION',
        'SUCCEEDED', 2, '{"note": "합성 시드. 실제 AI 실행이 아니다.", "seed": true}'::jsonb, 'seed-synthetic', 'seed', 'hops-system-1',
        '{"caseProposal": {"reusable": true}, "seed": true}'::jsonb, '{"applied": true, "caseGenerationState": "SUCCEEDED", "resources": [{"id": "7c7a3a13-2972-5c14-8ad4-b00cc9b0a6ed", "type": "CASE"}], "seed": true}'::jsonb, '2026-09-18T11:30:05+09:00'::timestamptz, '2026-09-18T11:31:00+09:00'::timestamptz, '2026-09-18T11:30:05+09:00'::timestamptz, '2026-09-18T11:31:00+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO resolved_case (id, case_number, version_no, source_work_log_id, it_system_id, system_feature_id, case_type, title, problem_statement, preconditions, confirmed_facts, confirmed_cause, action_detail, verification_result, applicability, applied_version, access_level, origin_type, case_status, curator_run_id, relation_candidates, approved_by_member_id, approved_at, vector_status, graph_status, created_at, updated_at)
VALUES ('7c7a3a13-2972-5c14-8ad4-b00cc9b0a6ed', 'SEED-C-003', 1, 'c58bd7dc-ec6c-5212-a448-874d220d93e8', (SELECT id FROM it_system WHERE code = 'COMMON_LOGIN'), (SELECT f.id FROM system_feature f JOIN it_system s ON s.id = f.it_system_id WHERE s.code = 'COMMON_LOGIN' AND f.code = 'LOGIN_SSO'), 'RESOLUTION',
        '포털 로그인 후 로그인 화면 반복 - 브라우저의 쿠키 차단', '사내 포털에 로그인해도 다시 로그인 화면으로 돌아온다. 특정 브라우저에서만 반복된다.', '인증 실패나 계정 잠금, 세션 만료가 아니라 로그인에는 성공한다.', '{"facts": [{"knowledgeEvidenceIds": [], "sourceMessageIds": ["0db3051d-0a82-5190-ba3c-38865253ac35"], "text": "크롬에서만 로그인 후 로그인 화면이 반복된다."}, {"knowledgeEvidenceIds": [], "sourceMessageIds": ["0db3051d-0a82-5190-ba3c-38865253ac35"], "text": "엣지에서는 로그인이 정상이다."}], "knowledge": [], "limitations": ["다른 브라우저에서도 같은 증상이면 쿠키 차단이 원인이 아니다.", "단말 시각이 서버와 5분 넘게 어긋난 경우는 다른 원인이다."], "sourceMessageIds": ["0daef54e-4611-5b23-928d-50a09e439b38", "0db3051d-0a82-5190-ba3c-38865253ac35"]}'::jsonb,
        '문의자 크롬의 쿠키 차단 설정 때문에 SSO 세션이 저장되지 않았다.', '문의자에게 크롬에서 포털 도메인의 쿠키를 허용하도록 안내했다.', '쿠키를 허용한 뒤 크롬에서 로그인하자 대상 시스템 화면이 유지되고 로그인 화면으로 돌아오지 않았다.', '특정 브라우저에서만 로그인 후 로그인 화면이 반복되고 다른 브라우저에서는 정상이며 단말 시각이 맞는 경우에 한한다. 모든 브라우저에서 반복되거나 특정 연동 시스템에서만 반복되면 적용하지 않는다.',
        NULL, 'STAFF', 'SYNTHETIC', 'APPROVED', 'ec1a43cf-c743-536a-b0b1-ab6e0bc1fa26', '[{"evidenceIds": ["0daef54e-4611-5b23-928d-50a09e439b38", "0db3051d-0a82-5190-ba3c-38865253ac35"], "relation": "APPLIES_TO", "targetId": "8ae35be5-1857-58c0-a478-e05b4217ff95"}]'::jsonb, (SELECT id FROM member WHERE login_id = 'login_owner'),
        '2026-09-19T09:30:00+09:00'::timestamptz, 'PENDING', 'PENDING', '2026-09-18T11:31:00+09:00'::timestamptz, '2026-09-19T09:30:00+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO knowledge_document (id, title, document_type, it_system_id, access_level, source_data, data_split, review_status, is_synthetic, created_at, updated_at)
VALUES ('7c7a3a13-2972-5c14-8ad4-b00cc9b0a6ed', '포털 로그인 후 로그인 화면 반복 - 브라우저의 쿠키 차단', 'CASE', (SELECT id FROM it_system WHERE code = 'COMMON_LOGIN'), 'STAFF', '{"cardPath": "docs/knowledge/cases/case-login-cookie-block.yml", "caseId": "7c7a3a13-2972-5c14-8ad4-b00cc9b0a6ed", "caseNumber": "SEED-C-003", "origin": "SYNTHETIC"}'::jsonb, 'RAG', 'APPROVED', true,
        '2026-09-19T09:30:00+09:00'::timestamptz, '2026-09-19T09:30:00+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO knowledge_document_version (id, knowledge_document_id, version_no, content, content_digest, source_metadata, review_status, created_at)
VALUES ('70004886-5c41-552e-8dbd-6c73453e2583', '7c7a3a13-2972-5c14-8ad4-b00cc9b0a6ed', 1,
        $kb$포털 로그인 후 로그인 화면 반복 - 브라우저의 쿠키 차단

사내 포털에 로그인해도 다시 로그인 화면으로 돌아온다. 특정 브라우저에서만 반복된다.

문의자에게 크롬에서 포털 도메인의 쿠키를 허용하도록 안내했다.

쿠키를 허용한 뒤 크롬에서 로그인하자 대상 시스템 화면이 유지되고 로그인 화면으로 돌아오지 않았다.

특정 브라우저에서만 로그인 후 로그인 화면이 반복되고 다른 브라우저에서는 정상이며 단말 시각이 맞는 경우에 한한다. 모든 브라우저에서 반복되거나 특정 연동 시스템에서만 반복되면 적용하지 않는다.

다른 브라우저에서도 같은 증상이면 쿠키 차단이 원인이 아니다.
단말 시각이 서버와 5분 넘게 어긋난 경우는 다른 원인이다.$kb$,
        '1d889bae275ce39c57c6df9ab21177644e1a6effb4ba2ba0f86a67143633818b', '{"caseId": "7c7a3a13-2972-5c14-8ad4-b00cc9b0a6ed", "caseVersion": 1, "indexRequests": {"NEO4J": {"approvedCase": {"accessScope": "SYSTEM_STAFF", "action": "문의자에게 크롬에서 포털 도메인의 쿠키를 허용하도록 안내했다.", "applicability": "특정 브라우저에서만 로그인 후 로그인 화면이 반복되고 다른 브라우저에서는 정상이며 단말 시각이 맞는 경우에 한한다. 모든 브라우저에서 반복되거나 특정 연동 시스템에서만 반복되면 적용하지 않는다.", "appliesToSystemVersion": null, "approvedAt": "2026-09-19T00:30:00Z", "approvedBy": "a0000000-0000-7000-8000-000000000012", "caseType": "RESOLUTION", "confirmationOrigin": "SYNTHETIC_PRESET", "confirmedCause": "문의자 크롬의 쿠키 차단 설정 때문에 SSO 세션이 저장되지 않았다.", "entityLinks": [{"evidenceIds": ["0daef54e-4611-5b23-928d-50a09e439b38", "0db3051d-0a82-5190-ba3c-38865253ac35"], "relation": "APPLIES_TO", "targetId": "8ae35be5-1857-58c0-a478-e05b4217ff95"}], "limitations": ["다른 브라우저에서도 같은 증상이면 쿠키 차단이 원인이 아니다.", "단말 시각이 서버와 5분 넘게 어긋난 경우는 다른 원인이다."], "origin": "SYNTHETIC", "problemStatement": "사내 포털에 로그인해도 다시 로그인 화면으로 돌아온다. 특정 브라우저에서만 반복된다.", "resolutionRequestId": "e85608e7-3fd0-5041-a786-5557984a7ad9", "sourceMessageIds": ["0daef54e-4611-5b23-928d-50a09e439b38", "0db3051d-0a82-5190-ba3c-38865253ac35"], "systemId": "8ae35be5-1857-58c0-a478-e05b4217ff95", "title": "포털 로그인 후 로그인 화면 반복 - 브라우저의 쿠키 차단", "verification": "쿠키를 허용한 뒤 크롬에서 로그인하자 대상 시스템 화면이 유지되고 로그인 화면으로 돌아오지 않았다.", "workRecordId": "c58bd7dc-ec6c-5212-a448-874d220d93e8"}, "attempt": 1, "caseId": "7c7a3a13-2972-5c14-8ad4-b00cc9b0a6ed", "caseVersion": 1, "contentDigest": "35455e44c91254789c4dbf291a67e36ec9cc61f499452289888b44e3b72452a6", "contractVersion": "hops-system-1", "jobId": "c1264ae3-d37b-50f2-a71f-9fecc413282b", "store": "NEO4J"}, "VECTOR": {"approvedCase": {"accessScope": "SYSTEM_STAFF", "action": "문의자에게 크롬에서 포털 도메인의 쿠키를 허용하도록 안내했다.", "applicability": "특정 브라우저에서만 로그인 후 로그인 화면이 반복되고 다른 브라우저에서는 정상이며 단말 시각이 맞는 경우에 한한다. 모든 브라우저에서 반복되거나 특정 연동 시스템에서만 반복되면 적용하지 않는다.", "appliesToSystemVersion": null, "approvedAt": "2026-09-19T00:30:00Z", "approvedBy": "a0000000-0000-7000-8000-000000000012", "caseType": "RESOLUTION", "confirmationOrigin": "SYNTHETIC_PRESET", "confirmedCause": "문의자 크롬의 쿠키 차단 설정 때문에 SSO 세션이 저장되지 않았다.", "entityLinks": [{"evidenceIds": ["0daef54e-4611-5b23-928d-50a09e439b38", "0db3051d-0a82-5190-ba3c-38865253ac35"], "relation": "APPLIES_TO", "targetId": "8ae35be5-1857-58c0-a478-e05b4217ff95"}], "limitations": ["다른 브라우저에서도 같은 증상이면 쿠키 차단이 원인이 아니다.", "단말 시각이 서버와 5분 넘게 어긋난 경우는 다른 원인이다."], "origin": "SYNTHETIC", "problemStatement": "사내 포털에 로그인해도 다시 로그인 화면으로 돌아온다. 특정 브라우저에서만 반복된다.", "resolutionRequestId": "e85608e7-3fd0-5041-a786-5557984a7ad9", "sourceMessageIds": ["0daef54e-4611-5b23-928d-50a09e439b38", "0db3051d-0a82-5190-ba3c-38865253ac35"], "systemId": "8ae35be5-1857-58c0-a478-e05b4217ff95", "title": "포털 로그인 후 로그인 화면 반복 - 브라우저의 쿠키 차단", "verification": "쿠키를 허용한 뒤 크롬에서 로그인하자 대상 시스템 화면이 유지되고 로그인 화면으로 돌아오지 않았다.", "workRecordId": "c58bd7dc-ec6c-5212-a448-874d220d93e8"}, "attempt": 1, "caseId": "7c7a3a13-2972-5c14-8ad4-b00cc9b0a6ed", "caseVersion": 1, "contentDigest": "7ad4d221b8a76dd2b46a0866928b0a3a1df1fded2a63c036f3bedcf3077a0b5f", "contractVersion": "hops-system-1", "jobId": "400ac8e5-0508-5a43-a308-0977f996143a", "store": "VECTOR"}}}'::jsonb, 'APPROVED', '2026-09-19T09:30:00+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
UPDATE inquiry SET current_run_id = '70cd3518-da95-51a6-a478-0f87c7412fcb' WHERE id = '566323f9-e257-5144-8744-6f38bfaba911' AND current_run_id IS NULL;

-- ===== case-login-write-permission-delay (SEED-Q-004, SEED-T-004, SEED-C-004) =====
INSERT INTO inquiry (id, inquiry_number, requester_member_id, title, inquiry_status, version, current_run_id, result_revision, created_at, updated_at)
VALUES ('a569962a-14da-5d22-b3b3-7d20068fff5e', 'SEED-Q-004', (SELECT id FROM member WHERE login_id = 'requester4'), '파일 공유 쓰기 권한 신청이 승인되지 않고 지연됨',
        'RESOLVED', 2, NULL, 3, '2026-09-21T11:15:00+09:00'::timestamptz, '2026-09-22T11:15:00+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO message (id, inquiry_id, sequence_no, sender_type, content, revision, payload, message_status, created_at, updated_at)
VALUES ('8bfa180d-63e0-5768-8c93-ce236cf51a72', 'a569962a-14da-5d22-b3b3-7d20068fff5e', 1, 'USER', '파일 공유 쓰기 권한을 신청한 지 사흘이 지났는데 아직 승인이 안 됐어요.',
        1, '{"answerRunId": null, "contractVersion": "hops-system-1", "guide": null, "pendingTarget": false, "problemLinks": [{"problemId": "a411bcd1-fd77-5353-968e-7134dbc2ae82", "revision": 1, "spans": [{"end": 41, "start": 0}]}], "questionBundles": [], "reportedSystemId": "8ae35be5-1857-58c0-a478-e05b4217ff95", "requestId": "930e944d-bf62-590f-808c-02984c569637", "revisionRequestIds": {}, "runRequestIds": {}}'::jsonb, 'COMPLETED', '2026-09-21T11:15:00+09:00'::timestamptz, '2026-09-21T11:15:00+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO agent_run (id, inquiry_id, trigger_message_id, task, route, run_status, input_version, input_snapshot, model_version, prompt_version, schema_version, validation_result, started_at, completed_at, created_at, updated_at)
VALUES ('632de262-0e47-5dd5-8389-9cf854b8e0ae', 'a569962a-14da-5d22-b3b3-7d20068fff5e', '8bfa180d-63e0-5768-8c93-ce236cf51a72', 'INQUIRY_ANALYSIS', 'REQUEST',
        'SUCCEEDED', 1, '{"answerMessageId": "1af55cfd-b45d-53f5-b29e-3bf00ed1298a", "note": "합성 시드. 실제 AI 실행이 아니다.", "seed": true}'::jsonb, 'seed-synthetic', 'seed', 'hops-system-1',
        '{"applied": true, "resources": [], "seed": true}'::jsonb, '2026-09-21T11:15:02+09:00'::timestamptz, '2026-09-21T11:15:20+09:00'::timestamptz, '2026-09-21T11:15:02+09:00'::timestamptz, '2026-09-21T11:15:20+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO message (id, inquiry_id, sequence_no, sender_type, content, revision, payload, message_status, created_at, updated_at)
VALUES ('1af55cfd-b45d-53f5-b29e-3bf00ed1298a', 'a569962a-14da-5d22-b3b3-7d20068fff5e', 2, 'AI', '신청이 오래 걸려 걱정되시겠어요. 확인할게요. 어떤 권한 종류로 신청하셨나요(읽기, 쓰기, 관리)? 그리고 소속 팀장에게 확인을 요청하셨나요?',
        1, '{"answerRunId": "632de262-0e47-5dd5-8389-9cf854b8e0ae", "contractVersion": "hops-system-1", "guide": null, "pendingTarget": false, "problemLinks": [], "questionBundles": [], "requestId": null}'::jsonb, 'COMPLETED', '2026-09-21T11:15:20+09:00'::timestamptz, '2026-09-21T11:15:20+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO message (id, inquiry_id, sequence_no, sender_type, content, revision, payload, message_status, created_at, updated_at)
VALUES ('1b3286f8-a525-50db-ba93-84ea5dcf1699', 'a569962a-14da-5d22-b3b3-7d20068fff5e', 3, 'USER', '쓰기 권한으로 신청했어요. 팀장님께 따로 요청드린 적은 없어요.',
        1, '{"answerRunId": null, "contractVersion": "hops-system-1", "guide": null, "pendingTarget": false, "problemLinks": [{"problemId": "a411bcd1-fd77-5353-968e-7134dbc2ae82", "revision": 1, "spans": [{"end": 35, "start": 0}]}], "questionBundles": [], "reportedSystemId": null, "requestId": "bd618b9d-910d-515d-b3cf-328ecedeff4f", "revisionRequestIds": {}, "runRequestIds": {}}'::jsonb, 'COMPLETED', '2026-09-21T11:18:00+09:00'::timestamptz, '2026-09-21T11:18:00+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO agent_run (id, inquiry_id, trigger_message_id, task, route, run_status, input_version, input_snapshot, model_version, prompt_version, schema_version, validation_result, started_at, completed_at, created_at, updated_at)
VALUES ('78a6ebd9-5071-5b71-86e4-c3124c4af84f', 'a569962a-14da-5d22-b3b3-7d20068fff5e', '1b3286f8-a525-50db-ba93-84ea5dcf1699', 'INQUIRY_ANALYSIS', 'REQUEST',
        'SUCCEEDED', 2, '{"answerMessageId": "2252b134-5528-5df6-a8ab-abd30687027d", "note": "합성 시드. 실제 AI 실행이 아니다.", "seed": true}'::jsonb, 'seed-synthetic', 'seed', 'hops-system-1',
        '{"applied": true, "resources": [], "seed": true}'::jsonb, '2026-09-21T11:18:02+09:00'::timestamptz, '2026-09-21T11:18:40+09:00'::timestamptz, '2026-09-21T11:18:02+09:00'::timestamptz, '2026-09-21T11:18:40+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO message (id, inquiry_id, sequence_no, sender_type, content, revision, payload, message_status, created_at, updated_at)
VALUES ('2252b134-5528-5df6-a8ab-abd30687027d', 'a569962a-14da-5d22-b3b3-7d20068fff5e', 4, 'AI', '알려 주셔서 감사합니다. 확인된 내용을 정리해 공통 로그인 담당자에게 접수했습니다. 담당자가 확인한 뒤 결과를 이 대화로 알려 드리겠습니다.',
        1, '{"answerRunId": "78a6ebd9-5071-5b71-86e4-c3124c4af84f", "contractVersion": "hops-system-1", "guide": null, "pendingTarget": false, "problemLinks": [], "questionBundles": [], "requestId": null}'::jsonb, 'COMPLETED', '2026-09-21T11:18:40+09:00'::timestamptz, '2026-09-21T11:18:40+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO inquiry_problem (id, inquiry_id, problem_no, title, detail, problem_status, clarify_count, creation_outcome, creation_input_version, agent_run_id, created_at, updated_at)
VALUES ('a411bcd1-fd77-5353-968e-7134dbc2ae82', 'a569962a-14da-5d22-b3b3-7d20068fff5e', 1, '파일 공유 쓰기 권한 신청이 승인되지 않고 접수 상태에 머묾', '파일 공유 쓰기 권한 신청이 사흘이 지나도 승인되지 않았다.',
        'TICKET_CREATED', 1, 'STORED', 2, '78a6ebd9-5071-5b71-86e4-c3124c4af84f', '2026-09-21T11:18:40+09:00'::timestamptz, '2026-09-21T11:18:40+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO standard_request (id, inquiry_problem_id, inquiry_version, agent_run_id, intent, work_type, reported_system_id, reported_feature_id, responsible_system_id, system_candidates, summary, confirmed_facts, assumptions, missing_items, type_detail, related_knowledge, created_at)
VALUES ('9b823199-852d-5695-a11c-16bcd3f15e1a', 'a411bcd1-fd77-5353-968e-7134dbc2ae82', 2, '78a6ebd9-5071-5b71-86e4-c3124c4af84f', 'ACCESS_REQUEST', 'SERVICE_REQUEST',
        (SELECT id FROM it_system WHERE code = 'COMMON_LOGIN'), (SELECT f.id FROM system_feature f JOIN it_system s ON s.id = f.it_system_id WHERE s.code = 'COMMON_LOGIN' AND f.code = 'PERMISSION_REQUEST'), (SELECT id FROM it_system WHERE code = 'COMMON_LOGIN'), '[{"evidenceIds": ["8bfa180d-63e0-5768-8c93-ce236cf51a72"], "systemId": "8ae35be5-1857-58c0-a478-e05b4217ff95"}]'::jsonb, '파일 공유 쓰기 권한 신청이 사흘이 지나도 승인되지 않았다.',
        '[{"knowledgeEvidenceIds": [], "sourceMessageIds": ["1b3286f8-a525-50db-ba93-84ea5dcf1699"], "text": "신청한 권한 종류는 쓰기이다."}, {"knowledgeEvidenceIds": [], "sourceMessageIds": ["1b3286f8-a525-50db-ba93-84ea5dcf1699"], "text": "소속 팀장에게 따로 확인을 요청하지 않았다."}]'::jsonb, '[]'::jsonb, '["업무 목적"]'::jsonb, '{"acceptanceCriteria": [], "businessPurpose": null, "occurredAt": null, "requestedAccess": "파일 공유 쓰기 권한"}'::jsonb, '[]'::jsonb, '2026-09-21T11:18:40+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO ticket (id, ticket_number, inquiry_id, inquiry_problem_id, standard_request_id, title, detail, work_type, ticket_status, responsible_system_id, assignee_member_id, confirm_requested_at, resolved_at, version, created_at, updated_at)
VALUES ('646b4a7b-e3af-5d1a-a784-df31fcd1b11e', 'SEED-T-004', 'a569962a-14da-5d22-b3b3-7d20068fff5e', 'a411bcd1-fd77-5353-968e-7134dbc2ae82', '9b823199-852d-5695-a11c-16bcd3f15e1a',
        '파일 공유 쓰기 권한 신청이 승인되지 않고 접수 상태에 머묾', '문의자가 파일 공유 쓰기 권한을 신청한 지 사흘이 지났으나 승인되지 않았다. 문의자는 소속 팀장에게 확인을 요청하지 않았다.', 'SERVICE_REQUEST', 'RESOLVED', (SELECT id FROM it_system WHERE code = 'COMMON_LOGIN'), (SELECT id FROM member WHERE login_id = 'login_owner'),
        '2026-09-21T13:15:00+09:00'::timestamptz, '2026-09-21T14:15:00+09:00'::timestamptz, 3, '2026-09-21T11:18:40+09:00'::timestamptz, '2026-09-21T14:15:00+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO ticket_assignment (id, ticket_id, it_system_id, assignee_member_id, source, reason, evidence, assigned_at, created_at, updated_at)
VALUES ('9d5831f3-dd77-5935-9e0d-1aae5c8feacc', '646b4a7b-e3af-5d1a-a784-df31fcd1b11e', (SELECT id FROM it_system WHERE code = 'COMMON_LOGIN'), (SELECT id FROM member WHERE login_id = 'login_owner'), 'AUTO',
        '책임 시스템의 현재 주담당자에게 자동 배정(합성 시드)', '{"messageIds": ["8bfa180d-63e0-5768-8c93-ce236cf51a72", "1b3286f8-a525-50db-ba93-84ea5dcf1699"], "note": null}'::jsonb,
        '2026-09-21T11:18:40+09:00'::timestamptz, '2026-09-21T11:18:40+09:00'::timestamptz, '2026-09-21T11:18:40+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO ticket_work_log (id, ticket_id, author_member_id, log_type, action_detail, confirmed_cause, verification_result, applied_version, created_at)
VALUES ('8eae6d2b-f567-57aa-9c29-25655ff99407', '646b4a7b-e3af-5d1a-a784-df31fcd1b11e', (SELECT id FROM member WHERE login_id = 'login_owner'), 'PROGRESS', '신청 상태가 접수(REQUESTED)에 머물러 있고 팀장 확인 기록이 없는 것을 확인했다.',
        NULL, NULL, NULL, '2026-09-21T12:15:00+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO ticket_work_log (id, ticket_id, author_member_id, log_type, action_detail, confirmed_cause, verification_result, applied_version, created_at)
VALUES ('8e024a4a-2df1-5f02-b63c-83f52d6764fc', '646b4a7b-e3af-5d1a-a784-df31fcd1b11e', (SELECT id FROM member WHERE login_id = 'login_owner'), 'RESOLVED', '문의자에게 소속 팀장에게 확인을 요청하도록 안내했고, 팀장 확인이 끝난 뒤 대상 시스템(파일 공유) 주담당자의 승인을 받아 적용까지 진행했다.',
        '쓰기 권한은 소속 팀장 확인이 필요한데 문의자가 확인을 요청하지 않아 신청이 접수 상태에 머물렀다.', '팀장 확인 뒤 신청 상태가 승인을 거쳐 적용(APPLIED)으로 바뀌었고, 문의자가 파일 공유에서 쓰기 권한을 쓸 수 있는 것을 확인했다.', NULL, '2026-09-21T13:15:00+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO ticket_event (id, ticket_id, event_type, actor_type, actor_member_id, before_data, after_data, created_at)
VALUES ('7fdb998f-9445-58d1-8497-04ccae9bf852', '646b4a7b-e3af-5d1a-a784-df31fcd1b11e', 'STATUS_CHANGED', 'MEMBER', (SELECT id FROM member WHERE login_id = 'login_owner'),
        '{"state": "IN_PROGRESS"}'::jsonb, '{"resolutionRequest": {"confirmationOrigin": "SYNTHETIC_PRESET", "createdAt": "2026-09-21T04:15:00Z", "id": "782ded79-ddfe-52e0-abdf-fe3fbbe12c82", "inquiryId": "a569962a-14da-5d22-b3b3-7d20068fff5e", "problemId": "a411bcd1-fd77-5353-968e-7134dbc2ae82", "requestRevision": 1, "respondedAt": null, "resultSummary": "문의자에게 소속 팀장에게 확인을 요청하도록 안내했고, 팀장 확인이 끝난 뒤 대상 시스템(파일 공유) 주담당자의 승인을 받아 적용까지 진행했다.", "source": "WORK_RECORD", "sourceId": "8e024a4a-2df1-5f02-b63c-83f52d6764fc", "sourceVersion": 1, "state": "PENDING", "ticketId": "646b4a7b-e3af-5d1a-a784-df31fcd1b11e"}, "state": "WAITING_CONFIRM"}'::jsonb, '2026-09-21T13:15:00+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO ticket_event (id, ticket_id, event_type, actor_type, actor_member_id, before_data, after_data, created_at)
VALUES ('c8749729-cc40-5a9a-a4cf-bf971f691665', '646b4a7b-e3af-5d1a-a784-df31fcd1b11e', 'USER_CONFIRMED', 'MEMBER', (SELECT id FROM member WHERE login_id = 'requester4'),
        '{"resolutionRequest": {"confirmationOrigin": "SYNTHETIC_PRESET", "createdAt": "2026-09-21T04:15:00Z", "id": "782ded79-ddfe-52e0-abdf-fe3fbbe12c82", "inquiryId": "a569962a-14da-5d22-b3b3-7d20068fff5e", "problemId": "a411bcd1-fd77-5353-968e-7134dbc2ae82", "requestRevision": 1, "respondedAt": null, "resultSummary": "문의자에게 소속 팀장에게 확인을 요청하도록 안내했고, 팀장 확인이 끝난 뒤 대상 시스템(파일 공유) 주담당자의 승인을 받아 적용까지 진행했다.", "source": "WORK_RECORD", "sourceId": "8e024a4a-2df1-5f02-b63c-83f52d6764fc", "sourceVersion": 1, "state": "PENDING", "ticketId": "646b4a7b-e3af-5d1a-a784-df31fcd1b11e"}}'::jsonb, '{"resolutionRequest": {"confirmationOrigin": "SYNTHETIC_PRESET", "createdAt": "2026-09-21T04:15:00Z", "id": "782ded79-ddfe-52e0-abdf-fe3fbbe12c82", "inquiryId": "a569962a-14da-5d22-b3b3-7d20068fff5e", "problemId": "a411bcd1-fd77-5353-968e-7134dbc2ae82", "requestRevision": 2, "respondedAt": "2026-09-21T05:15:00Z", "resultSummary": "문의자에게 소속 팀장에게 확인을 요청하도록 안내했고, 팀장 확인이 끝난 뒤 대상 시스템(파일 공유) 주담당자의 승인을 받아 적용까지 진행했다.", "source": "WORK_RECORD", "sourceId": "8e024a4a-2df1-5f02-b63c-83f52d6764fc", "sourceVersion": 1, "state": "RESOLVED", "ticketId": "646b4a7b-e3af-5d1a-a784-df31fcd1b11e"}}'::jsonb, '2026-09-21T14:15:00+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO agent_run (id, inquiry_id, ticket_id, trigger_work_log_id, task, run_status, input_version, input_snapshot, model_version, prompt_version, schema_version, result, validation_result, started_at, completed_at, created_at, updated_at)
VALUES ('723bf3d6-14b3-5c76-9f07-88c369679bd9', 'a569962a-14da-5d22-b3b3-7d20068fff5e', '646b4a7b-e3af-5d1a-a784-df31fcd1b11e', '8e024a4a-2df1-5f02-b63c-83f52d6764fc', 'CURATION',
        'SUCCEEDED', 2, '{"note": "합성 시드. 실제 AI 실행이 아니다.", "seed": true}'::jsonb, 'seed-synthetic', 'seed', 'hops-system-1',
        '{"caseProposal": {"reusable": true}, "seed": true}'::jsonb, '{"applied": true, "caseGenerationState": "SUCCEEDED", "resources": [{"id": "c79206a4-e6ea-5ee0-8feb-80dbd16884d6", "type": "CASE"}], "seed": true}'::jsonb, '2026-09-21T13:15:05+09:00'::timestamptz, '2026-09-21T13:16:00+09:00'::timestamptz, '2026-09-21T13:15:05+09:00'::timestamptz, '2026-09-21T13:16:00+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO resolved_case (id, case_number, version_no, source_work_log_id, it_system_id, system_feature_id, case_type, title, problem_statement, preconditions, confirmed_facts, confirmed_cause, action_detail, verification_result, applicability, applied_version, access_level, origin_type, case_status, curator_run_id, relation_candidates, approved_by_member_id, approved_at, vector_status, graph_status, created_at, updated_at)
VALUES ('c79206a4-e6ea-5ee0-8feb-80dbd16884d6', 'SEED-C-004', 1, '8e024a4a-2df1-5f02-b63c-83f52d6764fc', (SELECT id FROM it_system WHERE code = 'COMMON_LOGIN'), (SELECT f.id FROM system_feature f JOIN it_system s ON s.id = f.it_system_id WHERE s.code = 'COMMON_LOGIN' AND f.code = 'PERMISSION_REQUEST'), 'RESOLUTION',
        '쓰기 권한 신청이 지연됨 - 소속 팀장 확인 누락', '쓰기 권한 신청이 처리 목표 기간(2영업일)이 지나도 승인되지 않고 접수 상태에 머문다.', '신청한 권한 종류가 쓰기 또는 관리이다.', '{"facts": [{"knowledgeEvidenceIds": [], "sourceMessageIds": ["1b3286f8-a525-50db-ba93-84ea5dcf1699"], "text": "신청한 권한 종류는 쓰기이다."}, {"knowledgeEvidenceIds": [], "sourceMessageIds": ["1b3286f8-a525-50db-ba93-84ea5dcf1699"], "text": "소속 팀장에게 따로 확인을 요청하지 않았다."}], "knowledge": [], "limitations": ["읽기 권한 신청에는 팀장 확인이 필요하지 않다.", "승인은 대상 시스템 주담당자가 한다."], "sourceMessageIds": ["8bfa180d-63e0-5768-8c93-ce236cf51a72", "1b3286f8-a525-50db-ba93-84ea5dcf1699"]}'::jsonb,
        '쓰기 권한은 소속 팀장 확인이 필요한데 문의자가 확인을 요청하지 않아 신청이 접수 상태에 머물렀다.', '문의자에게 소속 팀장에게 확인을 요청하도록 안내했고, 팀장 확인이 끝난 뒤 대상 시스템(파일 공유) 주담당자의 승인을 받아 적용까지 진행했다.', '팀장 확인 뒤 신청 상태가 승인을 거쳐 적용(APPLIED)으로 바뀌었고, 문의자가 파일 공유에서 쓰기 권한을 쓸 수 있는 것을 확인했다.', '쓰기·관리 권한 신청이 접수 상태에 머물고 팀장 확인 기록이 없는 경우에 한한다. 읽기 권한 신청이거나 팀장 확인이 끝났는데도 지연되는 경우에는 적용하지 않는다.',
        NULL, 'STAFF', 'SYNTHETIC', 'APPROVED', '723bf3d6-14b3-5c76-9f07-88c369679bd9', '[{"evidenceIds": ["8bfa180d-63e0-5768-8c93-ce236cf51a72", "1b3286f8-a525-50db-ba93-84ea5dcf1699"], "relation": "APPLIES_TO", "targetId": "8ae35be5-1857-58c0-a478-e05b4217ff95"}]'::jsonb, (SELECT id FROM member WHERE login_id = 'login_owner'),
        '2026-09-22T11:15:00+09:00'::timestamptz, 'PENDING', 'PENDING', '2026-09-21T13:16:00+09:00'::timestamptz, '2026-09-22T11:15:00+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO knowledge_document (id, title, document_type, it_system_id, access_level, source_data, data_split, review_status, is_synthetic, created_at, updated_at)
VALUES ('c79206a4-e6ea-5ee0-8feb-80dbd16884d6', '쓰기 권한 신청이 지연됨 - 소속 팀장 확인 누락', 'CASE', (SELECT id FROM it_system WHERE code = 'COMMON_LOGIN'), 'STAFF', '{"cardPath": "docs/knowledge/cases/case-login-write-permission-delay.yml", "caseId": "c79206a4-e6ea-5ee0-8feb-80dbd16884d6", "caseNumber": "SEED-C-004", "origin": "SYNTHETIC"}'::jsonb, 'RAG', 'APPROVED', true,
        '2026-09-22T11:15:00+09:00'::timestamptz, '2026-09-22T11:15:00+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO knowledge_document_version (id, knowledge_document_id, version_no, content, content_digest, source_metadata, review_status, created_at)
VALUES ('072daf95-dc24-5d89-8062-3069157ada02', 'c79206a4-e6ea-5ee0-8feb-80dbd16884d6', 1,
        $kb$쓰기 권한 신청이 지연됨 - 소속 팀장 확인 누락

쓰기 권한 신청이 처리 목표 기간(2영업일)이 지나도 승인되지 않고 접수 상태에 머문다.

문의자에게 소속 팀장에게 확인을 요청하도록 안내했고, 팀장 확인이 끝난 뒤 대상 시스템(파일 공유) 주담당자의 승인을 받아 적용까지 진행했다.

팀장 확인 뒤 신청 상태가 승인을 거쳐 적용(APPLIED)으로 바뀌었고, 문의자가 파일 공유에서 쓰기 권한을 쓸 수 있는 것을 확인했다.

쓰기·관리 권한 신청이 접수 상태에 머물고 팀장 확인 기록이 없는 경우에 한한다. 읽기 권한 신청이거나 팀장 확인이 끝났는데도 지연되는 경우에는 적용하지 않는다.

읽기 권한 신청에는 팀장 확인이 필요하지 않다.
승인은 대상 시스템 주담당자가 한다.$kb$,
        '0591c3ed660aa63b99850d199674d3aadab5732f3e5573b996e1fb5b09632a7d', '{"caseId": "c79206a4-e6ea-5ee0-8feb-80dbd16884d6", "caseVersion": 1, "indexRequests": {"NEO4J": {"approvedCase": {"accessScope": "SYSTEM_STAFF", "action": "문의자에게 소속 팀장에게 확인을 요청하도록 안내했고, 팀장 확인이 끝난 뒤 대상 시스템(파일 공유) 주담당자의 승인을 받아 적용까지 진행했다.", "applicability": "쓰기·관리 권한 신청이 접수 상태에 머물고 팀장 확인 기록이 없는 경우에 한한다. 읽기 권한 신청이거나 팀장 확인이 끝났는데도 지연되는 경우에는 적용하지 않는다.", "appliesToSystemVersion": null, "approvedAt": "2026-09-22T02:15:00Z", "approvedBy": "a0000000-0000-7000-8000-000000000012", "caseType": "RESOLUTION", "confirmationOrigin": "SYNTHETIC_PRESET", "confirmedCause": "쓰기 권한은 소속 팀장 확인이 필요한데 문의자가 확인을 요청하지 않아 신청이 접수 상태에 머물렀다.", "entityLinks": [{"evidenceIds": ["8bfa180d-63e0-5768-8c93-ce236cf51a72", "1b3286f8-a525-50db-ba93-84ea5dcf1699"], "relation": "APPLIES_TO", "targetId": "8ae35be5-1857-58c0-a478-e05b4217ff95"}], "limitations": ["읽기 권한 신청에는 팀장 확인이 필요하지 않다.", "승인은 대상 시스템 주담당자가 한다."], "origin": "SYNTHETIC", "problemStatement": "쓰기 권한 신청이 처리 목표 기간(2영업일)이 지나도 승인되지 않고 접수 상태에 머문다.", "resolutionRequestId": "782ded79-ddfe-52e0-abdf-fe3fbbe12c82", "sourceMessageIds": ["8bfa180d-63e0-5768-8c93-ce236cf51a72", "1b3286f8-a525-50db-ba93-84ea5dcf1699"], "systemId": "8ae35be5-1857-58c0-a478-e05b4217ff95", "title": "쓰기 권한 신청이 지연됨 - 소속 팀장 확인 누락", "verification": "팀장 확인 뒤 신청 상태가 승인을 거쳐 적용(APPLIED)으로 바뀌었고, 문의자가 파일 공유에서 쓰기 권한을 쓸 수 있는 것을 확인했다.", "workRecordId": "8e024a4a-2df1-5f02-b63c-83f52d6764fc"}, "attempt": 1, "caseId": "c79206a4-e6ea-5ee0-8feb-80dbd16884d6", "caseVersion": 1, "contentDigest": "97e22c762e6fc4e2b9699f865ad9406b2e9be96dfd9c7105a018c298cb08ba8b", "contractVersion": "hops-system-1", "jobId": "5ebf2313-47d4-525a-b388-be251f852fa7", "store": "NEO4J"}, "VECTOR": {"approvedCase": {"accessScope": "SYSTEM_STAFF", "action": "문의자에게 소속 팀장에게 확인을 요청하도록 안내했고, 팀장 확인이 끝난 뒤 대상 시스템(파일 공유) 주담당자의 승인을 받아 적용까지 진행했다.", "applicability": "쓰기·관리 권한 신청이 접수 상태에 머물고 팀장 확인 기록이 없는 경우에 한한다. 읽기 권한 신청이거나 팀장 확인이 끝났는데도 지연되는 경우에는 적용하지 않는다.", "appliesToSystemVersion": null, "approvedAt": "2026-09-22T02:15:00Z", "approvedBy": "a0000000-0000-7000-8000-000000000012", "caseType": "RESOLUTION", "confirmationOrigin": "SYNTHETIC_PRESET", "confirmedCause": "쓰기 권한은 소속 팀장 확인이 필요한데 문의자가 확인을 요청하지 않아 신청이 접수 상태에 머물렀다.", "entityLinks": [{"evidenceIds": ["8bfa180d-63e0-5768-8c93-ce236cf51a72", "1b3286f8-a525-50db-ba93-84ea5dcf1699"], "relation": "APPLIES_TO", "targetId": "8ae35be5-1857-58c0-a478-e05b4217ff95"}], "limitations": ["읽기 권한 신청에는 팀장 확인이 필요하지 않다.", "승인은 대상 시스템 주담당자가 한다."], "origin": "SYNTHETIC", "problemStatement": "쓰기 권한 신청이 처리 목표 기간(2영업일)이 지나도 승인되지 않고 접수 상태에 머문다.", "resolutionRequestId": "782ded79-ddfe-52e0-abdf-fe3fbbe12c82", "sourceMessageIds": ["8bfa180d-63e0-5768-8c93-ce236cf51a72", "1b3286f8-a525-50db-ba93-84ea5dcf1699"], "systemId": "8ae35be5-1857-58c0-a478-e05b4217ff95", "title": "쓰기 권한 신청이 지연됨 - 소속 팀장 확인 누락", "verification": "팀장 확인 뒤 신청 상태가 승인을 거쳐 적용(APPLIED)으로 바뀌었고, 문의자가 파일 공유에서 쓰기 권한을 쓸 수 있는 것을 확인했다.", "workRecordId": "8e024a4a-2df1-5f02-b63c-83f52d6764fc"}, "attempt": 1, "caseId": "c79206a4-e6ea-5ee0-8feb-80dbd16884d6", "caseVersion": 1, "contentDigest": "6ff47b55f1638a3bbc995e77dc989e60d9947c899131be6449ac2b9d91bd1ccb", "contractVersion": "hops-system-1", "jobId": "6fff765f-e753-5b79-a341-8d6dd836aac0", "store": "VECTOR"}}}'::jsonb, 'APPROVED', '2026-09-22T11:15:00+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
UPDATE inquiry SET current_run_id = '78a6ebd9-5071-5b71-86e4-c3124c4af84f' WHERE id = 'a569962a-14da-5d22-b3b3-7d20068fff5e' AND current_run_id IS NULL;

-- ===== case-file-folder-no-access (SEED-Q-005, SEED-T-005, SEED-C-005) =====
INSERT INTO inquiry (id, inquiry_number, requester_member_id, title, inquiry_status, version, current_run_id, result_revision, created_at, updated_at)
VALUES ('584b3361-0c52-5c9f-9f86-6e70b389c455', 'SEED-Q-005', (SELECT id FROM member WHERE login_id = 'requester1'), '링크로 접근한 파일 공유 폴더가 열리지 않음',
        'RESOLVED', 2, NULL, 3, '2026-09-23T15:40:00+09:00'::timestamptz, '2026-09-24T15:40:00+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO message (id, inquiry_id, sequence_no, sender_type, content, revision, payload, message_status, created_at, updated_at)
VALUES ('eed02bfa-c83b-5a64-bd75-0668b93248e1', '584b3361-0c52-5c9f-9f86-6e70b389c455', 1, 'USER', '동료가 보내 준 링크로 파일 공유 폴더에 들어가려는데 접근할 수 없다고 나와요.',
        1, '{"answerRunId": null, "contractVersion": "hops-system-1", "guide": null, "pendingTarget": false, "problemLinks": [{"problemId": "3e950bf9-440b-5ede-8dc4-d8304dc06e89", "revision": 1, "spans": [{"end": 44, "start": 0}]}], "questionBundles": [], "reportedSystemId": "22d935d0-2ed8-58a7-a9b8-d73f717ed3c4", "requestId": "1712db3e-2566-55b4-aa54-39c483c898a1", "revisionRequestIds": {}, "runRequestIds": {}}'::jsonb, 'COMPLETED', '2026-09-23T15:40:00+09:00'::timestamptz, '2026-09-23T15:40:00+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO agent_run (id, inquiry_id, trigger_message_id, task, route, run_status, input_version, input_snapshot, model_version, prompt_version, schema_version, validation_result, started_at, completed_at, created_at, updated_at)
VALUES ('ac555f69-bb7a-5a5d-aedf-3474a41d8af4', '584b3361-0c52-5c9f-9f86-6e70b389c455', 'eed02bfa-c83b-5a64-bd75-0668b93248e1', 'INQUIRY_ANALYSIS', 'SYMPTOM',
        'SUCCEEDED', 1, '{"answerMessageId": "6c702636-f152-5078-8f5f-d3c40f372871", "note": "합성 시드. 실제 AI 실행이 아니다.", "seed": true}'::jsonb, 'seed-synthetic', 'seed', 'hops-system-1',
        '{"applied": true, "resources": [], "seed": true}'::jsonb, '2026-09-23T15:40:02+09:00'::timestamptz, '2026-09-23T15:40:20+09:00'::timestamptz, '2026-09-23T15:40:02+09:00'::timestamptz, '2026-09-23T15:40:20+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO message (id, inquiry_id, sequence_no, sender_type, content, revision, payload, message_status, created_at, updated_at)
VALUES ('6c702636-f152-5078-8f5f-d3c40f372871', '584b3361-0c52-5c9f-9f86-6e70b389c455', 2, 'AI', '폴더가 열리지 않아 곤란하시겠어요. 화면에 어떤 문구가 표시되나요? 그리고 폴더 목록에서 열려고 하셨는지, 링크로 여셨는지 알려 주세요.',
        1, '{"answerRunId": "ac555f69-bb7a-5a5d-aedf-3474a41d8af4", "contractVersion": "hops-system-1", "guide": null, "pendingTarget": false, "problemLinks": [], "questionBundles": [], "requestId": null}'::jsonb, 'COMPLETED', '2026-09-23T15:40:20+09:00'::timestamptz, '2026-09-23T15:40:20+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO message (id, inquiry_id, sequence_no, sender_type, content, revision, payload, message_status, created_at, updated_at)
VALUES ('b96ae3df-36ba-59cf-9ed6-8c9027615852', '584b3361-0c52-5c9f-9f86-6e70b389c455', 3, 'USER', '''이 폴더에 접근할 권한이 없습니다''라고 나와요. 링크를 눌러서 열었고, 목록에서는 그 폴더가 안 보여요.',
        1, '{"answerRunId": null, "contractVersion": "hops-system-1", "guide": null, "pendingTarget": false, "problemLinks": [{"problemId": "3e950bf9-440b-5ede-8dc4-d8304dc06e89", "revision": 1, "spans": [{"end": 59, "start": 0}]}], "questionBundles": [], "reportedSystemId": null, "requestId": "eee886ef-2fe7-5c1d-836f-5b25a5d3efbd", "revisionRequestIds": {}, "runRequestIds": {}}'::jsonb, 'COMPLETED', '2026-09-23T15:43:00+09:00'::timestamptz, '2026-09-23T15:43:00+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO agent_run (id, inquiry_id, trigger_message_id, task, route, run_status, input_version, input_snapshot, model_version, prompt_version, schema_version, validation_result, started_at, completed_at, created_at, updated_at)
VALUES ('bf50de42-cbbe-53fb-9589-378fb3b871a6', '584b3361-0c52-5c9f-9f86-6e70b389c455', 'b96ae3df-36ba-59cf-9ed6-8c9027615852', 'INQUIRY_ANALYSIS', 'SYMPTOM',
        'SUCCEEDED', 2, '{"answerMessageId": "5b5cebe5-7224-5c1e-89ab-6781fa6c1abb", "note": "합성 시드. 실제 AI 실행이 아니다.", "seed": true}'::jsonb, 'seed-synthetic', 'seed', 'hops-system-1',
        '{"applied": true, "resources": [], "seed": true}'::jsonb, '2026-09-23T15:43:02+09:00'::timestamptz, '2026-09-23T15:43:40+09:00'::timestamptz, '2026-09-23T15:43:02+09:00'::timestamptz, '2026-09-23T15:43:40+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO message (id, inquiry_id, sequence_no, sender_type, content, revision, payload, message_status, created_at, updated_at)
VALUES ('5b5cebe5-7224-5c1e-89ab-6781fa6c1abb', '584b3361-0c52-5c9f-9f86-6e70b389c455', 4, 'AI', '알려 주셔서 감사합니다. 확인된 내용을 정리해 파일 공유 담당자에게 접수했습니다. 담당자가 확인한 뒤 결과를 이 대화로 알려 드리겠습니다.',
        1, '{"answerRunId": "bf50de42-cbbe-53fb-9589-378fb3b871a6", "contractVersion": "hops-system-1", "guide": null, "pendingTarget": false, "problemLinks": [], "questionBundles": [], "requestId": null}'::jsonb, 'COMPLETED', '2026-09-23T15:43:40+09:00'::timestamptz, '2026-09-23T15:43:40+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO inquiry_problem (id, inquiry_id, problem_no, title, detail, problem_status, clarify_count, creation_outcome, creation_input_version, agent_run_id, created_at, updated_at)
VALUES ('3e950bf9-440b-5ede-8dc4-d8304dc06e89', '584b3361-0c52-5c9f-9f86-6e70b389c455', 1, '링크로 연 파일 공유 폴더에 접근 권한이 없다고 표시됨', '링크로 연 파일 공유 폴더에서 접근 권한이 없다는 문구가 나오고, 폴더 목록에서는 그 폴더가 보이지 않는다.',
        'TICKET_CREATED', 1, 'STORED', 2, 'bf50de42-cbbe-53fb-9589-378fb3b871a6', '2026-09-23T15:43:40+09:00'::timestamptz, '2026-09-23T15:43:40+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO standard_request (id, inquiry_problem_id, inquiry_version, agent_run_id, intent, work_type, reported_system_id, reported_feature_id, responsible_system_id, system_candidates, summary, confirmed_facts, assumptions, missing_items, type_detail, related_knowledge, created_at)
VALUES ('1f4aa26b-981d-594d-8dea-3678494a918f', '3e950bf9-440b-5ede-8dc4-d8304dc06e89', 2, 'bf50de42-cbbe-53fb-9589-378fb3b871a6', 'OBSERVED_SYMPTOM', 'INVESTIGATION',
        (SELECT id FROM it_system WHERE code = 'FILE_SHARING'), (SELECT f.id FROM system_feature f JOIN it_system s ON s.id = f.it_system_id WHERE s.code = 'FILE_SHARING' AND f.code = 'FILE_VIEW'), (SELECT id FROM it_system WHERE code = 'FILE_SHARING'), '[{"evidenceIds": ["eed02bfa-c83b-5a64-bd75-0668b93248e1"], "systemId": "22d935d0-2ed8-58a7-a9b8-d73f717ed3c4"}]'::jsonb, '링크로 연 파일 공유 폴더에서 접근 권한이 없다는 문구가 나오고, 폴더 목록에서는 그 폴더가 보이지 않는다.',
        '[{"knowledgeEvidenceIds": [], "sourceMessageIds": ["b96ae3df-36ba-59cf-9ed6-8c9027615852"], "text": "표시된 문구는 ''이 폴더에 접근할 권한이 없습니다''이다."}, {"knowledgeEvidenceIds": [], "sourceMessageIds": ["b96ae3df-36ba-59cf-9ed6-8c9027615852"], "text": "링크로 열었고 폴더 목록에는 그 폴더가 보이지 않는다."}]'::jsonb, '[]'::jsonb, '[]'::jsonb, '{"acceptanceCriteria": [], "actualBehavior": "''이 폴더에 접근할 권한이 없습니다''가 표시됨", "expectedBehavior": "폴더가 열림", "occurredAt": null, "taskOrScreen": "동료가 보낸 링크로 파일 공유 폴더를 열려 함"}'::jsonb, '[]'::jsonb, '2026-09-23T15:43:40+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO ticket (id, ticket_number, inquiry_id, inquiry_problem_id, standard_request_id, title, detail, work_type, ticket_status, responsible_system_id, assignee_member_id, confirm_requested_at, resolved_at, version, created_at, updated_at)
VALUES ('c07114c9-98cb-53a0-9dd9-b86ce0d3903c', 'SEED-T-005', '584b3361-0c52-5c9f-9f86-6e70b389c455', '3e950bf9-440b-5ede-8dc4-d8304dc06e89', '1f4aa26b-981d-594d-8dea-3678494a918f',
        '링크로 연 파일 공유 폴더에 접근 권한이 없다고 표시됨', '문의자가 링크로 파일 공유 폴더를 열었더니 접근 권한이 없다는 문구가 나온다. 폴더 목록에는 해당 폴더가 보이지 않는다.', 'INVESTIGATION', 'RESOLVED', (SELECT id FROM it_system WHERE code = 'FILE_SHARING'), (SELECT id FROM member WHERE login_id = 'file_owner'),
        '2026-09-23T17:40:00+09:00'::timestamptz, '2026-09-23T18:40:00+09:00'::timestamptz, 3, '2026-09-23T15:43:40+09:00'::timestamptz, '2026-09-23T18:40:00+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO ticket_assignment (id, ticket_id, it_system_id, assignee_member_id, source, reason, evidence, assigned_at, created_at, updated_at)
VALUES ('7eebc2db-68f5-5e21-930b-3bd1bded819d', 'c07114c9-98cb-53a0-9dd9-b86ce0d3903c', (SELECT id FROM it_system WHERE code = 'FILE_SHARING'), (SELECT id FROM member WHERE login_id = 'file_owner'), 'AUTO',
        '책임 시스템의 현재 주담당자에게 자동 배정(합성 시드)', '{"messageIds": ["eed02bfa-c83b-5a64-bd75-0668b93248e1", "b96ae3df-36ba-59cf-9ed6-8c9027615852"], "note": null}'::jsonb,
        '2026-09-23T15:43:40+09:00'::timestamptz, '2026-09-23T15:43:40+09:00'::timestamptz, '2026-09-23T15:43:40+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO ticket_work_log (id, ticket_id, author_member_id, log_type, action_detail, confirmed_cause, verification_result, applied_version, created_at)
VALUES ('5c4150fe-3d1d-56b7-b777-19888131e181', 'c07114c9-98cb-53a0-9dd9-b86ce0d3903c', (SELECT id FROM member WHERE login_id = 'file_owner'), 'PROGRESS', '문의자 계정에 해당 폴더의 접근 권한이 부여되어 있지 않은 것을 확인했다. 폴더는 삭제되거나 이동되지 않았다.',
        NULL, NULL, NULL, '2026-09-23T16:40:00+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO ticket_work_log (id, ticket_id, author_member_id, log_type, action_detail, confirmed_cause, verification_result, applied_version, created_at)
VALUES ('cb2d7d23-b6ac-5985-9885-114c9f86a232', 'c07114c9-98cb-53a0-9dd9-b86ce0d3903c', (SELECT id FROM member WHERE login_id = 'file_owner'), 'RESOLVED', '문의자에게 폴더의 [접근 권한 요청]으로 업무 목적을 입력해 신청하도록 안내했고, 폴더 소유 부서 팀장 확인 뒤 읽기 권한을 반영했다.',
        '문의자에게 해당 폴더의 접근 권한이 없었다.', '권한을 반영한 뒤 문의자의 폴더 목록에 해당 폴더가 나타나고 파일을 열 수 있었다.', NULL, '2026-09-23T17:40:00+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO ticket_event (id, ticket_id, event_type, actor_type, actor_member_id, before_data, after_data, created_at)
VALUES ('3acd4ebb-3e55-53e1-a640-83671a85db7e', 'c07114c9-98cb-53a0-9dd9-b86ce0d3903c', 'STATUS_CHANGED', 'MEMBER', (SELECT id FROM member WHERE login_id = 'file_owner'),
        '{"state": "IN_PROGRESS"}'::jsonb, '{"resolutionRequest": {"confirmationOrigin": "SYNTHETIC_PRESET", "createdAt": "2026-09-23T08:40:00Z", "id": "bd7135e6-1d6a-5db5-a0bd-e1267afe2b4a", "inquiryId": "584b3361-0c52-5c9f-9f86-6e70b389c455", "problemId": "3e950bf9-440b-5ede-8dc4-d8304dc06e89", "requestRevision": 1, "respondedAt": null, "resultSummary": "문의자에게 폴더의 [접근 권한 요청]으로 업무 목적을 입력해 신청하도록 안내했고, 폴더 소유 부서 팀장 확인 뒤 읽기 권한을 반영했다.", "source": "WORK_RECORD", "sourceId": "cb2d7d23-b6ac-5985-9885-114c9f86a232", "sourceVersion": 1, "state": "PENDING", "ticketId": "c07114c9-98cb-53a0-9dd9-b86ce0d3903c"}, "state": "WAITING_CONFIRM"}'::jsonb, '2026-09-23T17:40:00+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO ticket_event (id, ticket_id, event_type, actor_type, actor_member_id, before_data, after_data, created_at)
VALUES ('e2b6cd97-228f-5dc0-8336-52c1b394cd55', 'c07114c9-98cb-53a0-9dd9-b86ce0d3903c', 'USER_CONFIRMED', 'MEMBER', (SELECT id FROM member WHERE login_id = 'requester1'),
        '{"resolutionRequest": {"confirmationOrigin": "SYNTHETIC_PRESET", "createdAt": "2026-09-23T08:40:00Z", "id": "bd7135e6-1d6a-5db5-a0bd-e1267afe2b4a", "inquiryId": "584b3361-0c52-5c9f-9f86-6e70b389c455", "problemId": "3e950bf9-440b-5ede-8dc4-d8304dc06e89", "requestRevision": 1, "respondedAt": null, "resultSummary": "문의자에게 폴더의 [접근 권한 요청]으로 업무 목적을 입력해 신청하도록 안내했고, 폴더 소유 부서 팀장 확인 뒤 읽기 권한을 반영했다.", "source": "WORK_RECORD", "sourceId": "cb2d7d23-b6ac-5985-9885-114c9f86a232", "sourceVersion": 1, "state": "PENDING", "ticketId": "c07114c9-98cb-53a0-9dd9-b86ce0d3903c"}}'::jsonb, '{"resolutionRequest": {"confirmationOrigin": "SYNTHETIC_PRESET", "createdAt": "2026-09-23T08:40:00Z", "id": "bd7135e6-1d6a-5db5-a0bd-e1267afe2b4a", "inquiryId": "584b3361-0c52-5c9f-9f86-6e70b389c455", "problemId": "3e950bf9-440b-5ede-8dc4-d8304dc06e89", "requestRevision": 2, "respondedAt": "2026-09-23T09:40:00Z", "resultSummary": "문의자에게 폴더의 [접근 권한 요청]으로 업무 목적을 입력해 신청하도록 안내했고, 폴더 소유 부서 팀장 확인 뒤 읽기 권한을 반영했다.", "source": "WORK_RECORD", "sourceId": "cb2d7d23-b6ac-5985-9885-114c9f86a232", "sourceVersion": 1, "state": "RESOLVED", "ticketId": "c07114c9-98cb-53a0-9dd9-b86ce0d3903c"}}'::jsonb, '2026-09-23T18:40:00+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO agent_run (id, inquiry_id, ticket_id, trigger_work_log_id, task, run_status, input_version, input_snapshot, model_version, prompt_version, schema_version, result, validation_result, started_at, completed_at, created_at, updated_at)
VALUES ('a7ee5c8e-6ee9-5ef1-9da2-336e6b592dfb', '584b3361-0c52-5c9f-9f86-6e70b389c455', 'c07114c9-98cb-53a0-9dd9-b86ce0d3903c', 'cb2d7d23-b6ac-5985-9885-114c9f86a232', 'CURATION',
        'SUCCEEDED', 2, '{"note": "합성 시드. 실제 AI 실행이 아니다.", "seed": true}'::jsonb, 'seed-synthetic', 'seed', 'hops-system-1',
        '{"caseProposal": {"reusable": true}, "seed": true}'::jsonb, '{"applied": true, "caseGenerationState": "SUCCEEDED", "resources": [{"id": "ba4a75a0-0558-5555-8ac8-f5b745fd0759", "type": "CASE"}], "seed": true}'::jsonb, '2026-09-23T17:40:05+09:00'::timestamptz, '2026-09-23T17:41:00+09:00'::timestamptz, '2026-09-23T17:40:05+09:00'::timestamptz, '2026-09-23T17:41:00+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO resolved_case (id, case_number, version_no, source_work_log_id, it_system_id, system_feature_id, case_type, title, problem_statement, preconditions, confirmed_facts, confirmed_cause, action_detail, verification_result, applicability, applied_version, access_level, origin_type, case_status, curator_run_id, relation_candidates, approved_by_member_id, approved_at, vector_status, graph_status, created_at, updated_at)
VALUES ('ba4a75a0-0558-5555-8ac8-f5b745fd0759', 'SEED-C-005', 1, 'cb2d7d23-b6ac-5985-9885-114c9f86a232', (SELECT id FROM it_system WHERE code = 'FILE_SHARING'), (SELECT f.id FROM system_feature f JOIN it_system s ON s.id = f.it_system_id WHERE s.code = 'FILE_SHARING' AND f.code = 'FILE_VIEW'), 'RESOLUTION',
        '파일 공유 폴더가 안 열림 - 폴더 접근 권한 없음', '링크로 연 파일 공유 폴더에서 접근 권한이 없다는 문구가 나오고, 폴더 목록에서는 그 폴더가 보이지 않는다.', '폴더가 삭제되거나 이동되지 않았다.', '{"facts": [{"knowledgeEvidenceIds": [], "sourceMessageIds": ["b96ae3df-36ba-59cf-9ed6-8c9027615852"], "text": "표시된 문구는 ''이 폴더에 접근할 권한이 없습니다''이다."}, {"knowledgeEvidenceIds": [], "sourceMessageIds": ["b96ae3df-36ba-59cf-9ed6-8c9027615852"], "text": "링크로 열었고 폴더 목록에는 그 폴더가 보이지 않는다."}], "knowledge": [], "limitations": ["권한 반영은 폴더 소유 부서 팀장 확인을 거치며 처리 목표는 2영업일이다.", "파일 삭제·이동이나 링크 만료는 다른 원인이다."], "sourceMessageIds": ["eed02bfa-c83b-5a64-bd75-0668b93248e1", "b96ae3df-36ba-59cf-9ed6-8c9027615852"]}'::jsonb,
        '문의자에게 해당 폴더의 접근 권한이 없었다.', '문의자에게 폴더의 [접근 권한 요청]으로 업무 목적을 입력해 신청하도록 안내했고, 폴더 소유 부서 팀장 확인 뒤 읽기 권한을 반영했다.', '권한을 반영한 뒤 문의자의 폴더 목록에 해당 폴더가 나타나고 파일을 열 수 있었다.', '''이 폴더에 접근할 권한이 없습니다''가 표시되고 폴더 목록에 해당 폴더가 없는 경우에 한한다. ''파일을 찾을 수 없습니다''나 ''공유 링크가 만료되었습니다''가 표시되면 적용하지 않는다.',
        NULL, 'STAFF', 'SYNTHETIC', 'APPROVED', 'a7ee5c8e-6ee9-5ef1-9da2-336e6b592dfb', '[{"evidenceIds": ["eed02bfa-c83b-5a64-bd75-0668b93248e1", "b96ae3df-36ba-59cf-9ed6-8c9027615852"], "relation": "APPLIES_TO", "targetId": "22d935d0-2ed8-58a7-a9b8-d73f717ed3c4"}]'::jsonb, (SELECT id FROM member WHERE login_id = 'file_owner'),
        '2026-09-24T15:40:00+09:00'::timestamptz, 'PENDING', 'PENDING', '2026-09-23T17:41:00+09:00'::timestamptz, '2026-09-24T15:40:00+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO knowledge_document (id, title, document_type, it_system_id, access_level, source_data, data_split, review_status, is_synthetic, created_at, updated_at)
VALUES ('ba4a75a0-0558-5555-8ac8-f5b745fd0759', '파일 공유 폴더가 안 열림 - 폴더 접근 권한 없음', 'CASE', (SELECT id FROM it_system WHERE code = 'FILE_SHARING'), 'STAFF', '{"cardPath": "docs/knowledge/cases/case-file-folder-no-access.yml", "caseId": "ba4a75a0-0558-5555-8ac8-f5b745fd0759", "caseNumber": "SEED-C-005", "origin": "SYNTHETIC"}'::jsonb, 'RAG', 'APPROVED', true,
        '2026-09-24T15:40:00+09:00'::timestamptz, '2026-09-24T15:40:00+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO knowledge_document_version (id, knowledge_document_id, version_no, content, content_digest, source_metadata, review_status, created_at)
VALUES ('e9ab4804-50eb-5bfb-91bf-1c3907e70211', 'ba4a75a0-0558-5555-8ac8-f5b745fd0759', 1,
        $kb$파일 공유 폴더가 안 열림 - 폴더 접근 권한 없음

링크로 연 파일 공유 폴더에서 접근 권한이 없다는 문구가 나오고, 폴더 목록에서는 그 폴더가 보이지 않는다.

문의자에게 폴더의 [접근 권한 요청]으로 업무 목적을 입력해 신청하도록 안내했고, 폴더 소유 부서 팀장 확인 뒤 읽기 권한을 반영했다.

권한을 반영한 뒤 문의자의 폴더 목록에 해당 폴더가 나타나고 파일을 열 수 있었다.

'이 폴더에 접근할 권한이 없습니다'가 표시되고 폴더 목록에 해당 폴더가 없는 경우에 한한다. '파일을 찾을 수 없습니다'나 '공유 링크가 만료되었습니다'가 표시되면 적용하지 않는다.

권한 반영은 폴더 소유 부서 팀장 확인을 거치며 처리 목표는 2영업일이다.
파일 삭제·이동이나 링크 만료는 다른 원인이다.$kb$,
        '08f7e23f7104d05459d53c4b8c584caea4384e6a6cbdfdedc08d367e75a0280b', '{"caseId": "ba4a75a0-0558-5555-8ac8-f5b745fd0759", "caseVersion": 1, "indexRequests": {"NEO4J": {"approvedCase": {"accessScope": "SYSTEM_STAFF", "action": "문의자에게 폴더의 [접근 권한 요청]으로 업무 목적을 입력해 신청하도록 안내했고, 폴더 소유 부서 팀장 확인 뒤 읽기 권한을 반영했다.", "applicability": "''이 폴더에 접근할 권한이 없습니다''가 표시되고 폴더 목록에 해당 폴더가 없는 경우에 한한다. ''파일을 찾을 수 없습니다''나 ''공유 링크가 만료되었습니다''가 표시되면 적용하지 않는다.", "appliesToSystemVersion": null, "approvedAt": "2026-09-24T06:40:00Z", "approvedBy": "a0000000-0000-7000-8000-000000000013", "caseType": "RESOLUTION", "confirmationOrigin": "SYNTHETIC_PRESET", "confirmedCause": "문의자에게 해당 폴더의 접근 권한이 없었다.", "entityLinks": [{"evidenceIds": ["eed02bfa-c83b-5a64-bd75-0668b93248e1", "b96ae3df-36ba-59cf-9ed6-8c9027615852"], "relation": "APPLIES_TO", "targetId": "22d935d0-2ed8-58a7-a9b8-d73f717ed3c4"}], "limitations": ["권한 반영은 폴더 소유 부서 팀장 확인을 거치며 처리 목표는 2영업일이다.", "파일 삭제·이동이나 링크 만료는 다른 원인이다."], "origin": "SYNTHETIC", "problemStatement": "링크로 연 파일 공유 폴더에서 접근 권한이 없다는 문구가 나오고, 폴더 목록에서는 그 폴더가 보이지 않는다.", "resolutionRequestId": "bd7135e6-1d6a-5db5-a0bd-e1267afe2b4a", "sourceMessageIds": ["eed02bfa-c83b-5a64-bd75-0668b93248e1", "b96ae3df-36ba-59cf-9ed6-8c9027615852"], "systemId": "22d935d0-2ed8-58a7-a9b8-d73f717ed3c4", "title": "파일 공유 폴더가 안 열림 - 폴더 접근 권한 없음", "verification": "권한을 반영한 뒤 문의자의 폴더 목록에 해당 폴더가 나타나고 파일을 열 수 있었다.", "workRecordId": "cb2d7d23-b6ac-5985-9885-114c9f86a232"}, "attempt": 1, "caseId": "ba4a75a0-0558-5555-8ac8-f5b745fd0759", "caseVersion": 1, "contentDigest": "7009ce711e55823f2942e6103c9f6896ac4be35ab80538583dd12e9889f67f71", "contractVersion": "hops-system-1", "jobId": "4773ffd9-b0e9-5114-abac-365f5703ffc1", "store": "NEO4J"}, "VECTOR": {"approvedCase": {"accessScope": "SYSTEM_STAFF", "action": "문의자에게 폴더의 [접근 권한 요청]으로 업무 목적을 입력해 신청하도록 안내했고, 폴더 소유 부서 팀장 확인 뒤 읽기 권한을 반영했다.", "applicability": "''이 폴더에 접근할 권한이 없습니다''가 표시되고 폴더 목록에 해당 폴더가 없는 경우에 한한다. ''파일을 찾을 수 없습니다''나 ''공유 링크가 만료되었습니다''가 표시되면 적용하지 않는다.", "appliesToSystemVersion": null, "approvedAt": "2026-09-24T06:40:00Z", "approvedBy": "a0000000-0000-7000-8000-000000000013", "caseType": "RESOLUTION", "confirmationOrigin": "SYNTHETIC_PRESET", "confirmedCause": "문의자에게 해당 폴더의 접근 권한이 없었다.", "entityLinks": [{"evidenceIds": ["eed02bfa-c83b-5a64-bd75-0668b93248e1", "b96ae3df-36ba-59cf-9ed6-8c9027615852"], "relation": "APPLIES_TO", "targetId": "22d935d0-2ed8-58a7-a9b8-d73f717ed3c4"}], "limitations": ["권한 반영은 폴더 소유 부서 팀장 확인을 거치며 처리 목표는 2영업일이다.", "파일 삭제·이동이나 링크 만료는 다른 원인이다."], "origin": "SYNTHETIC", "problemStatement": "링크로 연 파일 공유 폴더에서 접근 권한이 없다는 문구가 나오고, 폴더 목록에서는 그 폴더가 보이지 않는다.", "resolutionRequestId": "bd7135e6-1d6a-5db5-a0bd-e1267afe2b4a", "sourceMessageIds": ["eed02bfa-c83b-5a64-bd75-0668b93248e1", "b96ae3df-36ba-59cf-9ed6-8c9027615852"], "systemId": "22d935d0-2ed8-58a7-a9b8-d73f717ed3c4", "title": "파일 공유 폴더가 안 열림 - 폴더 접근 권한 없음", "verification": "권한을 반영한 뒤 문의자의 폴더 목록에 해당 폴더가 나타나고 파일을 열 수 있었다.", "workRecordId": "cb2d7d23-b6ac-5985-9885-114c9f86a232"}, "attempt": 1, "caseId": "ba4a75a0-0558-5555-8ac8-f5b745fd0759", "caseVersion": 1, "contentDigest": "a504ddf0780951b343a23b1f76fb05435281a4c8a62f9f0da935fbb19346857f", "contractVersion": "hops-system-1", "jobId": "34e20b2a-1a84-5e47-8e8c-e54795ca5486", "store": "VECTOR"}}}'::jsonb, 'APPROVED', '2026-09-24T15:40:00+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
UPDATE inquiry SET current_run_id = 'bf50de42-cbbe-53fb-9589-378fb3b871a6' WHERE id = '584b3361-0c52-5c9f-9f86-6e70b389c455' AND current_run_id IS NULL;

-- ===== case-file-link-expired (SEED-Q-006, SEED-T-006, SEED-C-006) =====
INSERT INTO inquiry (id, inquiry_number, requester_member_id, title, inquiry_status, version, current_run_id, result_revision, created_at, updated_at)
VALUES ('375715f6-b905-5863-9ac9-de14a16e24ed', 'SEED-Q-006', (SELECT id FROM member WHERE login_id = 'requester3'), '받은 파일 공유 링크가 열리지 않음',
        'RESOLVED', 2, NULL, 3, '2026-09-25T13:05:00+09:00'::timestamptz, '2026-09-26T13:05:00+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO message (id, inquiry_id, sequence_no, sender_type, content, revision, payload, message_status, created_at, updated_at)
VALUES ('475d7b4a-4016-5f53-95f0-8f2e64b57160', '375715f6-b905-5863-9ac9-de14a16e24ed', 1, 'USER', '며칠 전에 받은 파일 공유 링크를 열었더니 열리지 않아요.',
        1, '{"answerRunId": null, "contractVersion": "hops-system-1", "guide": null, "pendingTarget": false, "problemLinks": [{"problemId": "b1e06c59-76fb-5d88-98a3-ccbd37796cb3", "revision": 1, "spans": [{"end": 32, "start": 0}]}], "questionBundles": [], "reportedSystemId": "22d935d0-2ed8-58a7-a9b8-d73f717ed3c4", "requestId": "49ffbdd2-4097-52e9-ac99-cef003460446", "revisionRequestIds": {}, "runRequestIds": {}}'::jsonb, 'COMPLETED', '2026-09-25T13:05:00+09:00'::timestamptz, '2026-09-25T13:05:00+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO agent_run (id, inquiry_id, trigger_message_id, task, route, run_status, input_version, input_snapshot, model_version, prompt_version, schema_version, validation_result, started_at, completed_at, created_at, updated_at)
VALUES ('161bcc21-aab3-5eac-ae78-b981b05426cf', '375715f6-b905-5863-9ac9-de14a16e24ed', '475d7b4a-4016-5f53-95f0-8f2e64b57160', 'INQUIRY_ANALYSIS', 'SYMPTOM',
        'SUCCEEDED', 1, '{"answerMessageId": "0bfa6bac-e27b-53ea-a462-4dfeff1fc203", "note": "합성 시드. 실제 AI 실행이 아니다.", "seed": true}'::jsonb, 'seed-synthetic', 'seed', 'hops-system-1',
        '{"applied": true, "resources": [], "seed": true}'::jsonb, '2026-09-25T13:05:02+09:00'::timestamptz, '2026-09-25T13:05:20+09:00'::timestamptz, '2026-09-25T13:05:02+09:00'::timestamptz, '2026-09-25T13:05:20+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO message (id, inquiry_id, sequence_no, sender_type, content, revision, payload, message_status, created_at, updated_at)
VALUES ('0bfa6bac-e27b-53ea-a462-4dfeff1fc203', '375715f6-b905-5863-9ac9-de14a16e24ed', 2, 'AI', '링크가 열리지 않아 불편하시겠어요. 화면에 어떤 문구가 표시되나요? 그리고 링크를 받은 게 언제쯤인가요?',
        1, '{"answerRunId": "161bcc21-aab3-5eac-ae78-b981b05426cf", "contractVersion": "hops-system-1", "guide": null, "pendingTarget": false, "problemLinks": [], "questionBundles": [], "requestId": null}'::jsonb, 'COMPLETED', '2026-09-25T13:05:20+09:00'::timestamptz, '2026-09-25T13:05:20+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO message (id, inquiry_id, sequence_no, sender_type, content, revision, payload, message_status, created_at, updated_at)
VALUES ('137b954c-25ea-58ec-ae55-e954ede87296', '375715f6-b905-5863-9ac9-de14a16e24ed', 3, 'USER', '''공유 링크가 만료되었습니다''라고 나와요. 링크는 열흘쯤 전에 받았어요.',
        1, '{"answerRunId": null, "contractVersion": "hops-system-1", "guide": null, "pendingTarget": false, "problemLinks": [{"problemId": "b1e06c59-76fb-5d88-98a3-ccbd37796cb3", "revision": 1, "spans": [{"end": 40, "start": 0}]}], "questionBundles": [], "reportedSystemId": null, "requestId": "5b26a14e-c034-571c-bb2e-90097d926e9c", "revisionRequestIds": {}, "runRequestIds": {}}'::jsonb, 'COMPLETED', '2026-09-25T13:08:00+09:00'::timestamptz, '2026-09-25T13:08:00+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO agent_run (id, inquiry_id, trigger_message_id, task, route, run_status, input_version, input_snapshot, model_version, prompt_version, schema_version, validation_result, started_at, completed_at, created_at, updated_at)
VALUES ('5274a46e-64ed-53f7-9c7b-988e338be78d', '375715f6-b905-5863-9ac9-de14a16e24ed', '137b954c-25ea-58ec-ae55-e954ede87296', 'INQUIRY_ANALYSIS', 'SYMPTOM',
        'SUCCEEDED', 2, '{"answerMessageId": "74339b92-5fa5-5946-960f-6a44e1fda206", "note": "합성 시드. 실제 AI 실행이 아니다.", "seed": true}'::jsonb, 'seed-synthetic', 'seed', 'hops-system-1',
        '{"applied": true, "resources": [], "seed": true}'::jsonb, '2026-09-25T13:08:02+09:00'::timestamptz, '2026-09-25T13:08:40+09:00'::timestamptz, '2026-09-25T13:08:02+09:00'::timestamptz, '2026-09-25T13:08:40+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO message (id, inquiry_id, sequence_no, sender_type, content, revision, payload, message_status, created_at, updated_at)
VALUES ('74339b92-5fa5-5946-960f-6a44e1fda206', '375715f6-b905-5863-9ac9-de14a16e24ed', 4, 'AI', '알려 주셔서 감사합니다. 확인된 내용을 정리해 파일 공유 담당자에게 접수했습니다. 담당자가 확인한 뒤 결과를 이 대화로 알려 드리겠습니다.',
        1, '{"answerRunId": "5274a46e-64ed-53f7-9c7b-988e338be78d", "contractVersion": "hops-system-1", "guide": null, "pendingTarget": false, "problemLinks": [], "questionBundles": [], "requestId": null}'::jsonb, 'COMPLETED', '2026-09-25T13:08:40+09:00'::timestamptz, '2026-09-25T13:08:40+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO inquiry_problem (id, inquiry_id, problem_no, title, detail, problem_status, clarify_count, creation_outcome, creation_input_version, agent_run_id, created_at, updated_at)
VALUES ('b1e06c59-76fb-5d88-98a3-ccbd37796cb3', '375715f6-b905-5863-9ac9-de14a16e24ed', 1, '받은 공유 링크가 만료되었다고 표시됨', '열흘쯤 전에 받은 파일 공유 링크가 열리지 않고 ''공유 링크가 만료되었습니다''가 표시된다.',
        'TICKET_CREATED', 1, 'STORED', 2, '5274a46e-64ed-53f7-9c7b-988e338be78d', '2026-09-25T13:08:40+09:00'::timestamptz, '2026-09-25T13:08:40+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO standard_request (id, inquiry_problem_id, inquiry_version, agent_run_id, intent, work_type, reported_system_id, reported_feature_id, responsible_system_id, system_candidates, summary, confirmed_facts, assumptions, missing_items, type_detail, related_knowledge, created_at)
VALUES ('221fb6a4-1a13-5418-9b5f-8ec3dc33045a', 'b1e06c59-76fb-5d88-98a3-ccbd37796cb3', 2, '5274a46e-64ed-53f7-9c7b-988e338be78d', 'OBSERVED_SYMPTOM', 'INVESTIGATION',
        (SELECT id FROM it_system WHERE code = 'FILE_SHARING'), (SELECT f.id FROM system_feature f JOIN it_system s ON s.id = f.it_system_id WHERE s.code = 'FILE_SHARING' AND f.code = 'FILE_SHARE'), (SELECT id FROM it_system WHERE code = 'FILE_SHARING'), '[{"evidenceIds": ["475d7b4a-4016-5f53-95f0-8f2e64b57160"], "systemId": "22d935d0-2ed8-58a7-a9b8-d73f717ed3c4"}]'::jsonb, '열흘쯤 전에 받은 파일 공유 링크가 열리지 않고 ''공유 링크가 만료되었습니다''가 표시된다.',
        '[{"knowledgeEvidenceIds": [], "sourceMessageIds": ["137b954c-25ea-58ec-ae55-e954ede87296"], "text": "표시된 문구는 ''공유 링크가 만료되었습니다''이다."}, {"knowledgeEvidenceIds": [], "sourceMessageIds": ["137b954c-25ea-58ec-ae55-e954ede87296"], "text": "링크는 열흘쯤 전에 받았다."}]'::jsonb, '[]'::jsonb, '[]'::jsonb, '{"acceptanceCriteria": [], "actualBehavior": "''공유 링크가 만료되었습니다''가 표시됨", "expectedBehavior": "파일이 열림", "occurredAt": null, "taskOrScreen": "받은 공유 링크로 파일을 열려 함"}'::jsonb, '[]'::jsonb, '2026-09-25T13:08:40+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO ticket (id, ticket_number, inquiry_id, inquiry_problem_id, standard_request_id, title, detail, work_type, ticket_status, responsible_system_id, assignee_member_id, confirm_requested_at, resolved_at, version, created_at, updated_at)
VALUES ('d52dabbc-7177-557f-b426-bde73c1d915b', 'SEED-T-006', '375715f6-b905-5863-9ac9-de14a16e24ed', 'b1e06c59-76fb-5d88-98a3-ccbd37796cb3', '221fb6a4-1a13-5418-9b5f-8ec3dc33045a',
        '받은 공유 링크가 만료되었다고 표시됨', '문의자가 열흘쯤 전에 받은 공유 링크를 열었더니 공유 링크가 만료되었다는 문구가 나온다.', 'INVESTIGATION', 'RESOLVED', (SELECT id FROM it_system WHERE code = 'FILE_SHARING'), (SELECT id FROM member WHERE login_id = 'file_owner'),
        '2026-09-25T15:05:00+09:00'::timestamptz, '2026-09-25T16:05:00+09:00'::timestamptz, 3, '2026-09-25T13:08:40+09:00'::timestamptz, '2026-09-25T16:05:00+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO ticket_assignment (id, ticket_id, it_system_id, assignee_member_id, source, reason, evidence, assigned_at, created_at, updated_at)
VALUES ('fb3ee6d6-ef17-57c5-84ea-074eda2b2076', 'd52dabbc-7177-557f-b426-bde73c1d915b', (SELECT id FROM it_system WHERE code = 'FILE_SHARING'), (SELECT id FROM member WHERE login_id = 'file_owner'), 'AUTO',
        '책임 시스템의 현재 주담당자에게 자동 배정(합성 시드)', '{"messageIds": ["475d7b4a-4016-5f53-95f0-8f2e64b57160", "137b954c-25ea-58ec-ae55-e954ede87296"], "note": null}'::jsonb,
        '2026-09-25T13:08:40+09:00'::timestamptz, '2026-09-25T13:08:40+09:00'::timestamptz, '2026-09-25T13:08:40+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO ticket_work_log (id, ticket_id, author_member_id, log_type, action_detail, confirmed_cause, verification_result, applied_version, created_at)
VALUES ('76b6d13b-49ee-58dd-a165-e41b8a73d5cb', 'd52dabbc-7177-557f-b426-bde73c1d915b', (SELECT id FROM member WHERE login_id = 'file_owner'), 'PROGRESS', '해당 공유의 설정을 확인했다. 링크의 만료일이 지난 상태이며 만료일은 기본값(7일)으로 지정되어 있었다.',
        NULL, NULL, NULL, '2026-09-25T14:05:00+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO ticket_work_log (id, ticket_id, author_member_id, log_type, action_detail, confirmed_cause, verification_result, applied_version, created_at)
VALUES ('1ab4e4f9-4940-5d31-8d5a-37da90291f3b', 'd52dabbc-7177-557f-b426-bde73c1d915b', (SELECT id FROM member WHERE login_id = 'file_owner'), 'RESOLVED', '공유한 사람에게 새 링크를 만들어 전달해 달라고 요청했고, 새 링크는 필요한 기간에 맞춰 만료일(최대 30일)을 지정해 만들었다.',
        '공유 링크의 만료일(기본 7일)이 지나 링크가 만료되었다.', '새 링크로 문의자가 파일을 열 수 있었다.', NULL, '2026-09-25T15:05:00+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO ticket_event (id, ticket_id, event_type, actor_type, actor_member_id, before_data, after_data, created_at)
VALUES ('8acefc25-553c-570f-ab20-d2a115fd6b37', 'd52dabbc-7177-557f-b426-bde73c1d915b', 'STATUS_CHANGED', 'MEMBER', (SELECT id FROM member WHERE login_id = 'file_owner'),
        '{"state": "IN_PROGRESS"}'::jsonb, '{"resolutionRequest": {"confirmationOrigin": "SYNTHETIC_PRESET", "createdAt": "2026-09-25T06:05:00Z", "id": "2ff8c3c6-d0c2-5cd8-b522-77d53a4f2834", "inquiryId": "375715f6-b905-5863-9ac9-de14a16e24ed", "problemId": "b1e06c59-76fb-5d88-98a3-ccbd37796cb3", "requestRevision": 1, "respondedAt": null, "resultSummary": "공유한 사람에게 새 링크를 만들어 전달해 달라고 요청했고, 새 링크는 필요한 기간에 맞춰 만료일(최대 30일)을 지정해 만들었다.", "source": "WORK_RECORD", "sourceId": "1ab4e4f9-4940-5d31-8d5a-37da90291f3b", "sourceVersion": 1, "state": "PENDING", "ticketId": "d52dabbc-7177-557f-b426-bde73c1d915b"}, "state": "WAITING_CONFIRM"}'::jsonb, '2026-09-25T15:05:00+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO ticket_event (id, ticket_id, event_type, actor_type, actor_member_id, before_data, after_data, created_at)
VALUES ('ac3a710b-0087-5b40-abc6-980595a3cbd2', 'd52dabbc-7177-557f-b426-bde73c1d915b', 'USER_CONFIRMED', 'MEMBER', (SELECT id FROM member WHERE login_id = 'requester3'),
        '{"resolutionRequest": {"confirmationOrigin": "SYNTHETIC_PRESET", "createdAt": "2026-09-25T06:05:00Z", "id": "2ff8c3c6-d0c2-5cd8-b522-77d53a4f2834", "inquiryId": "375715f6-b905-5863-9ac9-de14a16e24ed", "problemId": "b1e06c59-76fb-5d88-98a3-ccbd37796cb3", "requestRevision": 1, "respondedAt": null, "resultSummary": "공유한 사람에게 새 링크를 만들어 전달해 달라고 요청했고, 새 링크는 필요한 기간에 맞춰 만료일(최대 30일)을 지정해 만들었다.", "source": "WORK_RECORD", "sourceId": "1ab4e4f9-4940-5d31-8d5a-37da90291f3b", "sourceVersion": 1, "state": "PENDING", "ticketId": "d52dabbc-7177-557f-b426-bde73c1d915b"}}'::jsonb, '{"resolutionRequest": {"confirmationOrigin": "SYNTHETIC_PRESET", "createdAt": "2026-09-25T06:05:00Z", "id": "2ff8c3c6-d0c2-5cd8-b522-77d53a4f2834", "inquiryId": "375715f6-b905-5863-9ac9-de14a16e24ed", "problemId": "b1e06c59-76fb-5d88-98a3-ccbd37796cb3", "requestRevision": 2, "respondedAt": "2026-09-25T07:05:00Z", "resultSummary": "공유한 사람에게 새 링크를 만들어 전달해 달라고 요청했고, 새 링크는 필요한 기간에 맞춰 만료일(최대 30일)을 지정해 만들었다.", "source": "WORK_RECORD", "sourceId": "1ab4e4f9-4940-5d31-8d5a-37da90291f3b", "sourceVersion": 1, "state": "RESOLVED", "ticketId": "d52dabbc-7177-557f-b426-bde73c1d915b"}}'::jsonb, '2026-09-25T16:05:00+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO agent_run (id, inquiry_id, ticket_id, trigger_work_log_id, task, run_status, input_version, input_snapshot, model_version, prompt_version, schema_version, result, validation_result, started_at, completed_at, created_at, updated_at)
VALUES ('0d50e803-84b5-5c7d-bd3c-7165d2471a46', '375715f6-b905-5863-9ac9-de14a16e24ed', 'd52dabbc-7177-557f-b426-bde73c1d915b', '1ab4e4f9-4940-5d31-8d5a-37da90291f3b', 'CURATION',
        'SUCCEEDED', 2, '{"note": "합성 시드. 실제 AI 실행이 아니다.", "seed": true}'::jsonb, 'seed-synthetic', 'seed', 'hops-system-1',
        '{"caseProposal": {"reusable": true}, "seed": true}'::jsonb, '{"applied": true, "caseGenerationState": "SUCCEEDED", "resources": [{"id": "91c8709e-7b9e-54f1-874c-3abdcb1dd926", "type": "CASE"}], "seed": true}'::jsonb, '2026-09-25T15:05:05+09:00'::timestamptz, '2026-09-25T15:06:00+09:00'::timestamptz, '2026-09-25T15:05:05+09:00'::timestamptz, '2026-09-25T15:06:00+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO resolved_case (id, case_number, version_no, source_work_log_id, it_system_id, system_feature_id, case_type, title, problem_statement, preconditions, confirmed_facts, confirmed_cause, action_detail, verification_result, applicability, applied_version, access_level, origin_type, case_status, curator_run_id, relation_candidates, approved_by_member_id, approved_at, vector_status, graph_status, created_at, updated_at)
VALUES ('91c8709e-7b9e-54f1-874c-3abdcb1dd926', 'SEED-C-006', 1, '1ab4e4f9-4940-5d31-8d5a-37da90291f3b', (SELECT id FROM it_system WHERE code = 'FILE_SHARING'), (SELECT f.id FROM system_feature f JOIN it_system s ON s.id = f.it_system_id WHERE s.code = 'FILE_SHARING' AND f.code = 'FILE_SHARE'), 'RESOLUTION',
        '받은 공유 링크가 안 열림 - 링크 만료일(기본 7일) 경과', '받은 파일 공유 링크가 열리지 않고 공유 링크가 만료되었다는 문구가 표시된다.', '공유 링크의 만료일이 기본값(7일)으로 지정되어 있다.', '{"facts": [{"knowledgeEvidenceIds": [], "sourceMessageIds": ["137b954c-25ea-58ec-ae55-e954ede87296"], "text": "표시된 문구는 ''공유 링크가 만료되었습니다''이다."}, {"knowledgeEvidenceIds": [], "sourceMessageIds": ["137b954c-25ea-58ec-ae55-e954ede87296"], "text": "링크는 열흘쯤 전에 받았다."}], "knowledge": [], "limitations": ["만료일은 최대 30일까지 지정할 수 있다.", "새 링크는 공유한 사람이 만든다."], "sourceMessageIds": ["475d7b4a-4016-5f53-95f0-8f2e64b57160", "137b954c-25ea-58ec-ae55-e954ede87296"]}'::jsonb,
        '공유 링크의 만료일(기본 7일)이 지나 링크가 만료되었다.', '공유한 사람에게 새 링크를 만들어 전달해 달라고 요청했고, 새 링크는 필요한 기간에 맞춰 만료일(최대 30일)을 지정해 만들었다.', '새 링크로 문의자가 파일을 열 수 있었다.', '''공유 링크가 만료되었습니다''가 표시되고 링크를 받은 시점이 만료일을 지난 경우에 한한다. ''이 폴더에 접근할 권한이 없습니다''나 ''파일을 찾을 수 없습니다''가 표시되면 적용하지 않는다.',
        NULL, 'STAFF', 'SYNTHETIC', 'APPROVED', '0d50e803-84b5-5c7d-bd3c-7165d2471a46', '[{"evidenceIds": ["475d7b4a-4016-5f53-95f0-8f2e64b57160", "137b954c-25ea-58ec-ae55-e954ede87296"], "relation": "APPLIES_TO", "targetId": "22d935d0-2ed8-58a7-a9b8-d73f717ed3c4"}]'::jsonb, (SELECT id FROM member WHERE login_id = 'file_owner'),
        '2026-09-26T13:05:00+09:00'::timestamptz, 'PENDING', 'PENDING', '2026-09-25T15:06:00+09:00'::timestamptz, '2026-09-26T13:05:00+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO knowledge_document (id, title, document_type, it_system_id, access_level, source_data, data_split, review_status, is_synthetic, created_at, updated_at)
VALUES ('91c8709e-7b9e-54f1-874c-3abdcb1dd926', '받은 공유 링크가 안 열림 - 링크 만료일(기본 7일) 경과', 'CASE', (SELECT id FROM it_system WHERE code = 'FILE_SHARING'), 'STAFF', '{"cardPath": "docs/knowledge/cases/case-file-link-expired.yml", "caseId": "91c8709e-7b9e-54f1-874c-3abdcb1dd926", "caseNumber": "SEED-C-006", "origin": "SYNTHETIC"}'::jsonb, 'RAG', 'APPROVED', true,
        '2026-09-26T13:05:00+09:00'::timestamptz, '2026-09-26T13:05:00+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
INSERT INTO knowledge_document_version (id, knowledge_document_id, version_no, content, content_digest, source_metadata, review_status, created_at)
VALUES ('71d20666-0599-5a0f-a645-da4b13cc98f8', '91c8709e-7b9e-54f1-874c-3abdcb1dd926', 1,
        $kb$받은 공유 링크가 안 열림 - 링크 만료일(기본 7일) 경과

받은 파일 공유 링크가 열리지 않고 공유 링크가 만료되었다는 문구가 표시된다.

공유한 사람에게 새 링크를 만들어 전달해 달라고 요청했고, 새 링크는 필요한 기간에 맞춰 만료일(최대 30일)을 지정해 만들었다.

새 링크로 문의자가 파일을 열 수 있었다.

'공유 링크가 만료되었습니다'가 표시되고 링크를 받은 시점이 만료일을 지난 경우에 한한다. '이 폴더에 접근할 권한이 없습니다'나 '파일을 찾을 수 없습니다'가 표시되면 적용하지 않는다.

만료일은 최대 30일까지 지정할 수 있다.
새 링크는 공유한 사람이 만든다.$kb$,
        'f962e171bc121e457a4aaac112daaf2f66a0f8968b2e8379be05433960e0e2d2', '{"caseId": "91c8709e-7b9e-54f1-874c-3abdcb1dd926", "caseVersion": 1, "indexRequests": {"NEO4J": {"approvedCase": {"accessScope": "SYSTEM_STAFF", "action": "공유한 사람에게 새 링크를 만들어 전달해 달라고 요청했고, 새 링크는 필요한 기간에 맞춰 만료일(최대 30일)을 지정해 만들었다.", "applicability": "''공유 링크가 만료되었습니다''가 표시되고 링크를 받은 시점이 만료일을 지난 경우에 한한다. ''이 폴더에 접근할 권한이 없습니다''나 ''파일을 찾을 수 없습니다''가 표시되면 적용하지 않는다.", "appliesToSystemVersion": null, "approvedAt": "2026-09-26T04:05:00Z", "approvedBy": "a0000000-0000-7000-8000-000000000013", "caseType": "RESOLUTION", "confirmationOrigin": "SYNTHETIC_PRESET", "confirmedCause": "공유 링크의 만료일(기본 7일)이 지나 링크가 만료되었다.", "entityLinks": [{"evidenceIds": ["475d7b4a-4016-5f53-95f0-8f2e64b57160", "137b954c-25ea-58ec-ae55-e954ede87296"], "relation": "APPLIES_TO", "targetId": "22d935d0-2ed8-58a7-a9b8-d73f717ed3c4"}], "limitations": ["만료일은 최대 30일까지 지정할 수 있다.", "새 링크는 공유한 사람이 만든다."], "origin": "SYNTHETIC", "problemStatement": "받은 파일 공유 링크가 열리지 않고 공유 링크가 만료되었다는 문구가 표시된다.", "resolutionRequestId": "2ff8c3c6-d0c2-5cd8-b522-77d53a4f2834", "sourceMessageIds": ["475d7b4a-4016-5f53-95f0-8f2e64b57160", "137b954c-25ea-58ec-ae55-e954ede87296"], "systemId": "22d935d0-2ed8-58a7-a9b8-d73f717ed3c4", "title": "받은 공유 링크가 안 열림 - 링크 만료일(기본 7일) 경과", "verification": "새 링크로 문의자가 파일을 열 수 있었다.", "workRecordId": "1ab4e4f9-4940-5d31-8d5a-37da90291f3b"}, "attempt": 1, "caseId": "91c8709e-7b9e-54f1-874c-3abdcb1dd926", "caseVersion": 1, "contentDigest": "0a5b199b1cf786f7afe04eb9eccf712fe37d680b37c7939261be605f1a1d2c54", "contractVersion": "hops-system-1", "jobId": "556bea74-c17c-5d73-9f47-42c4302dfa57", "store": "NEO4J"}, "VECTOR": {"approvedCase": {"accessScope": "SYSTEM_STAFF", "action": "공유한 사람에게 새 링크를 만들어 전달해 달라고 요청했고, 새 링크는 필요한 기간에 맞춰 만료일(최대 30일)을 지정해 만들었다.", "applicability": "''공유 링크가 만료되었습니다''가 표시되고 링크를 받은 시점이 만료일을 지난 경우에 한한다. ''이 폴더에 접근할 권한이 없습니다''나 ''파일을 찾을 수 없습니다''가 표시되면 적용하지 않는다.", "appliesToSystemVersion": null, "approvedAt": "2026-09-26T04:05:00Z", "approvedBy": "a0000000-0000-7000-8000-000000000013", "caseType": "RESOLUTION", "confirmationOrigin": "SYNTHETIC_PRESET", "confirmedCause": "공유 링크의 만료일(기본 7일)이 지나 링크가 만료되었다.", "entityLinks": [{"evidenceIds": ["475d7b4a-4016-5f53-95f0-8f2e64b57160", "137b954c-25ea-58ec-ae55-e954ede87296"], "relation": "APPLIES_TO", "targetId": "22d935d0-2ed8-58a7-a9b8-d73f717ed3c4"}], "limitations": ["만료일은 최대 30일까지 지정할 수 있다.", "새 링크는 공유한 사람이 만든다."], "origin": "SYNTHETIC", "problemStatement": "받은 파일 공유 링크가 열리지 않고 공유 링크가 만료되었다는 문구가 표시된다.", "resolutionRequestId": "2ff8c3c6-d0c2-5cd8-b522-77d53a4f2834", "sourceMessageIds": ["475d7b4a-4016-5f53-95f0-8f2e64b57160", "137b954c-25ea-58ec-ae55-e954ede87296"], "systemId": "22d935d0-2ed8-58a7-a9b8-d73f717ed3c4", "title": "받은 공유 링크가 안 열림 - 링크 만료일(기본 7일) 경과", "verification": "새 링크로 문의자가 파일을 열 수 있었다.", "workRecordId": "1ab4e4f9-4940-5d31-8d5a-37da90291f3b"}, "attempt": 1, "caseId": "91c8709e-7b9e-54f1-874c-3abdcb1dd926", "caseVersion": 1, "contentDigest": "e6772019289cc62cf03c0cbf5157172edbd62ab1e6382fb941b38c5cf2263bb6", "contractVersion": "hops-system-1", "jobId": "8afda241-a2e9-51b1-bb09-08104b1f11e4", "store": "VECTOR"}}}'::jsonb, 'APPROVED', '2026-09-26T13:05:00+09:00'::timestamptz)
ON CONFLICT DO NOTHING;
UPDATE inquiry SET current_run_id = '5274a46e-64ed-53f7-9c7b-988e338be78d' WHERE id = '375715f6-b905-5863-9ac9-de14a16e24ed' AND current_run_id IS NULL;
