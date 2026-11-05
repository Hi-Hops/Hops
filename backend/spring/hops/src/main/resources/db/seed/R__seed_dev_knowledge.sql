-- ⚠️ 로컬 개발용 가상 데이터 (실제 회사·실제 문서가 아님)
-- ⚠️ 자동 생성 파일이다. 직접 고치지 않는다. 원본: docs/knowledge/corpus/ (Markdown)
--    다시 만들기: uv run scripts/knowledge/build_knowledge_seed.py
--    어긋남 검사:  uv run scripts/knowledge/build_knowledge_seed.py --check
-- 값의 정본은 docs/knowledge/VIRTUAL_COMPANY.md 6절(지식 문서 카탈로그)이다.
-- db 프로필에서만 실행된다 (application-db.properties의 spring.flyway.locations). 테스트 DB·운영에는 들어가지 않는다.
--
-- R__ 파일 실행 순서: department_member → it_system → knowledge (이름 순). 시스템 문서는 it_system 뒤에 들어간다.
--
-- ID는 documentKey에서 정해지는 고정값이다: uuid5(NAMESPACE_URL, "hops-knowledge.example/" + documentKey).
-- 버전 ID는 documentKey 뒤에 "/v{version}"을 붙여 만든다. 팀원 모두의 PC에서 같은 문서 ID가 된다.
--
-- 모든 INSERT는 ON CONFLICT DO NOTHING이라 이미 있는 행은 바꾸지 않는다. 문서 내용을 고치면:
--   1) 원본의 version을 올리고 이 파일을 다시 만든다. 그러면 새 버전 행이 추가된다.
--   2) version을 올리지 않고 고치면 이미 적재된 DB에는 반영되지 않으므로, 팀원은 DB를 새로 만들어 적재한다.
--      (backend/spring/hops/README.md "처음 상태로 되돌리기")
--
-- 문서 본문(content)은 front matter를 뺀 Markdown이고, content_digest는 그 UTF-8 바이트의 SHA-256이다.

-- guide-ea-approval-status (v1)
INSERT INTO knowledge_document (id, title, document_type, it_system_id, access_level, source_data, data_split, review_status, is_synthetic)
VALUES ('bc670240-53a6-54a5-9a07-ca01e362be69', '전자결재 결재 상태와 결재선 탭 보는 법', 'EMPLOYEE_GUIDE',
        (SELECT id FROM it_system WHERE code = 'ELECTRONIC_APPROVAL'), 'EMPLOYEE',
        '{"corpusPath": "docs/knowledge/corpus/guides/guide-ea-approval-status.md", "documentKey": "guide-ea-approval-status", "origin": "SYNTHETIC"}'::jsonb, 'RAG', 'APPROVED', true)
ON CONFLICT DO NOTHING;
INSERT INTO knowledge_document_version (id, knowledge_document_id, version_no, content, content_digest, source_metadata, review_status)
VALUES ('bf36cb48-7ca0-5c68-97be-8826f13751c6', 'bc670240-53a6-54a5-9a07-ca01e362be69', 1,
        $kb$## 목적

전자결재 문서의 결재가 어디까지 진행됐는지 확인하는 방법과, 화면에 나오는 상태의 의미를 안내합니다.

## 전제

- 문서를 열람할 권한이 있어야 합니다. 내가 기안했거나 결재선·참조에 포함된 문서가 해당합니다.

## 절차

1. 문서함이나 기안함에서 문서를 열어 상세 화면으로 이동합니다.
2. `결재선` 탭을 엽니다.
3. 문서 상태와 결재자별 단계 상태를 확인합니다.

## 확인할 수 있는 결과

문서 상태는 다음 중 하나입니다.

| 상태 | 코드 | 의미 |
|---|---|---|
| 임시 저장 | `DRAFT` | 상신하지 않고 저장만 한 상태 |
| 상신 | `SUBMITTED` | 상신했고 아직 아무도 처리하지 않은 상태 |
| 결재 중 | `IN_PROGRESS` | 한 단계 이상 처리되었고 결재가 진행 중인 상태 |
| 승인 | `APPROVED` | 마지막 결재자까지 승인한 상태 |
| 반려 | `REJECTED` | 반려된 상태. 사유가 함께 표시됩니다 |
| 회수 | `WITHDRAWN` | 기안자가 회수한 상태 |

결재선의 단계 상태는 "대기"(`WAITING`), "승인"(`APPROVED`), "반려"(`REJECTED`)입니다.

상태는 `임시 저장 → 상신 → 결재 중 → 승인 또는 반려` 순서로 바뀝니다. "상신"이나 "결재 중"(첫 결재자가 처리하기 전)에서는 기안자가 회수하면 "회수"가 됩니다.

## 적용 조건과 한계

- 이 안내는 상태를 읽는 방법만 다룹니다. 결재 자체를 대신 처리하는 방법은 포함하지 않습니다.
- 열람 권한이 없는 문서의 결재선은 볼 수 없습니다.
$kb$,
        '18bd99147bdd898d8d3df3064b447d48fa6e7732af28fb2812eab597f1b10d1f',
        '{"appliesToSystemVersion": null, "documentKey": "guide-ea-approval-status", "origin": "SYNTHETIC", "source": {"reference": null, "type": "AUTHORED"}}'::jsonb, 'APPROVED')
ON CONFLICT DO NOTHING;

-- guide-ea-attachment-open (v1)
INSERT INTO knowledge_document (id, title, document_type, it_system_id, access_level, source_data, data_split, review_status, is_synthetic)
VALUES ('e796e500-ea3b-5f28-98a0-cfeb6f95a568', '전자결재 첨부 파일 열기와 내려받기', 'EMPLOYEE_GUIDE',
        (SELECT id FROM it_system WHERE code = 'ELECTRONIC_APPROVAL'), 'EMPLOYEE',
        '{"corpusPath": "docs/knowledge/corpus/guides/guide-ea-attachment-open.md", "documentKey": "guide-ea-attachment-open", "origin": "SYNTHETIC"}'::jsonb, 'RAG', 'APPROVED', true)
ON CONFLICT DO NOTHING;
INSERT INTO knowledge_document_version (id, knowledge_document_id, version_no, content, content_digest, source_metadata, review_status)
VALUES ('58fa4162-7fd5-51e6-878a-28fd27b9fb6b', 'e796e500-ea3b-5f28-98a0-cfeb6f95a568', 1,
        $kb$## 목적

전자결재 문서에 붙은 첨부 파일을 열어 보거나 내려받는 방법을 안내합니다.

## 전제

- 첨부 파일은 해당 문서를 열람할 권한이 있어야 볼 수 있습니다. 내가 기안했거나 결재선·참조에 포함된 문서가 해당합니다.

## 절차

1. 문서 상세 화면을 엽니다.
2. `첨부 파일` 영역에서 파일을 찾습니다.
3. 미리보고 싶으면 [열기]를, 내 컴퓨터에 저장하려면 [내려받기]를 누릅니다.

## 확인할 수 있는 결과

- [열기]는 pdf, png, jpg, txt 파일을 화면에서 미리 보여 줍니다.
- 그 외 형식의 파일은 [열기]가 눌리지 않고 [내려받기]만 사용할 수 있습니다.
- 첨부 파일 열람 권한이 없으면 "첨부 파일 열람 권한이 없습니다"가 표시됩니다.
- 파일을 열 수 없으면 "첨부 파일을 열 수 없습니다"가 표시됩니다.

## 적용 조건과 한계

- 내려받기는 한 번에 한 파일씩만 할 수 있습니다. 여러 파일을 한꺼번에 내려받는 기능은 지원하지 않습니다.
- 첨부 파일의 크기·개수·형식 제한은 첨부 제한 정책을 따릅니다.
- 전자결재의 첨부 저장소는 파일 공유와 자동으로 연결되지 않습니다.
$kb$,
        '21d7333443ecc285cd0ddbe47e254e0ca783bfdf537265ac19814e891557eaea',
        '{"appliesToSystemVersion": null, "documentKey": "guide-ea-attachment-open", "origin": "SYNTHETIC", "source": {"reference": null, "type": "AUTHORED"}}'::jsonb, 'APPROVED')
ON CONFLICT DO NOTHING;

-- guide-ea-document-create (v1)
INSERT INTO knowledge_document (id, title, document_type, it_system_id, access_level, source_data, data_split, review_status, is_synthetic)
VALUES ('ac6cd4ba-e868-58e4-943f-d67fea73b0e7', '전자결재에서 새 기안 작성하고 상신하기', 'EMPLOYEE_GUIDE',
        (SELECT id FROM it_system WHERE code = 'ELECTRONIC_APPROVAL'), 'EMPLOYEE',
        '{"corpusPath": "docs/knowledge/corpus/guides/guide-ea-document-create.md", "documentKey": "guide-ea-document-create", "origin": "SYNTHETIC"}'::jsonb, 'RAG', 'APPROVED', true)
ON CONFLICT DO NOTHING;
INSERT INTO knowledge_document_version (id, knowledge_document_id, version_no, content, content_digest, source_metadata, review_status)
VALUES ('b99e5dfe-858b-5f5b-b5fb-0f490528938c', 'ac6cd4ba-e868-58e4-943f-d67fea73b0e7', 1,
        $kb$## 목적

전자결재에서 결재 문서를 새로 작성해 상신하는 방법을 안내합니다. 작성하다가 멈춰야 할 때의 임시 저장과, 상신 뒤의 회수도 함께 다룹니다.

## 전제

- 공통 로그인(SSO)으로 로그인한 상태입니다.
- 결재자로 지정할 사람이 정해져 있어야 상신할 수 있습니다.

## 절차

1. `전자결재 > 새 기안`을 엽니다.
2. 제목을 입력합니다. 제목은 필수이며 100자 이내입니다.
3. 본문을 입력합니다. 본문은 필수입니다.
4. 필요하면 첨부 파일을 추가합니다. 첨부는 선택입니다.
5. 결재선에 결재자를 1명 이상 지정합니다.
6. 바로 상신하지 않을 때는 [임시 저장]을 누릅니다. 필수 항목을 다 채우지 않아도 저장되며, 저장한 문서는 `기안함 > 임시 저장`에서 다시 열 수 있습니다.
7. 상신하려면 [상신]을 누릅니다. 이때 필수 항목을 검사합니다.

## 확인할 수 있는 결과

- 상신하면 문서 상태가 "상신"(`SUBMITTED`)이 됩니다. 결재 진행 상황은 문서 상세의 `결재선` 탭에서 확인합니다.
- 필수 항목이 비어 있으면 "필수 항목을 입력해 주세요"가 표시됩니다.
- 결재선이 비어 있으면 "결재선을 1명 이상 지정해 주세요"가 표시됩니다.
- 저장하지 못하면 "저장하지 못했습니다. 잠시 후 다시 시도해 주세요"가 표시됩니다.

## 적용 조건과 한계

- 상신한 문서는 수정할 수 없습니다.
- 첫 결재자가 문서를 처리하기 전까지만 [회수]할 수 있습니다. 한 단계라도 처리된 뒤에는 회수할 수 없습니다.
- 임시 저장 문서는 `문서함 > 전체 문서`가 아니라 `기안함 > 임시 저장`에 나타납니다.
$kb$,
        'c395c6adc22ce28e2a1c24118dcbf81638223ed8b570408848542b587743fd1b',
        '{"appliesToSystemVersion": null, "documentKey": "guide-ea-document-create", "origin": "SYNTHETIC", "source": {"reference": null, "type": "AUTHORED"}}'::jsonb, 'APPROVED')
ON CONFLICT DO NOTHING;

-- guide-ea-document-search (v1)
INSERT INTO knowledge_document (id, title, document_type, it_system_id, access_level, source_data, data_split, review_status, is_synthetic)
VALUES ('ce09363e-9aa8-5557-a49f-90ba9a311a24', '전자결재에서 지난 문서 찾기', 'EMPLOYEE_GUIDE',
        (SELECT id FROM it_system WHERE code = 'ELECTRONIC_APPROVAL'), 'EMPLOYEE',
        '{"corpusPath": "docs/knowledge/corpus/guides/guide-ea-document-search.md", "documentKey": "guide-ea-document-search", "origin": "SYNTHETIC"}'::jsonb, 'RAG', 'APPROVED', true)
ON CONFLICT DO NOTHING;
INSERT INTO knowledge_document_version (id, knowledge_document_id, version_no, content, content_digest, source_metadata, review_status)
VALUES ('15961bb4-503d-5804-8a71-7ec838de1832', 'ce09363e-9aa8-5557-a49f-90ba9a311a24', 1,
        $kb$## 목적

전자결재 문서함에서 기간, 상태, 제목 키워드로 문서를 조회하는 방법을 안내합니다.

## 전제

- 공통 로그인(SSO)으로 로그인한 상태입니다.
- 조회할 수 있는 문서는 내가 기안했거나 결재선·참조에 포함된 문서입니다.

## 절차

1. `전자결재 > 문서함 > 전체 문서`를 엽니다.
2. 조회 조건을 정합니다.
   - 기간: 문서의 **기안일**을 기준으로 합니다. 처음에는 최근 3개월이 지정되어 있고, 최대 12개월까지 지정할 수 있습니다.
   - 상태: 처음에는 전체입니다. 특정 상태의 문서만 보려면 선택합니다.
   - 제목 키워드: 제목에 포함된 단어를 입력합니다.
3. [조회]를 누릅니다.

## 확인할 수 있는 결과

- 조건에 맞는 문서가 목록에 나타납니다. 문서를 누르면 상세를 볼 수 있습니다.
- 조건에 맞는 문서가 없으면 "조건에 맞는 문서가 없습니다"가 표시됩니다.
- 조회 기간을 12개월보다 길게 지정하면 "조회 기간은 최대 12개월입니다"가 표시됩니다.
- 열람 권한이 없는 문서의 링크를 열면 "열람 권한이 없는 문서입니다"가 표시됩니다.
- 목록을 불러오지 못하면 "문서를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요"가 표시됩니다.

## 적용 조건과 한계

- 기간 조건은 상신한 날짜가 아니라 기안일에 적용됩니다.
- 임시 저장 문서는 `문서함 > 전체 문서`에 나타나지 않고 `기안함 > 임시 저장`에서 볼 수 있습니다.
- 이 안내는 조회 방법만 다룹니다. 조회가 되지 않는 문제의 확인은 IT 문의로 접수합니다.
$kb$,
        'a8e3e9fbfcbbc6bd228d8adc87bd150c8646e0df486ee6418f596a366ee179c7',
        '{"appliesToSystemVersion": null, "documentKey": "guide-ea-document-search", "origin": "SYNTHETIC", "source": {"reference": null, "type": "AUTHORED"}}'::jsonb, 'APPROVED')
ON CONFLICT DO NOTHING;

-- guide-file-open-download (v1)
INSERT INTO knowledge_document (id, title, document_type, it_system_id, access_level, source_data, data_split, review_status, is_synthetic)
VALUES ('d37e0bc2-07bc-54af-941f-acb6a7effcfa', '파일 공유에서 폴더와 파일 열기·내려받기', 'EMPLOYEE_GUIDE',
        (SELECT id FROM it_system WHERE code = 'FILE_SHARING'), 'EMPLOYEE',
        '{"corpusPath": "docs/knowledge/corpus/guides/guide-file-open-download.md", "documentKey": "guide-file-open-download", "origin": "SYNTHETIC"}'::jsonb, 'RAG', 'APPROVED', true)
ON CONFLICT DO NOTHING;
INSERT INTO knowledge_document_version (id, knowledge_document_id, version_no, content, content_digest, source_metadata, review_status)
VALUES ('8b6d2e6b-df25-58d7-bb56-94a4c41b5d26', 'd37e0bc2-07bc-54af-941f-acb6a7effcfa', 1,
        $kb$## 목적

파일 공유의 공유 폴더에서 파일을 찾아 열어 보거나 내려받는 방법을 안내합니다.

## 전제

- 공통 로그인(SSO)으로 로그인한 상태입니다.
- 접근 권한이 있는 폴더만 목록에 나타납니다.

## 절차

1. `파일 공유 > 공유 폴더`를 엽니다.
2. 폴더를 열어 원하는 파일을 찾습니다.
3. 미리보고 싶으면 [열기]를, 내 컴퓨터에 저장하려면 [내려받기]를 누릅니다.

## 확인할 수 있는 결과

- [열기]는 pdf, png, jpg, txt 파일을 화면에서 미리 보여 줍니다.
- 파일 내용을 저장하려면 [내려받기]를 사용합니다.
- 파일을 불러오지 못하면 "파일을 열 수 없습니다. 잠시 후 다시 시도해 주세요"가 표시됩니다.

## 적용 조건과 한계

- 업로드할 수 있는 파일 크기는 파일당 1GB까지입니다.
- 삭제한 파일은 휴지통에 30일 동안 보관됩니다.
- 폴더 접근 권한 신청은 파일 공유 범위 정책을 따릅니다.
- 이 안내는 정상적인 열기·내려받기만 다룹니다. 열리지 않는 문제의 확인은 IT 문의로 접수합니다.
$kb$,
        '486570d3618710cd733558f68b153af14e881821f8779e747fc3accbc5f66b1d',
        '{"appliesToSystemVersion": null, "documentKey": "guide-file-open-download", "origin": "SYNTHETIC", "source": {"reference": null, "type": "AUTHORED"}}'::jsonb, 'APPROVED')
ON CONFLICT DO NOTHING;

-- guide-file-share-link (v1)
INSERT INTO knowledge_document (id, title, document_type, it_system_id, access_level, source_data, data_split, review_status, is_synthetic)
VALUES ('903a4755-2b61-55e7-ba16-1a6b9c11a1d7', '파일 공유에서 파일을 공유하고 링크 만들기', 'EMPLOYEE_GUIDE',
        (SELECT id FROM it_system WHERE code = 'FILE_SHARING'), 'EMPLOYEE',
        '{"corpusPath": "docs/knowledge/corpus/guides/guide-file-share-link.md", "documentKey": "guide-file-share-link", "origin": "SYNTHETIC"}'::jsonb, 'RAG', 'APPROVED', true)
ON CONFLICT DO NOTHING;
INSERT INTO knowledge_document_version (id, knowledge_document_id, version_no, content, content_digest, source_metadata, review_status)
VALUES ('3e819993-1d88-5980-9955-ffd81ec2548c', '903a4755-2b61-55e7-ba16-1a6b9c11a1d7', 1,
        $kb$## 목적

파일을 다른 임직원과 공유하거나 공유 링크를 만드는 방법을 안내합니다.

## 전제

- 공통 로그인(SSO)으로 로그인한 상태입니다.
- 공유하려는 파일이 파일 공유에 올라와 있어야 합니다.

## 절차

1. `파일 공유 > 공유 폴더`에서 공유할 파일을 선택합니다.
2. [공유]를 누릅니다.
3. 공유 방식을 고릅니다.
   - 임직원·부서 지정: 공유할 임직원이나 부서를 지정합니다.
   - 링크 공유: 링크를 만들어 전달합니다. 링크를 가진 사람 중 로그인한 임직원이 열 수 있습니다.
4. 상대의 권한을 "보기" 또는 "내려받기" 중에서 고릅니다.
5. 만료일을 정합니다. 처음에는 7일로 지정되어 있고, 최대 30일까지 지정할 수 있습니다.
6. 공유를 완료합니다.

## 확인할 수 있는 결과

- 지정한 임직원·부서나 링크를 가진 로그인한 임직원이 파일을 열 수 있습니다.
- 사내 임직원이 아닌 대상을 지정하면 "사내 임직원에게만 공유할 수 있습니다"가 표시됩니다.
- 만료일을 30일보다 길게 지정하면 "만료일은 최대 30일입니다"가 표시됩니다.

## 적용 조건과 한계

- 사외 공유는 지원하지 않습니다.
- 공유는 언제든 취소할 수 있습니다.
- 공유 범위의 세부 규칙은 파일 공유 범위 정책을 따릅니다.
$kb$,
        '9f4926921d9bd0ec1f325554fc0fa2710aa668a55c8d35796d8b48a61a65ce95',
        '{"appliesToSystemVersion": null, "documentKey": "guide-file-share-link", "origin": "SYNTHETIC", "source": {"reference": null, "type": "AUTHORED"}}'::jsonb, 'APPROVED')
ON CONFLICT DO NOTHING;

-- guide-login-permission-request (v1)
INSERT INTO knowledge_document (id, title, document_type, it_system_id, access_level, source_data, data_split, review_status, is_synthetic)
VALUES ('3b058948-6b80-524b-8dce-4966435ad063', '시스템 접근 권한 신청하기', 'EMPLOYEE_GUIDE',
        (SELECT id FROM it_system WHERE code = 'COMMON_LOGIN'), 'EMPLOYEE',
        '{"corpusPath": "docs/knowledge/corpus/guides/guide-login-permission-request.md", "documentKey": "guide-login-permission-request", "origin": "SYNTHETIC"}'::jsonb, 'RAG', 'APPROVED', true)
ON CONFLICT DO NOTHING;
INSERT INTO knowledge_document_version (id, knowledge_document_id, version_no, content, content_digest, source_metadata, review_status)
VALUES ('af4dca7c-0888-5c70-861a-2dc0380e8f6a', '3b058948-6b80-524b-8dce-4966435ad063', 1,
        $kb$## 목적

전자결재, 파일 공유 같은 시스템을 쓰는 데 필요한 접근 권한을 신청하는 방법을 안내합니다.

## 전제

- 공통 로그인(SSO)으로 로그인한 상태입니다.
- 신청할 대상 시스템, 필요한 권한 종류, 업무 목적을 정해 두어야 합니다.

## 절차

1. `내 정보 > 접근 권한 신청`을 엽니다.
2. 대상 시스템을 선택합니다.
3. 필요한 기능과 권한 종류를 고릅니다. 권한 종류는 읽기, 쓰기, 관리 중 하나입니다.
4. 업무 목적을 입력합니다. 업무 목적은 필수입니다.
5. 필요한 기간이 있으면 입력합니다. 비워 두면 기간 제한 없이 신청됩니다.
6. 신청을 제출합니다.

## 확인할 수 있는 결과

신청 상태는 다음 중 하나로 표시됩니다.

| 상태 | 코드 | 의미 |
|---|---|---|
| 접수 | `REQUESTED` | 신청이 접수되어 처리 중인 상태 |
| 승인 | `APPROVED` | 승인된 상태 |
| 반려 | `REJECTED` | 반려된 상태 |
| 적용 | `APPLIED` | 권한이 실제로 적용된 상태 |

- 이미 가지고 있는 권한을 신청하면 "이미 보유한 권한입니다"가 표시됩니다.
- 업무 목적을 비우면 "업무 목적을 입력해 주세요"가 표시됩니다.
- 처리 목표 기간은 2영업일입니다.

## 적용 조건과 한계

- 승인 기준은 권한 종류에 따라 다릅니다. 자세한 기준은 권한 신청 승인 정책을 따릅니다.
- 이 신청은 시스템 이용 권한입니다. 파일 공유의 폴더 접근 권한은 폴더에서 별도로 요청합니다.
$kb$,
        'e64395d6226599f53efde1a9ce5a41d4a6f9c6626a2511206b26ce2684afdc9a',
        '{"appliesToSystemVersion": null, "documentKey": "guide-login-permission-request", "origin": "SYNTHETIC", "source": {"reference": null, "type": "AUTHORED"}}'::jsonb, 'APPROVED')
ON CONFLICT DO NOTHING;

-- guide-login-sso-use (v1)
INSERT INTO knowledge_document (id, title, document_type, it_system_id, access_level, source_data, data_split, review_status, is_synthetic)
VALUES ('63f4c618-4f17-566e-a51e-32c93828febb', '사내 포털 로그인과 SSO 세션 이용', 'EMPLOYEE_GUIDE',
        (SELECT id FROM it_system WHERE code = 'COMMON_LOGIN'), 'EMPLOYEE',
        '{"corpusPath": "docs/knowledge/corpus/guides/guide-login-sso-use.md", "documentKey": "guide-login-sso-use", "origin": "SYNTHETIC"}'::jsonb, 'RAG', 'APPROVED', true)
ON CONFLICT DO NOTHING;
INSERT INTO knowledge_document_version (id, knowledge_document_id, version_no, content, content_digest, source_metadata, review_status)
VALUES ('d6fe685c-1cb0-5057-a167-63fb91b1b874', '63f4c618-4f17-566e-a51e-32c93828febb', 1,
        $kb$## 목적

사내 포털에 로그인하는 방법과, 한 번 로그인하면 전자결재·파일 공유를 다시 로그인하지 않고 쓰는 방식(SSO)을 안내합니다.

## 전제

- 사번과 비밀번호를 알고 있어야 합니다.
- 계정이 잠겨 있지 않아야 합니다. 잠금과 비밀번호 규칙은 계정 잠금 정책을 따릅니다.

## 절차

1. 사내 포털 로그인 화면을 엽니다.
2. 사번과 비밀번호를 입력해 로그인합니다.
3. 로그인한 뒤 전자결재나 파일 공유 같은 시스템 화면으로 이동합니다.

## 확인할 수 있는 결과

- 로그인에 성공하면 대상 시스템 화면이 열립니다. 로그인한 뒤 다시 로그인 화면으로 돌아오는 것은 정상 동작이 아닙니다.
- SSO 세션은 8시간 동안 유지됩니다. 그동안 전자결재와 파일 공유에는 다시 로그인하지 않아도 됩니다.
- 30분 동안 아무 작업도 하지 않으면 세션이 끝나고 "세션이 만료되었습니다. 다시 로그인해 주세요"가 표시됩니다.
- 사번이나 비밀번호가 틀리면 "사번 또는 비밀번호가 올바르지 않습니다"가 표시됩니다.
- 로그인은 되지만 해당 시스템을 쓸 권한이 없으면 "이 시스템을 사용할 권한이 없습니다"가 표시됩니다. 이는 로그인 실패와 다른 상황이며, 접근 권한 신청으로 해결합니다.

## 적용 조건과 한계

- 로그인에 성공했다는 사실만으로 모든 시스템·문서·파일을 열람할 수 있는 것은 아닙니다. 폴더·파일 단위 접근은 파일 공유 쪽 권한을 따릅니다.
- 로그인 후 로그인 화면이 반복되는 문제의 확인은 IT 문의로 접수합니다.
$kb$,
        '1bbb9232797504307af5c8635df591f5de04add6c09e867b3f03d4bf2c32e40f',
        '{"appliesToSystemVersion": null, "documentKey": "guide-login-sso-use", "origin": "SYNTHETIC", "source": {"reference": null, "type": "AUTHORED"}}'::jsonb, 'APPROVED')
ON CONFLICT DO NOTHING;

-- policy-ea-attachment-limit (v1)
INSERT INTO knowledge_document (id, title, document_type, it_system_id, access_level, source_data, data_split, review_status, is_synthetic)
VALUES ('ed8667cd-fb92-5710-8191-e1020113c6ab', '전자결재 첨부 파일 크기·개수·형식 제한', 'POLICY',
        (SELECT id FROM it_system WHERE code = 'ELECTRONIC_APPROVAL'), 'EMPLOYEE',
        '{"corpusPath": "docs/knowledge/corpus/policies/policy-ea-attachment-limit.md", "documentKey": "policy-ea-attachment-limit", "origin": "SYNTHETIC"}'::jsonb, 'RAG', 'APPROVED', true)
ON CONFLICT DO NOTHING;
INSERT INTO knowledge_document_version (id, knowledge_document_id, version_no, content, content_digest, source_metadata, review_status)
VALUES ('d2330635-4b0d-5fae-84e6-3709523e0f56', 'ed8667cd-fb92-5710-8191-e1020113c6ab', 1,
        $kb$## 규칙

- 첨부 파일은 파일당 **20MB 이하**여야 합니다.
- 한 문서에 첨부할 수 있는 파일은 **10개까지**입니다.
- 허용하는 형식은 pdf, docx, xlsx, pptx, hwp, txt, png, jpg, zip입니다.
- 첨부 파일을 미리 볼 수 있는 형식은 pdf, png, jpg, txt입니다. 그 외 형식은 내려받아서 확인합니다.
- 첨부 파일은 한 번에 한 파일씩만 내려받을 수 있습니다. 일괄 내려받기는 지원하지 않습니다.
- 첨부 파일은 해당 문서의 열람 권한이 있어야 볼 수 있습니다.

## 적용 대상

전자결재 문서에 파일을 첨부하거나 첨부 파일을 보는 모든 임직원

## 예외

이 정책에는 정해진 예외가 없습니다.

## 신청 절차

이 제한을 조정하거나 예외를 신청하는 절차는 이 정책에서 정하지 않습니다.

## 근거

가상 업무 규정입니다. 제한을 어기면 "파일 크기는 20MB 이하여야 합니다" 또는 "허용하지 않는 파일 형식입니다"가 표시됩니다.
$kb$,
        'b39c1d1b8939ceccf9f81c72cf51c4b25a2ad8e411a78ea009d6222652d9b934',
        '{"appliesToSystemVersion": null, "documentKey": "policy-ea-attachment-limit", "origin": "SYNTHETIC", "source": {"reference": null, "type": "AUTHORED"}}'::jsonb, 'APPROVED')
ON CONFLICT DO NOTHING;

-- policy-file-share-scope (v1)
INSERT INTO knowledge_document (id, title, document_type, it_system_id, access_level, source_data, data_split, review_status, is_synthetic)
VALUES ('2efb6969-888d-5da8-81a2-0a4dec21974c', '파일 공유 범위와 폴더 접근 권한', 'POLICY',
        (SELECT id FROM it_system WHERE code = 'FILE_SHARING'), 'EMPLOYEE',
        '{"corpusPath": "docs/knowledge/corpus/policies/policy-file-share-scope.md", "documentKey": "policy-file-share-scope", "origin": "SYNTHETIC"}'::jsonb, 'RAG', 'APPROVED', true)
ON CONFLICT DO NOTHING;
INSERT INTO knowledge_document_version (id, knowledge_document_id, version_no, content, content_digest, source_metadata, review_status)
VALUES ('3afb416a-8f6c-5cea-aff7-af3a354d31b6', '2efb6969-888d-5da8-81a2-0a4dec21974c', 1,
        $kb$## 규칙

- 파일은 **사내 임직원에게만** 공유할 수 있습니다. 사외 공유는 허용하지 않습니다.
- 공유 방식은 임직원·부서 지정과 링크 공유입니다. 링크 공유는 링크를 가진 사람 중 로그인한 임직원만 열 수 있습니다.
- 공유 만료일은 처음에 7일이며 최대 30일까지 지정할 수 있습니다.
- 공유는 언제든 취소할 수 있습니다.
- 폴더 접근 권한은 "읽기"와 "읽기·쓰기" 두 종류입니다.
- 접근 권한이 있는 폴더만 목록에 나타납니다.
- 폴더 접근 권한은 회수할 수 있습니다.
- 폴더 접근 권한 신청의 처리 목표는 2영업일입니다.

## 적용 대상

- 파일 공유를 쓰는 모든 임직원
- 폴더 접근 권한은 폴더 단위로 부여하며, 시스템 이용 권한(공통 로그인의 접근 권한 신청)과 다릅니다.

## 예외

이 정책에는 정해진 예외가 없습니다.

## 신청 절차

1. 접근하려는 폴더에서 [접근 권한 요청]을 누릅니다.
2. 업무 목적을 입력합니다. 업무 목적은 필수입니다.
3. 폴더를 소유한 부서의 팀장이 내용을 확인합니다.
4. 파일 공유 담당자가 권한을 반영합니다.

## 근거

가상 업무 규정입니다. 폴더·파일 단위 접근 권한은 파일 공유 담당자가 관리합니다.
$kb$,
        'f6241a061eb50e1fc8fe982cbaf630f2b56857901e6fd9231a5ebbdf3c563565',
        '{"appliesToSystemVersion": null, "documentKey": "policy-file-share-scope", "origin": "SYNTHETIC", "source": {"reference": null, "type": "AUTHORED"}}'::jsonb, 'APPROVED')
ON CONFLICT DO NOTHING;

-- policy-login-account-lock (v1)
INSERT INTO knowledge_document (id, title, document_type, it_system_id, access_level, source_data, data_split, review_status, is_synthetic)
VALUES ('7261865e-9733-596d-a6bd-91793fef6342', '계정 잠금과 비밀번호 규칙', 'POLICY',
        (SELECT id FROM it_system WHERE code = 'COMMON_LOGIN'), 'EMPLOYEE',
        '{"corpusPath": "docs/knowledge/corpus/policies/policy-login-account-lock.md", "documentKey": "policy-login-account-lock", "origin": "SYNTHETIC"}'::jsonb, 'RAG', 'APPROVED', true)
ON CONFLICT DO NOTHING;
INSERT INTO knowledge_document_version (id, knowledge_document_id, version_no, content, content_digest, source_metadata, review_status)
VALUES ('5b273fbc-867b-542b-a505-caee954e8984', '7261865e-9733-596d-a6bd-91793fef6342', 1,
        $kb$## 규칙

- 비밀번호를 **5회 연속** 틀리면 계정이 잠깁니다.
- 잠긴 계정은 **30분 뒤 자동으로 풀립니다**.
- 30분을 기다리지 않고 바로 풀려면 [잠금 해제 요청]을 접수합니다. 공통 로그인 담당자가 처리합니다.
- 새 비밀번호는 **10자 이상**이며 영문, 숫자, 특수문자를 모두 조합해야 합니다.
- 직전에 쓴 3개의 비밀번호는 다시 쓸 수 없습니다.

## 적용 대상

사내 포털에 로그인하는 모든 임직원 계정

## 예외

이 정책에는 정해진 예외가 없습니다.

## 신청 절차

- 비밀번호를 잊은 경우: 로그인 화면의 [비밀번호 찾기]를 누르고 사번과 등록된 이메일을 확인하면 재설정 링크가 전달됩니다. 링크는 30분 동안 유효합니다.
- 계정이 잠긴 경우: 30분을 기다리거나 [잠금 해제 요청]을 접수합니다.

## 근거

가상 업무 규정입니다.
$kb$,
        'a3be098e0130695f9fbc72294812a0bf48d1815b71797148a5b52634bac8b1e2',
        '{"appliesToSystemVersion": null, "documentKey": "policy-login-account-lock", "origin": "SYNTHETIC", "source": {"reference": null, "type": "AUTHORED"}}'::jsonb, 'APPROVED')
ON CONFLICT DO NOTHING;

-- policy-login-permission-approval (v1)
INSERT INTO knowledge_document (id, title, document_type, it_system_id, access_level, source_data, data_split, review_status, is_synthetic)
VALUES ('011c3688-63cd-572b-8009-c6cf9d4a91e2', '시스템 접근 권한 신청 승인 기준', 'POLICY',
        (SELECT id FROM it_system WHERE code = 'COMMON_LOGIN'), 'EMPLOYEE',
        '{"corpusPath": "docs/knowledge/corpus/policies/policy-login-permission-approval.md", "documentKey": "policy-login-permission-approval", "origin": "SYNTHETIC"}'::jsonb, 'RAG', 'APPROVED', true)
ON CONFLICT DO NOTHING;
INSERT INTO knowledge_document_version (id, knowledge_document_id, version_no, content, content_digest, source_metadata, review_status)
VALUES ('1bac2f3d-b47a-5cdc-91c1-b9408f02c4c7', '011c3688-63cd-572b-8009-c6cf9d4a91e2', 1,
        $kb$## 규칙

- 시스템 접근 권한은 **읽기**, **쓰기**, **관리** 세 종류입니다.
- 신청서에는 대상 시스템, 필요한 기능·권한, **업무 목적**을 적습니다. 업무 목적이 없으면 신청할 수 없습니다.
- 읽기 권한은 대상 시스템의 주담당자가 승인합니다.
- 쓰기·관리 권한은 주담당자 승인에 더해 신청자 **소속 팀장의 확인**이 필요합니다. 팀장 확인이 끝나기 전까지 신청은 "접수"(`REQUESTED`) 상태에 머뭅니다.
- 처리 목표는 신청일로부터 2영업일입니다.
- 이미 가지고 있는 권한은 다시 신청할 수 없습니다.
- 필요한 기간을 적지 않으면 상시 권한으로 신청됩니다.

신청 상태는 "접수"(`REQUESTED`), "승인"(`APPROVED`), "반려"(`REJECTED`), "적용"(`APPLIED`)입니다.

## 적용 대상

- 사내 시스템(전자결재, 파일 공유 등)을 이용하려는 모든 임직원
- 이 정책은 시스템 이용 권한만 다룹니다. 파일 공유의 폴더·파일 단위 접근 권한은 파일 공유 범위 정책을 따릅니다.

## 예외

이 정책에는 정해진 예외가 없습니다.

## 신청 절차

신청 방법은 `내 정보 > 접근 권한 신청`에서 대상 시스템, 권한 종류, 업무 목적을 입력해 제출하는 것입니다. 쓰기·관리 권한은 신청 뒤 소속 팀장에게 확인을 요청해야 합니다.

## 근거

가상 업무 규정입니다. 신청 접수와 진행은 공통 로그인 담당자가 관리합니다.
$kb$,
        '1cd0d18a6fa493a89f8afc85bafa0f93cd3364e05c03db0161f8a974a9f1a02f',
        '{"appliesToSystemVersion": null, "documentKey": "policy-login-permission-approval", "origin": "SYNTHETIC", "source": {"reference": null, "type": "AUTHORED"}}'::jsonb, 'APPROVED')
ON CONFLICT DO NOTHING;

-- runbook-ea-attachment-open-failure (v1)
INSERT INTO knowledge_document (id, title, document_type, it_system_id, access_level, source_data, data_split, review_status, is_synthetic)
VALUES ('c01d7886-57d8-5365-884e-afdb00061f05', '전자결재 첨부 파일이 열리지 않을 때', 'STAFF_RUNBOOK',
        (SELECT id FROM it_system WHERE code = 'ELECTRONIC_APPROVAL'), 'STAFF',
        '{"corpusPath": "docs/knowledge/corpus/runbooks/runbook-ea-attachment-open-failure.md", "documentKey": "runbook-ea-attachment-open-failure", "origin": "SYNTHETIC"}'::jsonb, 'RAG', 'APPROVED', true)
ON CONFLICT DO NOTHING;
INSERT INTO knowledge_document_version (id, knowledge_document_id, version_no, content, content_digest, source_metadata, review_status)
VALUES ('cf830c91-fe02-5749-b73b-5d929a95af6a', 'c01d7886-57d8-5365-884e-afdb00061f05', 1,
        $kb$## 증상

문의자가 "전자결재 문서의 첨부 파일이 열리지 않는다"고 말한다. 대상 기능은 첨부 열람(`ATTACHMENT_VIEW`)이며 증상 `sym-ea-attach-not-open`이다. 이 문서는 담당자용이며 직원에게 보여 줄 문장이 아니다.

먼저 화면 문구로 상황을 구분한다. "첨부 파일 열람 권한이 없습니다"는 권한 거부이고, "첨부 파일을 열 수 없습니다"는 조회 오류다. [열기]가 눌리지 않고 [내려받기]만 되는 것과 "검사 중" 표시도 서로 다른 상황이다. 권한 거부를 조회 오류로, 조회 오류를 권한 거부로 바꿔 판단하지 않는다.

## 확인 항목

1. `check-ea-error-message` — 화면에 표시된 문구. 위 구분의 출발점이다.
2. `check-ea-file-format` — 열리지 않는 파일의 형식. 미리보기 형식은 pdf, png, jpg, txt다.
3. `check-ea-upload-time` — 파일을 올린 시점. 업로드 직후인지 본다.
4. `check-ea-retry-result` — 잠시 뒤 다시 시도해도 같은지.
5. `check-ea-participation` — 문의자가 문서의 기안자인지, 결재선·참조에 있는지. 정의는 `runbook-ea-document-not-listed`에 있다.

## 가능 원인 후보

- `cause-ea-preview-unsupported` — 미리보기 형식(pdf, png, jpg, txt)이 아니면 [열기]가 눌리지 않고 [내려받기]만 된다. 파일이 깨진 것이 아니다.
- `cause-ea-attach-scanning` — 업로드 직후 보안 검사를 최대 10분 하며, "검사 중"인 동안에는 열기와 내려받기가 모두 안 된다.
- `cause-ea-storage-error` — 저장소의 일시 오류로 "첨부 파일을 열 수 없습니다"가 나온다.
- `cause-ea-no-doc-permission` — 문서 열람 권한이 있어야 첨부를 볼 수 있다. 권한이 없으면 "첨부 파일 열람 권한이 없습니다"가 나온다.

위 후보는 가능성일 뿐이며 확인 결과로만 좁힌다.

## 결과 해석

- 문구가 "첨부 파일 열람 권한이 없습니다"이면 권한 거부다. 문의자가 문서의 기안자·결재선·참조에 있는지 확인한다.
- 문구가 "첨부 파일을 열 수 없습니다"이면 조회 오류다. 같은 파일이 시간이 지나도 계속 안 열리는지, 다른 첨부는 열리는지 본다.
- 파일 형식이 미리보기 형식이 아니면 [열기]가 안 눌리는 것이 정상이며, [내려받기]로 확인한다.
- 파일을 올린 지 10분 이내이고 "검사 중"이 보이면 검사가 끝날 때까지는 열 수 없는 정상 상태다. 검사가 끝난 뒤 다시 열어 본다.
- 어느 항목에서도 원인이 좁혀지지 않으면 원인을 확정하지 않고 조사를 이어 간다.

## 적용 조건과 한계

- 이 런북은 전자결재 문서의 첨부 열람에만 쓴다.
- 파일 공유의 파일이 열리지 않는 문제와 증상이 비슷하지만 원인과 확인 항목이 다르다. 전자결재의 첨부 저장소는 파일 공유와 자동으로 연결되지 않으므로, 한쪽의 원인으로 다른 쪽을 확정하지 않는다.
- 로그인이 반복되는 문제는 공통 로그인 담당이다.
$kb$,
        '1ae45d39d24cf027aaf10c830da00b0116c63f185df4526035dfe1cd22501413',
        '{"appliesToSystemVersion": null, "documentKey": "runbook-ea-attachment-open-failure", "origin": "SYNTHETIC", "source": {"reference": null, "type": "AUTHORED"}}'::jsonb, 'APPROVED')
ON CONFLICT DO NOTHING;

-- runbook-ea-document-not-listed (v1)
INSERT INTO knowledge_document (id, title, document_type, it_system_id, access_level, source_data, data_split, review_status, is_synthetic)
VALUES ('31604baa-d46c-5303-996b-e05bea5b0b59', '전자결재 문서가 문서함 목록에 보이지 않을 때', 'STAFF_RUNBOOK',
        (SELECT id FROM it_system WHERE code = 'ELECTRONIC_APPROVAL'), 'STAFF',
        '{"corpusPath": "docs/knowledge/corpus/runbooks/runbook-ea-document-not-listed.md", "documentKey": "runbook-ea-document-not-listed", "origin": "SYNTHETIC"}'::jsonb, 'RAG', 'APPROVED', true)
ON CONFLICT DO NOTHING;
INSERT INTO knowledge_document_version (id, knowledge_document_id, version_no, content, content_digest, source_metadata, review_status)
VALUES ('ad95ebbc-909d-5b37-a74c-3e75a82371ca', '31604baa-d46c-5303-996b-e05bea5b0b59', 1,
        $kb$## 증상

문의자가 "문서가 전자결재 `문서함 > 전체 문서` 목록에 나오지 않는다"고 말한다. 대상 기능은 문서 조회(`DOCUMENT_VIEW`)이며 증상 `sym-ea-doc-not-listed`다. 이 문서는 담당자용이며 직원에게 보여 줄 문장이 아니다.

먼저 상황을 구분한다. 목록이 비어 "조건에 맞는 문서가 없습니다"가 나오는 것(결과 없음), 직접 링크로 열었더니 "열람 권한이 없는 문서입니다"가 나오는 것(권한 거부), "문서를 불러오지 못했습니다"가 나오는 것(조회 오류)은 서로 다른 상황이다. 조회 오류는 이 런북의 원인 후보가 아니라 시스템 일시 장애로 따로 본다.

## 확인 항목

아래 순서로 확인한다. 문의자가 모르면 "모름"으로 기록하고 다음 항목으로 넘어간다.

1. `check-ea-search-period` — 문의자가 설정한 조회 기간. 기본값은 최근 3개월이고 최대 12개월이다.
2. `check-ea-draft-date` — 찾는 문서를 기안한 시기. 조회 기간은 **기안일** 기준이다.
3. `check-ea-filter-condition` — 조회할 때 입력한 상태와 제목 키워드.
4. `check-ea-submit-state` — 문서를 상신했는지, 임시 저장 상태인지.
5. `check-ea-participation` — 문의자가 기안자인지, 결재선·참조에 포함됐는지.
6. `check-ea-view-permission-record` — 시스템에 남은 문서의 기안자·결재선·참조 기록. 문의자에게 묻는 항목이 아니라 담당자가 시스템에서 확인한다.

## 가능 원인 후보

- `cause-ea-period-out-of-range` — 기안일이 조회 기간 밖이다. 임시 저장해 둔 지 오래된 문서를 최근에 상신해도 기안일이 기간 밖이면 목록에 나오지 않는다.
- `cause-ea-no-view-permission` — 문의자가 기안자·결재선·참조에 없어서 볼 수 있는 문서가 아니다.
- `cause-ea-draft-not-submitted` — 임시 저장(`DRAFT`) 문서는 `문서함 > 전체 문서`에 나타나지 않고 `기안함 > 임시 저장`에만 있다.
- `cause-ea-filter-narrow` — 상태나 제목 키워드 조건이 문서와 맞지 않는다.

위 후보는 가능성일 뿐이며 확인 결과로만 좁힌다.

## 결과 해석

- 기안일이 조회 기간(기본 최근 3개월)에서 벗어나 있으면 기간 원인이 유력하다. 기안일을 포함하도록 기간을 넓혀 다시 조회했을 때 문서가 나타나는지 본다.
- 문의자가 기안자도 결재선·참조도 아니면 열람 권한이 없어서 보이지 않는 것이다. 목록에 나오지 않는 것이 정상이며 오류가 아니다. 시스템 기록에서 기안자·결재선·참조에 문의자가 없는지 확인한다.
- 문의자가 문서를 상신하지 않고 임시 저장만 했다면 `기안함 > 임시 저장`에 있는지 확인한다.
- 상태나 키워드를 넣었다면 조건을 비우고 다시 조회해 결과가 달라지는지 본다.
- 어느 항목에서도 원인이 좁혀지지 않으면 원인을 확정하지 않고 조사를 이어 간다.

## 적용 조건과 한계

- 문서 조회(`DOCUMENT_VIEW`)에서 문서가 목록에 보이지 않는 경우에만 쓴다. 결재 상태 확인이나 첨부 열람 문제는 다루지 않는다.
- 로그인이 반복되거나 세션이 끊기는 문제는 공통 로그인 담당이다.
- 확인 결과가 위 후보에 모두 맞지 않으면 이 런북으로 원인을 정하지 않는다.
$kb$,
        '00fe9f71c0828eba7f31d469f040c15dff6f9196a85c6fe8d5e9f112ffe3d58d',
        '{"appliesToSystemVersion": null, "documentKey": "runbook-ea-document-not-listed", "origin": "SYNTHETIC", "source": {"reference": null, "type": "AUTHORED"}}'::jsonb, 'APPROVED')
ON CONFLICT DO NOTHING;

-- runbook-file-open-denied (v1)
INSERT INTO knowledge_document (id, title, document_type, it_system_id, access_level, source_data, data_split, review_status, is_synthetic)
VALUES ('af32da54-e352-58b8-8546-3d26202ab572', '파일 공유의 파일이나 폴더가 열리지 않을 때', 'STAFF_RUNBOOK',
        (SELECT id FROM it_system WHERE code = 'FILE_SHARING'), 'STAFF',
        '{"corpusPath": "docs/knowledge/corpus/runbooks/runbook-file-open-denied.md", "documentKey": "runbook-file-open-denied", "origin": "SYNTHETIC"}'::jsonb, 'RAG', 'APPROVED', true)
ON CONFLICT DO NOTHING;
INSERT INTO knowledge_document_version (id, knowledge_document_id, version_no, content, content_digest, source_metadata, review_status)
VALUES ('6698dff8-74f2-52a5-9223-802608959695', 'af32da54-e352-58b8-8546-3d26202ab572', 1,
        $kb$## 증상

문의자가 "파일 공유의 파일이나 폴더가 열리지 않는다"고 말한다. 대상 기능은 파일 열람(`FILE_VIEW`)이며 증상 `sym-file-not-open`이다. 이 문서는 담당자용이며 직원에게 보여 줄 문장이 아니다.

먼저 화면 문구로 상황을 구분한다. 권한 없음과 파일 없음은 다른 상황이다.

- "이 폴더에 접근할 권한이 없습니다" — 폴더 접근 권한 없음. 링크로 접근한 경우에 나오며, 목록으로 들어갈 때는 권한 없는 폴더가 목록에 보이지 않는다.
- "파일을 찾을 수 없습니다" — 파일이 삭제되었거나 이동됨.
- "공유 링크가 만료되었습니다" — 링크 만료.
- "파일을 열 수 없습니다. 잠시 후 다시 시도해 주세요" — 조회 오류(일시 장애). 이 런북의 원인 후보가 아니라 시스템 일시 장애로 따로 본다.

## 확인 항목

1. `check-file-error-message` — 화면에 표시된 문구. 위 구분의 출발점이다.
2. `check-file-access-route` — 폴더 목록에서 열었는지, 링크로 열었는지.
3. `check-file-recent-change` — 그 파일이 최근 삭제되거나 이동됐는지 아는지.
4. `check-file-link-received` — 공유 링크를 받은 시점. 링크 공유의 기본 만료일은 7일, 최대 30일이다.

## 가능 원인 후보

- `cause-file-folder-no-access` — 폴더 접근 권한이 없다. 권한은 폴더 단위로 부여된다.
- `cause-file-moved-deleted` — 파일이 삭제되었거나 다른 위치로 이동되었다. 삭제한 파일은 휴지통에 30일 보관된다.
- `cause-file-link-expired` — 공유 링크의 만료일이 지났다. 기본 만료일은 7일이다.

위 후보는 가능성일 뿐이며 확인 결과로만 좁힌다.

## 결과 해석

- 문구가 "이 폴더에 접근할 권한이 없습니다"이면 권한 문제로 본다. 링크로 접근했는지, 문의자에게 해당 폴더 접근 권한이 부여돼 있는지 확인한다.
- 문구가 "파일을 찾을 수 없습니다"이면 권한 문제가 아니다. 파일이 삭제되었거나 이동되었는지 확인하고, 삭제라면 휴지통에 있는지 본다.
- 문구가 "공유 링크가 만료되었습니다"이면 링크를 받은 시점과 만료일을 대조한다.
- 문구를 정확히 기억하지 못하면 접근 경로(목록인지 링크인지)와 최근 변경 여부로 후보를 좁힌다.
- 어느 항목에서도 원인이 좁혀지지 않으면 원인을 확정하지 않고 조사를 이어 간다.

## 적용 조건과 한계

- 파일 공유의 파일·폴더 열람에만 쓴다. 폴더·파일 단위 접근 권한은 파일 공유 담당이며 로그인·SSO 문제는 공통 로그인 담당이다.
- 전자결재 첨부가 열리지 않는 문제와 증상이 비슷하지만 원인과 확인 항목이 다르다. 한쪽의 원인으로 다른 쪽을 확정하지 않는다.
- 정상 로그인에 성공했다는 사실만으로 모든 파일의 열람 권한이 있다고 판단하지 않는다.
$kb$,
        '2eb5f9f9763e646e27b6b18e30082e642c99db7ed2caebd4251d822a4cc155ee',
        '{"appliesToSystemVersion": null, "documentKey": "runbook-file-open-denied", "origin": "SYNTHETIC", "source": {"reference": null, "type": "AUTHORED"}}'::jsonb, 'APPROVED')
ON CONFLICT DO NOTHING;

-- runbook-login-sso-loop (v1)
INSERT INTO knowledge_document (id, title, document_type, it_system_id, access_level, source_data, data_split, review_status, is_synthetic)
VALUES ('f8e39ef3-4d52-55f1-b8cd-04c175333ee6', '로그인 후 다시 로그인 화면으로 돌아올 때', 'STAFF_RUNBOOK',
        (SELECT id FROM it_system WHERE code = 'COMMON_LOGIN'), 'STAFF',
        '{"corpusPath": "docs/knowledge/corpus/runbooks/runbook-login-sso-loop.md", "documentKey": "runbook-login-sso-loop", "origin": "SYNTHETIC"}'::jsonb, 'RAG', 'APPROVED', true)
ON CONFLICT DO NOTHING;
INSERT INTO knowledge_document_version (id, knowledge_document_id, version_no, content, content_digest, source_metadata, review_status)
VALUES ('1c19dc5a-c91d-5256-b16a-7c89de592266', 'f8e39ef3-4d52-55f1-b8cd-04c175333ee6', 1,
        $kb$## 증상

문의자가 "로그인했는데 다시 로그인 화면이 나온다"고 말한다. 대상 기능은 로그인·SSO(`LOGIN_SSO`)이며 증상 `sym-login-loop`다. 정상 흐름은 로그인 성공 뒤 대상 시스템 화면이 열리는 것이고, 다시 로그인 화면으로 돌아오는 것은 정상이 아니다. 이 문서는 담당자용이며 직원에게 보여 줄 문장이 아니다.

먼저 다른 상황과 구분한다.

- 사번이나 비밀번호가 틀려 "사번 또는 비밀번호가 올바르지 않습니다"가 나오는 것은 인증 실패다.
- 비밀번호를 5회 연속 틀려 계정이 잠긴 것은 계정 잠금이다.
- 비활동 30분이 지나 "세션이 만료되었습니다. 다시 로그인해 주세요"가 나오는 것은 정상적인 세션 만료다.
- "이 시스템을 사용할 권한이 없습니다"는 이용 권한이 없는 것이며 로그인 반복이 아니다.

문의자는 전자결재나 파일 공유처럼 다른 시스템에서 이 증상을 겪고 보고할 수 있다. 이때 보고 시스템과 책임 시스템이 다르다.

## 확인 항목

1. `check-login-reported-system` — 어느 시스템에서 로그인 화면이 반복되는지. 포털에서인지, 전자결재나 파일 공유에서인지.
2. `check-login-portal-direct` — 사내 포털에서 직접 로그인하면 정상인지.
3. `check-login-browser-cookie` — 브라우저의 쿠키 차단 설정 여부.
4. `check-login-browser-other` — 다른 브라우저나 시크릿 창에서도 같은지.
5. `check-login-device-time` — 단말의 날짜·시간 설정이 맞는지.

## 가능 원인 후보

SSO 세션은 브라우저의 포털 도메인 쿠키에 저장되고, 전자결재와 파일 공유는 이 세션을 검증해 이용을 허용한다.

- `cause-login-cookie-blocked` — 브라우저가 쿠키를 차단하면 로그인에 성공해도 세션이 저장되지 않아 로그인 화면이 반복된다.
- `cause-login-clock-skew` — 단말 시각이 서버와 5분 넘게 어긋나면 발급된 세션이 곧바로 만료로 판정되어 같은 증상이 반복된다.
- `cause-login-linked-system-auth` — 포털 로그인은 정상인데 특정 연동 시스템의 세션 검증이 실패한다. 포털에서 직접 로그인하면 되고 특정 시스템에서만 반복된다.

위 후보는 가능성일 뿐이며 확인 결과로만 좁힌다.

## 결과 해석

- 어느 브라우저에서나 포털 직접 로그인부터 반복되면 쿠키 차단이나 단말 시각 어긋남을 의심한다. 다른 브라우저나 시크릿 창에서는 되고 원래 브라우저에서만 안 되면 쿠키 차단이 유력하다.
- 단말 날짜·시간이 실제와 크게 다르면 시각 어긋남이 유력하다.
- 포털에서 직접 로그인하면 정상이고 특정 시스템에서만 반복되면 연동 시스템의 세션 검증을 의심한다.
- 전자결재에서 보고했더라도 원 맥락은 전자결재로 보존한다. 대화와 허용된 근거가 공통 로그인의 처리를 뒷받침할 때만 책임 시스템 변경을 제안한다. 의존 관계만 있거나 근거가 부족하면 보고 시스템 담당자가 최초로 조사한다.
- 어느 항목에서도 원인이 좁혀지지 않으면 원인을 확정하지 않고 조사를 이어 간다.

## 적용 조건과 한계

- 로그인 성공 뒤 로그인 화면이 반복되는 경우에만 쓴다. 인증 실패, 계정 잠금, 세션 만료, 이용 권한 없음에는 쓰지 않는다.
- 전자결재 첨부가 열리지 않는 문제는 이 증상과 별개다. 두 문제를 함께 보고해도 한쪽을 다른 쪽의 원인으로 확정하지 않는다.
- 로그인에 성공했다는 사실만으로 모든 문서·파일의 열람 권한이 있다고 판단하지 않는다.
$kb$,
        'd1bb85bdb4e43b3e00e0ae9050ce878ae42b05f8a483026c0240ac1b59f20797',
        '{"appliesToSystemVersion": null, "documentKey": "runbook-login-sso-loop", "origin": "SYNTHETIC", "source": {"reference": null, "type": "AUTHORED"}}'::jsonb, 'APPROVED')
ON CONFLICT DO NOTHING;
