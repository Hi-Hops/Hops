# ERD (물리 테이블·컬럼)

상태: PRD 1.44 MVP 공통 계약 정합안 · 22개 테이블 · FK 46개 · 6절 보완을 3절 표에 반영하고 Flyway V1~V21로 실제 DB에 적용 (#23). 원본 기준은 [DATA11절](DATA.md#11-완성-erd-대조-결과와-병합-기준)에 고정한다.

이 문서는 테이블·컬럼·타입·FK를 적은 **물리 설계**다. ERD Cloud import와 Flyway 마이그레이션 작성의 기준으로 쓴다. 엔터티의 의미·관계·저장 불변식(**논리 모델**)은 [ERD 설명](ERD_설명.md)과 [DATA](DATA.md)를 따른다. 두 문서가 다르면 논리 모델을 먼저 맞추고 이 문서를 고친다.

실제 DB 구조는 [Flyway 마이그레이션](../backend/spring/hops/src/main/resources/db/migration/) V1~V21이다. V1은 `department`·`member`·`member_role`, V2~V20은 나머지 19개 테이블, V21은 `member.policy_revision` 추가다. 이 문서를 고쳐도 DDL·DB에 자동으로 반영되지 않으므로, 바꿀 때는 새 마이그레이션과 함께 고친다.

## 1. 한눈에 보기

| 영역 | 테이블명 | 물리명 | 하는 일 | 근거 |
|---|---|---|---|---|
| 계정 · 조직 | **부서** | `department` | 임직원의 소속 부서를 관리해요. 문의 배정에는 쓰지 않아요. | 최종기획서 2.1 |
|  | **회원** | `member` | 로그인 계정과 소속 부서를 관리해요. 담당자 여부는 시스템의 주담당자 지정으로 판단해요. | 최종기획서 3 |
|  | **역할 부여** | `member_role` | 업무 관리자처럼 부서·시스템 담당과 별개인 권한을 부여해요. | 최종기획서 3 |
| 시스템 | **시스템** | `it_system` | 문의를 받는 업무시스템(전자결재·공통 로그인·파일 공유)과 시스템별 주담당자를 관리해요. | 최종기획서 2.2, 3 |
|  | **시스템 기능** | `system_feature` | 시스템 안의 기능(문서 조회·결재 상태 등)이에요. 표준 요청·CASE가 어느 기능 이야기인지 가리켜요. | 최종기획서 4.1, 11.1 |
| 문의 · 대화 · 문제 | **문의** | `inquiry` | 대화 세션 1개 = 문의 1건이에요. 종합 상태·AI 요약과 문의 버전을 담아요. | 최종기획서 5.3, 6 |
|  | **대화 메시지** | `message` | 문의 안의 대화(사용자·AI 메시지)를 순서대로 저장해요. | 최종기획서 4.5 |
|  | **메시지 수정 이력** | `message_revision` | 사용자가 메시지를 고쳤을 때 고치기 전 원문을 남겨요. | 최종기획서 5.3 |
|  | **문의 문제** | `inquiry_problem` | 복합 문의를 나눈 '문제' 단위예요. 문제마다 추가 질문 횟수·티켓 생성·무티켓 해결 확인을 따로 관리해요. | 최종기획서 5.1, 5.4, 6 |
|  | **표준 요청서** | `standard_request` | 문제별 대화를 담당자가 바로 일할 수 있는 요청서로 정리해요. 문의 버전마다 쌓아 정정 전 내용도 남겨요. | 최종기획서 4.5, 5.1 |
| 티켓 · 배정 · 처리 | **티켓** | `ticket` | 책임 시스템의 주담당자가 처리할 일 1건이에요. 문제 1개에 티켓은 최대 1개예요. | 최종기획서 5.2, 5.4, 6 |
|  | **티켓 변경 이력** | `ticket_event` | 티켓의 생성·상태 변경·내용 정정·해결 확인 응답 이력을 추가만 해서 남겨요. | 최종기획서 4.3, 5.3, 6 |
|  | **담당 배정 이력** | `ticket_assignment` | 티켓이 어느 시스템의 어느 담당자에게 배정됐는지, 누가 왜 정정했는지 남겨요. | 최종기획서 3, 5.2 |
|  | **티켓 처리 기록** | `ticket_work_log` | 담당자가 한 조치·실패한 시도·해결·기능 구현을 추가만 해서 남겨요. 해결 기록이 저장되면 Curator가 사례 초안을 만들어요. | 최종기획서 6, 7.3, 8.3 |
| AI 실행 | **AI 실행** | `agent_run` | 상담 분석 또는 Curator 지식화 1회 실행 기록이에요. 실행 추적과 응답 시간 평가에 써요. | 최종기획서 7.2, 7.3, 12.2 |
|  | **AI 실행 단계** | `agent_step` | AI 실행 안에서 어느 에이전트가 무엇을 했는지 단계별로 남겨요. 채팅 진행 문구와 실패 단계 추적에 써요. | 최종기획서 7.1, 7.2 |
| 요청 처리 | **중복 요청 방지 기록** | `idempotency_key` | 같은 변경 요청이 다시 와도 한 번만 반영하고 처음 결과를 그대로 돌려주려고 처리한 요청을 남겨요. | DATA 5 (팀 합의 2026-10-28) |
| 알림 | **알림** | `notification` | 서비스 내부 알림함이에요. 배정·해결 확인 요청·결과 통보·사례 승인 요청을 알려요. | 최종기획서 6, 13.2 |
| 지식 | **지식 문서** | `knowledge_document` | 직원 가이드·정책·담당자 런북이에요. 출처·검수·공개 범위·평가 분할을 함께 관리해요. | 최종기획서 8.1, 12.1 |
|  | **문서 버전** | `knowledge_document_version` | 문서 내용을 덮어쓰지 않고 버전별로 보관해요. | 최종기획서 8.5 |
|  | **검색 조각** | `knowledge_chunk` | 문서를 검색하기 좋게 잘게 나눈 조각과 임베딩이에요. | 최종기획서 9.1, 9.3 |
|  | **해결 사례** | `resolved_case` | 해결·구현 기록으로 만든 사례예요. Curator 초안 → 주담당자 승인 → Vector·Neo4j 반영 후 검색에 써요. | 최종기획서 8.1~8.5 |
| 관계 데이터 | **증상** | `relation_symptom` | 직원이 말하는 현상이에요. 시스템·기능에 묶이고 원인을 담지 않아요. | 에이전트 설계 8절, #191 |
|  | **가능 원인** | `relation_cause` | 증상의 가능한 원인 후보예요. 담당자 전용이고 확정이 아니에요. | 에이전트 설계 8절, #191 |
|  | **확인 항목** | `relation_check_item` | 원인을 가르는 확인거리예요. 직원이 스스로 답할 수 있는 항목만 직원 공개로 표시해요. | 에이전트 설계 8절, #191 |
|  | **질문 문구** | `relation_question_template` | 확인 항목을 직원에게 묻는 문구예요. 승인되고 공개 표시가 있는 문구만 직원 상담에 써요. | 에이전트 설계 8절, #191 |
|  | **증상-원인 연결** | `relation_symptom_cause` | 증상에서 가능한 원인 후보로 가는 연결이에요. | 에이전트 설계 8절, #191 |
|  | **원인-확인 항목 연결** | `relation_cause_check` | 원인에서 그 원인을 가르는 확인 항목으로 가는 연결이에요. | 에이전트 설계 8절, #191 |
|  | **사례-증상 연결** | `relation_case_symptom` | 승인 사례가 다룬 증상을 이어요. | 에이전트 설계 8절, #191 |
|  | **사례-확인 항목 연결** | `relation_case_check` | 승인 사례가 쓴 확인 항목을 이어요. | 에이전트 설계 8절, #191 |

## 2. 공통 규칙

| 항목 | 규칙 |
|---|---|
| 기본키 | 모든 테이블 `id` UUID (UUIDv7 권장). 화면에는 Q-0142·TK-0001·CASE-0001 같은 표시 번호를 따로 씀 |
| 시간 컬럼 | 바뀌는 테이블: 생성일시·수정일시·삭제일시 / 원본 이력·기록9개는 생성일시만. journal은 operation/tombstone 갱신, CASE는 현재 초안 편집 후 승인하면 읽기 전용(6절) / AI 실행: 생성일시·수정일시 |
| 이력·기록 테이블 | 메시지 수정 이력, 표준 요청서, 티켓 변경 이력, 티켓 처리 기록, AI 실행 단계, 중복 요청 방지 기록, 알림, 문서 버전, 검색 조각 |
| 상태값 | 별도 코드 테이블 없이 VARCHAR(30) + CHECK 제약. 마이그레이션에서 `ck_<테이블>_<컬럼>` 이름으로 적용 (상태와 맞물린 규칙 포함 40개) |
| 상태와 맞물린 값 | 상태에 따라 반드시 있거나 없어야 하는 값은 CHECK로 막는다: 티켓 `RESOLVED` ⇔ `resolved_at`, 문제 `RESOLVED_WITHOUT_TICKET` ⇔ `resolved_at`, 배정 `MANUAL_CORRECTION` ⇔ `assigned_by_member_id`, 사례 `APPROVED` ⇔ `approved_by_member_id`·`approved_at`(초안이면 둘 다 NULL), AI 실행 `QUEUED`는 시작·종료 시각 없음 / `RUNNING`은 시작 시각만 / 끝난 상태는 종료 시각 필수 / `SUCCEEDED`는 시작 시각도 필수 / 종료 시각 ≥ 시작 시각, 완료 처리 기록은 `verification_result` 필수, Curator 실행은 `trigger_work_log_id` 필수 |
| 인덱스 | PostgreSQL은 FK에 인덱스를 자동으로 만들지 않아 목록 조회용 FK에 `idx_<테이블>_<컬럼>`을 둔다: `inquiry.requester_member_id`, `agent_run.inquiry_id`, `ticket.inquiry_id`·`assignee_member_id`·`responsible_system_id`, `ticket_event.ticket_id`, `ticket_assignment.ticket_id`, `ticket_work_log.ticket_id`, `notification (recipient_member_id, created_at DESC)`(알림함 최신순). 유일 제약의 앞 컬럼으로 이미 찾을 수 있는 경우(예: `message.inquiry_id`)는 따로 두지 않는다. 부분 유일 인덱스 2개(현재 배정, Curator 실행)는 각 테이블 설명 참고 |
| 업무 데이터 쓰기 | Spring이 REST 변경 요청을 저장·확정하고 권한별 SSE를 전달. AI 서버는 제안·근거 반환을 담당하며 업무 DB에 직접 쓰지 않음 |
| 관계선 | 실제 DB는 FK 46개(원본 45개 + 6.1절 `inquiry.current_run_id`)를 적용한다. 추가 테이블의 FK는 만들지 않는다. ERD Cloud 그림에는 소속 관계와 배정 흐름의 핵심 20개만 그림. 회원(누가 했는지)·AI 실행(추적용)·다른 길로 이미 이어진 참조와 알림의 이동 링크는 선을 생략 |
| 중복 방지 | REST `Idempotency-Key`는 중복 요청 방지 기록에 업무 변경과 같은 트랜잭션으로 저장. 문의 1:N 문제 → 문제당 티켓 최대 1개 (`ticket.inquiry_problem_id` 중복 불가) |
| 삭제 | 문의·티켓·처리 기록·해결 사례는 지우지 않고 보존 (최종기획서 8.3). 신규 프로필의 만료된 journal 응답 본문은 정리하되 키/digest/tombstone은 유지(6절). 전환 전 프로필의 정책과 구분 |
| 저장소 경계 | PostgreSQL은 사용자·시스템·주담당자·문의·티켓·승인·상태의 정본. 관계 탐색은 Neo4j, 의미 검색은 Vector 저장소, 로그인 세션·Refresh는 Redis. 신규 hops-system-1 SSE는 replay 없이 REST 재동기화, 전환 전 replay 프로필은 별도 Redis (최종기획서 9.1, 팀 합의 2026-10-28) |

## 3. 테이블별 컬럼

> **필수** ● = 비어 있으면 안 됨 · **참조** = 가리키는 테이블

### 계정 · 조직

#### 부서 `department`
임직원의 소속 부서를 관리해요. 문의는 부서가 아니라 시스템 주담당자에게 배정하므로 배정에는 쓰지 않아요. *(근거: 최종기획서 2.1)*

| 테이블명 | 컬럼명 | 물리명 | 타입 | 필수 | 기본값 | 참조 | 설명 (왜 쓰는지) |
|---|---|---|---|:-:|---|---|---|
| **부서** | 부서 ID | `id` | UUID | ● | uuidv7() |  | 부서 한 건을 구분하는 고유 번호 |
|  | 부서 코드 | `code` | VARCHAR(30) | ● |  |  | 소속 부서를 구분하는 고유 코드 (예: FINANCE) · 중복 불가 |
|  | 부서명 | `name` | VARCHAR(100) | ● |  |  | 헤더·문의자 정보에 소속 부서 이름을 보여주려고 |
|  | 생성일시 | `created_at` | TIMESTAMPTZ | ● | now() |  | 언제 만들어졌는지. 목록 정렬과 기간 조회에 쓰려고 |
|  | 수정일시 | `updated_at` | TIMESTAMPTZ | ● | now() |  | 마지막으로 언제 바뀌었는지 추적하려고 |
|  | 삭제일시 | `deleted_at` | TIMESTAMPTZ |  |  |  | 삭제 처리한 시각 (NULL이면 사용 중). 실제로 지우지 않고 기록을 남기려고 |

**현재 DB와의 차이:** `is_routable` 컬럼과 `AGENT_OPS_ADMIN` 역할은 V1 마이그레이션·seed·코드(`Department`, `DepartmentResponse`, `RealtimeAuthorization`, `Role`)와 전환 전 계약 `Department.isRoutable`에서 아직 사용 중이다. 현재 프론트의 시스템 권한과 구분하며 실제 제거는 후속 Flyway와 서버 DTO·권한 코드 전환에서 함께 한다. 이미 적용된 마이그레이션 파일은 고치지 않는다.

#### 회원 `member`
로그인 계정과 소속 부서를 관리해요. 담당자 여부는 소속 부서가 아니라 시스템의 주담당자 지정으로 판단해요. *(근거: 최종기획서 2.1, 3)*

| 테이블명 | 컬럼명 | 물리명 | 타입 | 필수 | 기본값 | 참조 | 설명 (왜 쓰는지) |
|---|---|---|---|:-:|---|---|---|
| **회원** | 회원 ID | `id` | UUID | ● | uuidv7() |  | 회원 한 건을 구분하는 고유 번호 |
|  | 로그인 ID | `login_id` | VARCHAR(100) | ● |  |  | 자체 로그인 아이디. 같은 아이디가 두 개 생기지 않게 · 중복 불가 |
|  | 사번 | `employee_no` | VARCHAR(100) | ● |  |  | JWT `employeeNo`의 원본 · 중복 불가. 회원 ID·로그인 ID와 별도. 최대100자·비어 있지 않은 고유 문자열, MVP에서 변경·퇴사자 사번 재사용 기능 없음 |
|  | 비밀번호 해시 | `password_hash` | VARCHAR(255) | ● |  |  | 로그인 확인용. 비밀번호 원문은 저장하지 않으려고 해시로 보관 |
|  | 이름 | `name` | VARCHAR(50) | ● |  |  | 헤더·요청자·담당자 이름을 화면에 보여주려고 |
|  | 소속 부서 ID | `department_id` | UUID | ● |  | 부서 | 헤더·문의자 정보에 소속을 보여주려고. 티켓 조회 권한과 배정에는 쓰지 않음 |
|  | 권한 문맥 버전 | `policy_revision` | INT | ● | 1 |  | 시스템 주담당자·역할이 바뀌면 영향받는 회원 행과 같은 거래에서 1씩 늘려, 예전 권한으로 만든 토큰·구독을 막으려고 (V21) |
|  | 생성일시 | `created_at` | TIMESTAMPTZ | ● | now() |  | 언제 만들어졌는지. 목록 정렬과 기간 조회에 쓰려고 |
|  | 수정일시 | `updated_at` | TIMESTAMPTZ | ● | now() |  | 마지막으로 언제 바뀌었는지 추적하려고 |
|  | 삭제일시 | `deleted_at` | TIMESTAMPTZ |  |  |  | 삭제 처리한 시각 (NULL이면 사용 중). 실제로 지우지 않고 기록을 남기려고 |

#### 역할 부여 `member_role`
업무 관리자처럼 부서·시스템 담당과 별개인 권한을 부여해요. *(근거: 최종기획서 3)*

| 테이블명 | 컬럼명 | 물리명 | 타입 | 필수 | 기본값 | 참조 | 설명 (왜 쓰는지) |
|---|---|---|---|:-:|---|---|---|
| **역할 부여** | 역할 부여 ID | `id` | UUID | ● | uuidv7() |  | 역할 부여 한 건을 구분하는 고유 번호 |
|  | 회원 ID | `member_id` | UUID | ● |  | 회원 | 누구에게 준 권한인지 알려고 |
|  | 역할 | `role` | VARCHAR(30) | ● |  |  | 업무 관리자 화면(수동 시스템 정정·재배정 지원) 접근을 막고 열려고 (BIZ_ADMIN) |
|  | 생성일시 | `created_at` | TIMESTAMPTZ | ● | now() |  | 언제 만들어졌는지. 목록 정렬과 기간 조회에 쓰려고 |
|  | 수정일시 | `updated_at` | TIMESTAMPTZ | ● | now() |  | 마지막으로 언제 바뀌었는지 추적하려고 |
|  | 삭제일시 | `deleted_at` | TIMESTAMPTZ |  |  |  | 삭제 처리한 시각 (NULL이면 사용 중). 실제로 지우지 않고 기록을 남기려고 |

중복 불가 조합: (회원 ID, 역할)

문의자는 별도 역할 없이 모든 회원이다. 시스템 주담당자(PRIMARY)도 역할이 아니라 시스템의 주담당자 지정으로 판단한다. 에이전트 운영 관리자(`AGENT_OPS_ADMIN`)는 최종기획서 3의 역할에 없어 뺀다.

JWT 매핑: `sub`는 회원 ID, `employeeNo`는 사번, `departmentId/departmentCode/departmentName`은 현재 소속 부서의 ID·코드·이름, `roles`는 역할 부여에서 가져온다. 부서명으로 권한을 비교하거나 사용자 소속으로 티켓을 배정하지 않는다. 부서·역할 변경 시 토큰을 무효화하고 열린 SSE를 종료하는 규칙은 신규 WIRE12절과 DATA11절을 따른다.

JWT 사용만으로 인증 테이블을 추가하지 않는다. 로그인 세션·Refresh 검증값과 전환 전 프로필의 SSE replay 이벤트 이력은 Redis에 두고, 요청 멱등은 중복 요청 방지 기록 테이블에 둔다 (팀 합의 2026-10-28). SSE 연결과 eventId는 업무 문의와 별개이며, 채팅 원본은 `inquiry/message/message_revision`에 보존한다.

### 시스템

#### 시스템 `it_system`
문의를 받는 업무시스템과 시스템별 주담당자를 관리해요. 문의는 부서가 아니라 시스템의 주담당자에게 배정해요. *(근거: 최종기획서 2.1, 2.2, 3)*

| 테이블명 | 컬럼명 | 물리명 | 타입 | 필수 | 기본값 | 참조 | 설명 (왜 쓰는지) |
|---|---|---|---|:-:|---|---|---|
| **시스템** | 시스템 ID | `id` | UUID | ● | uuidv7() |  | 시스템 한 건을 구분하는 고유 번호 |
|  | 시스템 코드 | `code` | VARCHAR(30) | ● |  |  | AI가 시스템 후보를 제안할 때 쓰는 고유 코드 (ELECTRONIC_APPROVAL / COMMON_LOGIN / FILE_SHARING) · 중복 불가 |
|  | 시스템명 | `name` | VARCHAR(100) | ● |  |  | 채팅의 시스템 확인 질문·티켓 목록에 시스템 이름을 보여주려고 (예: 전자결재) |
|  | 지원 범위 설명 | `description` | TEXT |  |  |  | 사용자가 시스템을 고르지 않았을 때 AI가 문의 내용과 비교해 시스템 후보를 고르려고 |
|  | 주담당자 ID | `primary_owner_member_id` | UUID | ● |  | 회원 | 이 시스템 문의를 자동 배정받는 주담당자(PRIMARY) 1명. 한 회원이 여러 시스템을 맡을 수 있음 |
|  | 담당 세대 | `ownership_version` | INT | ● | 1 |  | 주담당자가 바뀔 때마다 1씩 늘려 권한을 검증하려고. 이미 배정된 티켓의 담당자는 바꾸지 않음 |
|  | 생성일시 | `created_at` | TIMESTAMPTZ | ● | now() |  | 언제 만들어졌는지. 목록 정렬과 기간 조회에 쓰려고 |
|  | 수정일시 | `updated_at` | TIMESTAMPTZ | ● | now() |  | 마지막으로 언제 바뀌었는지 추적하려고 |
|  | 삭제일시 | `deleted_at` | TIMESTAMPTZ |  |  |  | 삭제 처리한 시각 (NULL이면 사용 중). 실제로 지우지 않고 기록을 남기려고 |

주담당자는 시스템당 1명이고 담당자 없음·중복·만료는 정상 데이터에 없다고 전제해 별도 담당 관계 테이블을 두지 않는다 (최종기획서 3). 외부 현재 담당 DTO는 systemId/primaryUser다. 권한 세대는 6절 ownership_version·member.policy_revision으로 관리하고 당시 티켓 배정은 ticket_assignment에 보존한다. 부담당자·별도 담당 관계 Entity는 두지 않는다. 지원 시스템 목록은 삭제되지 않은 행이며, 목록에 없는 시스템의 문의는 범위 밖으로 안내한다 (최종기획서 5.1).

#### 시스템 기능 `system_feature`
시스템 안의 기능이에요. 사용자가 어디에서 현상을 겪었는지, CASE가 어느 기능 이야기인지 가리켜요. *(근거: 최종기획서 2.2, 4.1, 8.4, 11.1)*

| 테이블명 | 컬럼명 | 물리명 | 타입 | 필수 | 기본값 | 참조 | 설명 (왜 쓰는지) |
|---|---|---|---|:-:|---|---|---|
| **시스템 기능** | 시스템 기능 ID | `id` | UUID | ● | uuidv7() |  | 시스템 기능 한 건을 구분하는 고유 번호 |
|  | 시스템 ID | `it_system_id` | UUID | ● |  | 시스템 | 어느 시스템의 기능인지 묶으려고 |
|  | 기능 코드 | `code` | VARCHAR(50) | ● |  |  | AI가 보고 기능을 제안할 때 쓰는 코드 (예: DOCUMENT_VIEW) |
|  | 기능명 | `name` | VARCHAR(100) | ● |  |  | 표준 요청·티켓·CASE에 기능 이름을 보여주려고 (예: 문서 조회) |
|  | 기능 범위 설명 | `description` | TEXT |  |  |  | 비슷한 기능끼리 책임 경계를 구분해 AI가 기능을 고르려고 |
|  | 생성일시 | `created_at` | TIMESTAMPTZ | ● | now() |  | 언제 만들어졌는지. 목록 정렬과 기간 조회에 쓰려고 |
|  | 수정일시 | `updated_at` | TIMESTAMPTZ | ● | now() |  | 마지막으로 언제 바뀌었는지 추적하려고 |
|  | 삭제일시 | `deleted_at` | TIMESTAMPTZ |  |  |  | 삭제 처리한 시각 (NULL이면 사용 중). 실제로 지우지 않고 기록을 남기려고 |

중복 불가 조합: (시스템 ID, 기능 코드)

기능 → 증상 → 가능한 원인 같은 관계는 Neo4j에 두고, 이 테이블은 기능 목록의 정본만 맡는다 (최종기획서 9.1, 9.2).

### 문의 · 대화 · 문제

#### 문의 `inquiry`
대화 세션 1개 = 문의 1건이에요. 종합 상태·AI 요약과 정정 충돌을 막는 문의 버전을 담아요. *(근거: 최종기획서 5.3, 6)*

| 테이블명 | 컬럼명 | 물리명 | 타입 | 필수 | 기본값 | 참조 | 설명 (왜 쓰는지) |
|---|---|---|---|:-:|---|---|---|
| **문의** | 문의 ID | `id` | UUID | ● | uuidv7() |  | 문의 한 건을 구분하는 고유 번호 |
|  | 문의 번호 | `inquiry_number` | VARCHAR(20) | ● |  |  | 사용자·담당자가 부르기 쉬운 문의 번호 (Q-0142) · 중복 불가 |
|  | 문의자 ID | `requester_member_id` | UUID | ● |  | 회원 | 본인 문의만 보이게 하고, 알림 받을 사람을 찾으려고 |
|  | 제목 | `title` | VARCHAR(200) | ● |  |  | 내 문의 목록에 보여줄 제목 (첫 입력 → AI 요약 제목) |
|  | 문의 상태 | `inquiry_status` | VARCHAR(30) | ● |  |  | 내 문의 목록 상태 필터와 종합 상태 표시용 (IN_PROGRESS / WAITING_USER / RESOLVED / CLOSED). CLOSED는 모든 문제가 범위 밖이고 범위 안내를 저장한 문의에만 쓰며 무응답 자동 종료가 아님 |
|  | 문의 버전 | `version` | INT | ● | 1 |  | 메시지 추가·정정 때마다 1씩 늘려, 예전 버전으로 분석한 늦은 AI 결과를 버리려고 |
|  | 현재 상담 실행 ID | `current_run_id` | UUID |  |  | AI 실행 | 지금 이 문의를 분석 중인(마지막으로 시작한) 상담 실행. 현재 입력 버전과 실행이 둘 다 맞는 결과만 적용하려고. Curator 실행은 가리키지 않음 |
|  | 결과 차수 | `result_revision` | INT | ● | 1 |  | 티켓 생성 결과나 해결 응답이 바뀔 때 1씩 늘려, 화면이 최신 결과를 받았는지 확인하려고 |
|  | AI 요약 | `summary` | TEXT |  |  |  | 문의 상세에서 대화를 다 읽지 않아도 내용을 알 수 있게 |
|  | 요약 반영 순번 | `summary_until_seq` | INT |  |  |  | 요약이 대화 어디까지 반영됐는지 표시하려고 |
|  | 요약 기준일시 | `summary_updated_at` | TIMESTAMPTZ |  |  |  | 문의 상세에 'AI 요약 · 10.17 09:05 기준'처럼 요약이 언제 기준인지 보여주려고 |
|  | 생성일시 | `created_at` | TIMESTAMPTZ | ● | now() |  | 언제 만들어졌는지. 목록 정렬과 기간 조회에 쓰려고 |
|  | 수정일시 | `updated_at` | TIMESTAMPTZ | ● | now() |  | 마지막으로 언제 바뀌었는지 추적하려고 |
|  | 삭제일시 | `deleted_at` | TIMESTAMPTZ |  |  |  | 삭제 처리한 시각 (NULL이면 사용 중). 실제로 지우지 않고 기록을 남기려고 |

메시지 추가·정정은 요청의 expectedInquiryVersion과 `version`을 원자적으로 비교한 뒤 증가시킨다. 티켓의 `version`(초기값 0)은 티켓 동시 수정 방지용이라 문의 버전과 섞어 쓰지 않는다. 해결 확인은 문제마다 따로 받으므로 문의 문제에 둔다 (최종기획서 5.4). 예약 재알림·무응답 자동 종료는 없다 (최종기획서 6).

#### 대화 메시지 `message`
문의 안의 대화(사용자·AI 메시지)를 순서대로 저장해요. 표준 요청서로 정리해도 원문은 그대로 보존해요. *(근거: 최종기획서 4.5)*

| 테이블명 | 컬럼명 | 물리명 | 타입 | 필수 | 기본값 | 참조 | 설명 (왜 쓰는지) |
|---|---|---|---|:-:|---|---|---|
| **대화 메시지** | 대화 메시지 ID | `id` | UUID | ● | uuidv7() |  | 대화 메시지 한 건을 구분하는 고유 번호 |
|  | 문의 ID | `inquiry_id` | UUID | ● |  | 문의 | 어느 문의의 대화인지 묶으려고 |
|  | 메시지 순번 | `sequence_no` | INT | ● |  |  | 대화를 보낸 순서대로 보여주려고 (같은 시각이어도 순서 보장) |
|  | 발신자 구분 | `sender_type` | VARCHAR(30) | ● |  |  | 말풍선을 사용자·AI로 나눠 보여주려고 (USER / AI) |
|  | 내용 | `content` | TEXT | ● |  |  | 대화 내용을 보여주고 AI 분석에 넘기려고 (최대 4,000자) |
|  | 현재 차수 | `revision` | INT | ● | 1 |  | 현재 내용이 몇 번째 수정본인지 (1 이상). 정정할 때마다 1씩 늘리고 과거 원문은 메시지 수정 이력에 둠 |
|  | 화면 표시 데이터 | `payload` | JSONB | ● | '{}' |  | 요청 ID·실행 ID·문제 연결·질문 묶음·가이드 같은 서버 관리 메타데이터와 모양이 있는 답변을 그리려고 (구조는 DATA 11.3절) |
|  | 상태 | `message_status` | VARCHAR(30) | ● |  |  | 답변 생성 중·완료·실패를 구분하려고 (GENERATING / COMPLETED / FAILED). 신규 계약의 저장 행은 COMPLETED이며 AI 답변은 예약한 answerMessageId를 이 행의 ID로 써서 완성 후 한 번 저장 |
|  | 생성일시 | `created_at` | TIMESTAMPTZ | ● | now() |  | 언제 만들어졌는지. 목록 정렬과 기간 조회에 쓰려고 |
|  | 수정일시 | `updated_at` | TIMESTAMPTZ | ● | now() |  | 마지막으로 언제 바뀌었는지 추적하려고 |
|  | 삭제일시 | `deleted_at` | TIMESTAMPTZ |  |  |  | 삭제 처리한 시각 (NULL이면 사용 중). 실제로 지우지 않고 기록을 남기려고 |

중복 불가 조합: (문의 ID, 메시지 순번)

#### 메시지 수정 이력 `message_revision`
사용자가 메시지를 고쳤을 때 고치기 전 원문을 남겨요. *(근거: 최종기획서 5.3)*

| 테이블명 | 컬럼명 | 물리명 | 타입 | 필수 | 기본값 | 참조 | 설명 (왜 쓰는지) |
|---|---|---|---|:-:|---|---|---|
| **메시지 수정 이력** | 수정 이력 ID | `id` | UUID | ● | uuidv7() |  | 메시지 수정 이력 한 건을 구분하는 고유 번호 |
|  | 대화 메시지 ID | `message_id` | UUID | ● |  | 대화 메시지 | 어느 메시지를 고쳤는지 알려고 |
|  | 수정 차수 | `revision_no` | INT | ● |  |  | 몇 번째 수정인지 순서를 매기려고 |
|  | 수정 전 내용 | `content` | TEXT | ● |  |  | 고치기 전 원문을 남겨 '이전 내용 보기'와 AI 판단 추적에 쓰려고 |
|  | 생성일시 | `created_at` | TIMESTAMPTZ | ● | now() |  | 언제 만들어졌는지. 목록 정렬과 기간 조회에 쓰려고 |

중복 불가 조합: (대화 메시지 ID, 수정 차수)

#### 문의 문제 `inquiry_problem`
복합 문의를 나눈 '문제' 단위예요. 문제마다 추가 질문 횟수·티켓 생성·무티켓 해결 확인을 따로 관리해요. *(근거: 최종기획서 5.1, 5.4, 6)*

| 테이블명 | 컬럼명 | 물리명 | 타입 | 필수 | 기본값 | 참조 | 설명 (왜 쓰는지) |
|---|---|---|---|:-:|---|---|---|
| **문의 문제** | 문제 ID | `id` | UUID | ● | uuidv7() |  | 문의 문제 한 건을 구분하는 고유 번호 |
|  | 문의 ID | `inquiry_id` | UUID | ● |  | 문의 | 한 문의에서 나온 여러 문제를 묶으려고 |
|  | 문제 번호 | `problem_no` | INT | ● |  |  | 복합 문의를 나눈 문제의 순서 (문제 1, 문제 2 …) |
|  | 제목 | `title` | VARCHAR(200) | ● |  |  | 채팅에서 문제별 결과를 구분해 보여주려고 |
|  | 설명 | `detail` | TEXT |  |  |  | AI가 파악한 문제 내용을 채팅의 문제별 결과에 보여주려고 |
|  | 문제 상태 | `problem_status` | VARCHAR(30) | ● |  |  | 문제마다 어디까지 왔는지 구분하려고 (CLARIFYING 추가 질문 중 / SYSTEM_PENDING 시스템 확인 대기 / GUIDED 안내 후 해결 확인 대기 / RESOLVED_WITHOUT_TICKET 무티켓 해결 / TICKET_CREATED 티켓 생성 / TICKET_FAILED 티켓 생성 실패 / OUT_OF_SCOPE 범위 밖) |
|  | 추가 질문 횟수 | `clarify_count` | INT | ● | 0 |  | 문제당 질문 묶음 최대 5회를 지키려고. 5회 뒤 시스템을 알면 누락 항목과 함께 티켓으로, 모르면 시스템 확인 대기로 |
|  | 티켓 생성 오류 코드 | `ticket_error_code` | VARCHAR(50) |  |  |  | 티켓 생성이 실패한 원인. 성공한 티켓은 두고 실패한 문제만 다시 만들려고 |
|  | 티켓 생성 결과 | `creation_outcome` | VARCHAR(30) |  |  |  | 티켓 생성 작업이 어디까지 됐는지 (PENDING / STORED / NOT_CREATED / UNKNOWN, 작업이 없으면 NULL). 저장 여부를 모르면 실패로 단정하지 않고 UNKNOWN으로 두려고 |
|  | 생성 기준 문의 버전 | `creation_input_version` | INT |  |  |  | 티켓 생성을 시도한 문의 버전 |
|  | 확인 요청일시 | `confirm_requested_at` | TIMESTAMPTZ |  |  |  | 티켓 없이 안내한 뒤 문의자에게 해결 확인을 요청한 시각 |
|  | 해결일시 | `resolved_at` | TIMESTAMPTZ |  |  |  | 문의자가 티켓 없이 해결됐다고 확인한 시각 (확인 전이면 NULL) |
|  | AI 실행 ID | `agent_run_id` | UUID |  |  | AI 실행 | 이 문제를 판단한 AI 실행을 추적하려고 |
|  | 생성일시 | `created_at` | TIMESTAMPTZ | ● | now() |  | 언제 만들어졌는지. 목록 정렬과 기간 조회에 쓰려고 |
|  | 수정일시 | `updated_at` | TIMESTAMPTZ | ● | now() |  | 마지막으로 언제 바뀌었는지 추적하려고 |
|  | 삭제일시 | `deleted_at` | TIMESTAMPTZ |  |  |  | 삭제 처리한 시각 (NULL이면 사용 중). 실제로 지우지 않고 기록을 남기려고 |

중복 불가 조합: (문의 ID, 문제 번호)

- 의도·처리 유형·시스템·확인 사실은 표준 요청서에 버전별로 두고 이 테이블에는 진행 상태만 둔다.
- 시스템을 끝까지 알 수 없으면 배정하지 않고 `SYSTEM_PENDING`으로 보존한다. 이때 자유 질문을 더 하지 않고 지원 시스템 선택을 요청한다 (최종기획서 5.1).
- 티켓 생성이 실패하면 `TICKET_FAILED`와 오류 코드를 남긴다. 다시 만들 때 티켓의 문제 ID 중복 불가가 같은 문제의 티켓 중복을 막는다 (최종기획서 5.4). 미생성이 서버에서 확인된 NOT_CREATED 작업만 문의 소유자가 같은 workItemId로 재시도한다. UNKNOWN은 저장 결과를 먼저 확인하며 F010·WIRE12절을 따른다.

#### 표준 요청서 `standard_request`
문제별 대화를 담당자가 바로 일할 수 있는 요청서로 정리해요. 원문은 대화 메시지에 그대로 두고, 문의 버전마다 새 행을 추가해 정정 전 정리도 남겨요. *(근거: 최종기획서 1.3, 4.1~4.5, 5.1, 7.1)*

| 테이블명 | 컬럼명 | 물리명 | 타입 | 필수 | 기본값 | 참조 | 설명 (왜 쓰는지) |
|---|---|---|---|:-:|---|---|---|
| **표준 요청서** | 표준 요청서 ID | `id` | UUID | ● | uuidv7() |  | 표준 요청서 한 건을 구분하는 고유 번호 |
|  | 문제 ID | `inquiry_problem_id` | UUID | ● |  | 문의 문제 | 어느 문제를 정리한 요청서인지 |
|  | 문의 버전 | `inquiry_version` | INT | ● |  |  | 어느 문의 버전까지의 대화로 정리했는지. 정정되거나 같은 버전을 새로 분석하면 새 행을 추가하고 최신 적용 실행의 행을 사용 |
|  | AI 실행 ID | `agent_run_id` | UUID | ● |  | AI 실행 | 이 요청서를 정리한 AI 실행. 출처를 남기려고 필수 |
|  | 문의 의도 | `intent` | VARCHAR(30) | ● |  |  | 사용자가 무엇을 원하는지 (HOW_TO / ACCESS_REQUEST / OBSERVED_SYMPTOM / FEATURE_REQUEST / IMPROVEMENT_REQUEST / UNKNOWN / OUT_OF_SCOPE) |
|  | 처리 유형 | `work_type` | VARCHAR(30) | ● |  |  | 담당자가 어떤 업무를 할지 (KNOWLEDGE_QA / SERVICE_REQUEST / INVESTIGATION / INCIDENT / CHANGE_REQUEST / REVIEW_REQUIRED / OUT_OF_SCOPE) |
|  | 보고 시스템 ID | `reported_system_id` | UUID |  |  | 시스템 | 사용자가 현상을 겪은 시스템. 아직 모르면 NULL이고 시스템 확인 질문 대상 |
|  | 보고 기능 ID | `reported_feature_id` | UUID |  |  | 시스템 기능 | 사용자가 현상을 겪은 화면·기능 (모르면 NULL) |
|  | 책임 시스템 ID | `responsible_system_id` | UUID |  |  | 시스템 | 이 요청을 맡을 시스템. Spring이 이 시스템의 주담당자에게 배정. 확인 전이면 NULL |
|  | 시스템 후보·근거 | `system_candidates` | JSONB | ● | '[]' |  | 시스템을 확정하기 전 AI가 본 후보와 근거를 남겨 오배정 정정에 쓰려고 |
|  | 요약 | `summary` | TEXT | ● |  |  | 담당자가 대화를 다 읽지 않아도 요청을 알 수 있게 |
|  | 확인한 사실 | `confirmed_facts` | JSONB | ● | '[]' |  | 사용자가 직접 말한 사실과 출처 메시지. 이미 확인한 내용을 다시 묻지 않으려고 |
|  | 추정 | `assumptions` | JSONB | ● | '[]' |  | AI가 추정한 내용을 확인한 사실과 섞지 않으려고 |
|  | 미확인 항목 | `missing_items` | JSONB | ● | '[]' |  | 아직 받지 못한 필수·조건부 정보. 다음 질문을 고르고, 질문 5회 뒤에는 담당자에게 누락으로 보여주려고 |
|  | 유형별 정보 | `type_detail` | JSONB | ● | '{}' |  | 현상은 기대/실제 동작·발생 시점, 기능 요청은 AS-IS·문제점·TO-BE·수용 조건처럼 의도마다 다른 항목을 담으려고 |
|  | 관련 지식 | `related_knowledge` | JSONB | ● | '[]' |  | 검색된 지식·해결 사례와 적용 조건·차이점을 담당자에게 함께 전달하려고 |
|  | 생성일시 | `created_at` | TIMESTAMPTZ | ● | now() |  | 언제 만들어졌는지. 목록 정렬과 기간 조회에 쓰려고 |

중복 불가 조합: (문제 ID, 문의 버전, AI 실행 ID). 같은 입력 버전의 새 run은 새 불변 스냅샷을 만들고 현재 포인터는 최신 적용 run에서만 선택한다.

추가만 하는 이력 테이블이라 수정·삭제일시를 두지 않는다. 사용자에게 알 수 없는 정보를 강요하거나 미확인 값을 모델 추정으로 채우지 않도록 확인한 사실·추정·미확인 항목을 분리한다 (최종기획서 4.4). 기획서 4.5의 티켓 제목·내용과 PRIMARY 배정 결과는 티켓·배정 이력에 저장한다. 필수·null·JSONB 구조는 DATA11.3절과 신규 DATA_TYPES·AI Schema를 따른다.


### 티켓 · 배정 · 처리

#### 티켓 `ticket`
책임 시스템의 주담당자가 처리할 일 1건이에요. 문제 1개에서 티켓은 최대 1개만 만들어요. *(근거: 최종기획서 5.2, 5.4, 6)*

| 테이블명 | 컬럼명 | 물리명 | 타입 | 필수 | 기본값 | 참조 | 설명 (왜 쓰는지) |
|---|---|---|---|:-:|---|---|---|
| **티켓** | 티켓 ID | `id` | UUID | ● | uuidv7() |  | 티켓 한 건을 구분하는 고유 번호 |
|  | 티켓 번호 | `ticket_number` | VARCHAR(20) | ● |  |  | 담당자·사용자가 부르기 쉬운 티켓 번호 (TK-0001) · 중복 불가 |
|  | 문의 ID | `inquiry_id` | UUID | ● |  | 문의 | 티켓이 어느 문의에서 생겼는지. 문의 상세의 연결 티켓 목록 |
|  | 문제 ID | `inquiry_problem_id` | UUID | ● |  | 문의 문제 | 어느 문제의 티켓인지. 같은 문제로 티켓이 두 번 생기지 않게 · 중복 불가 |
|  | 표준 요청서 ID | `standard_request_id` | UUID | ● |  | 표준 요청서 | 어느 버전의 표준 요청서로 만든 티켓인지. 담당자에게 확인 사실·미확인 항목·관련 지식을 보여주려고 |
|  | 제목 | `title` | VARCHAR(200) | ● |  |  | 담당 시스템 티켓 목록·알림에 보여줄 제목 |
|  | 설명 | `detail` | TEXT | ● |  |  | 담당자가 다시 묻지 않도록 AI가 정리한 요청 내용 |
|  | 처리 유형 | `work_type` | VARCHAR(30) | ● |  |  | 담당자가 어떤 업무를 할지 (SERVICE_REQUEST / INVESTIGATION / INCIDENT / CHANGE_REQUEST / REVIEW_REQUIRED). 장애 확정은 담당자가 INVESTIGATION → INCIDENT로 바꿈 |
|  | 처리 상태 | `ticket_status` | VARCHAR(30) | ● | 'OPEN' |  | 담당자 목록 필터와 해결 확인 흐름용 (OPEN / IN_PROGRESS / WAITING_CONFIRM / RESOLVED) |
|  | 책임 시스템 ID | `responsible_system_id` | UUID | ● |  | 시스템 | 이 티켓을 맡은 시스템. 담당자 정정 시 바뀌고, 시스템별 티켓 목록과 조회 권한의 기준 |
|  | 담당자 ID | `assignee_member_id` | UUID | ● |  | 회원 | 배정 시점의 책임 시스템 주담당자. 나중에 주담당자가 바뀌어도 기존 티켓 담당은 그대로 남음 |
|  | 확인 요청일시 | `confirm_requested_at` | TIMESTAMPTZ |  |  |  | 담당자가 해결·구현 기록을 남기고 문의자에게 해결 확인을 요청한 시각 |
|  | 해결일시 | `resolved_at` | TIMESTAMPTZ |  |  |  | 문의자가 해결을 확인해 최종 완료된 시각 (확인 전이면 NULL) |
|  | 버전 | `version` | INT | ● | 0 |  | 두 사람이 동시에 고칠 때 늦은 쪽 변경을 거절하려고. 0~2,147,483,646만 허용(CHECK)하고 외부에는 version+1을 ticketRevision으로 보냄 |
|  | 생성일시 | `created_at` | TIMESTAMPTZ | ● | now() |  | 언제 만들어졌는지. 목록 정렬과 기간 조회에 쓰려고 |
|  | 수정일시 | `updated_at` | TIMESTAMPTZ | ● | now() |  | 마지막으로 언제 바뀌었는지 추적하려고 |
|  | 삭제일시 | `deleted_at` | TIMESTAMPTZ |  |  |  | 삭제 처리한 시각 (NULL이면 사용 중). 실제로 지우지 않고 기록을 남기려고 |

- **상태 흐름:** `OPEN`(접수·배정) → `IN_PROGRESS`(처리 중) → `WAITING_CONFIRM`(해결·구현 기록 후 문의자 확인 대기) → `RESOLVED`(최종 완료). 문의자가 미해결이라고 하면 같은 티켓을 `IN_PROGRESS`로 되돌린다. 무응답이면 `WAITING_CONFIRM`을 유지하고, 완료 후 재오픈은 하지 않는다 (최종기획서 6).
- **배정:** 선점·수락 없이 생성과 동시에 책임 시스템의 주담당자에게 배정한다 (최종기획서 3, 5.2). 배정·수동 정정 내역은 배정 이력에 남긴다.
- 처리 내용은 티켓 처리 기록에, 확인 사실·추정·미확인 항목은 표준 요청서에 둔다. 우선순위·장애 그룹·재알림·해결 방식 집계는 이번 범위 밖이다 (최종기획서 13.3).

#### 티켓 변경 이력 `ticket_event`
티켓의 생성·상태 변경·내용 정정·해결 확인 응답 이력을 추가만 해서 남겨요. *(근거: 최종기획서 4.3, 5.3, 6)*

| 테이블명 | 컬럼명 | 물리명 | 타입 | 필수 | 기본값 | 참조 | 설명 (왜 쓰는지) |
|---|---|---|---|:-:|---|---|---|
| **티켓 변경 이력** | 변경 이력 ID | `id` | UUID | ● | uuidv7() |  | 티켓 변경 이력 한 건을 구분하는 고유 번호 |
|  | 티켓 ID | `ticket_id` | UUID | ● |  | 티켓 | 어느 티켓의 이력인지 |
|  | 이벤트 유형 | `event_type` | VARCHAR(30) | ● |  |  | 무슨 일이 있었는지 (CREATED 생성 / STATUS_CHANGED 상태 변경 / WORK_TYPE_CHANGED 처리 유형 변경·장애 확정 / CONTENT_CORRECTED AI 내용 정정 / SUPPLEMENT_ADDED 배정 후 보완 정보 / USER_CONFIRMED 해결 확인 / USER_NOT_RESOLVED 미해결 응답) |
|  | 변경자 구분 | `actor_type` | VARCHAR(30) | ● |  |  | 사람·AI·시스템 중 누가 바꿨는지 구분하려고 (MEMBER / AI / SYSTEM) |
|  | 변경자 ID | `actor_member_id` | UUID |  |  | 회원 | 사람이 바꿨다면 누구인지 (감사 기록) |
|  | AI 실행 ID | `agent_run_id` | UUID |  |  | AI 실행 | AI가 바꿨다면 어느 실행인지 추적하려고 |
|  | 변경 전 값 | `before_data` | JSONB |  |  |  | 바뀌기 전 값. 정정해도 AI가 처음 쓴 내용을 남기려고 |
|  | 변경 후 값 | `after_data` | JSONB |  |  |  | 바뀐 뒤 값을 이력 화면에 보여주려고 |
|  | 사유 | `reason` | VARCHAR(1000) |  |  |  | 정정·처리 유형 변경 이유를 남기려고 (사람이 정정할 때 필수 입력) |
|  | 생성일시 | `created_at` | TIMESTAMPTZ | ● | now() |  | 언제 만들어졌는지. 목록 정렬과 기간 조회에 쓰려고 |

담당 시스템·담당자 변경은 담당 배정 이력에 남기고 이 테이블에는 넣지 않는다. 배정 이후 사용자가 보낸 메시지는 새 티켓·재배정 없이 `SUPPLEMENT_ADDED`로 기존 티켓에 연결한다 (최종기획서 5.3). 선점·반환·재오픈은 이번 범위 밖이다 (최종기획서 3, 6).

#### 담당 배정 이력 `ticket_assignment`
티켓이 어느 시스템의 어느 담당자에게 언제 배정됐는지, 누가 왜 정정했는지 남겨요. *(근거: 최종기획서 3, 5.2)*

| 테이블명 | 컬럼명 | 물리명 | 타입 | 필수 | 기본값 | 참조 | 설명 (왜 쓰는지) |
|---|---|---|---|:-:|---|---|---|
| **담당 배정 이력** | 배정 이력 ID | `id` | UUID | ● | uuidv7() |  | 담당 배정 이력 한 건을 구분하는 고유 번호 |
|  | 티켓 ID | `ticket_id` | UUID | ● |  | 티켓 | 어느 티켓의 배정 기록인지 |
|  | 책임 시스템 ID | `it_system_id` | UUID | ● |  | 시스템 | 배정 당시 이 티켓을 맡은 시스템. 시스템 정정 전후를 비교하려고 |
|  | 배정 담당자 ID | `assignee_member_id` | UUID | ● |  | 회원 | 배정 당시 그 시스템의 주담당자 |
|  | 배정 출처 | `source` | VARCHAR(30) | ● |  |  | 최초 자동 배정과 수동 정정을 구분하려고 (AUTO / MANUAL_CORRECTION) |
|  | 정정자 ID | `assigned_by_member_id` | UUID |  |  | 회원 | 담당자나 업무 관리자가 수동 정정했다면 누구인지 (자동 배정이면 NULL) |
|  | 배정 사유 | `reason` | VARCHAR(1000) | ● |  |  | 자동 배정은 AI가 본 시스템 근거, 수동 정정은 정정 근거와 사유. 오배정 검토에 쓰려고 |
|  | 배정 근거 | `evidence` | JSONB | ● | '{"messageIds":[],"note":null}' |  | 배정 근거로 본 메시지와 메모. 정정 전후 근거를 비교하려고 (구조는 DATA 11.3절) |
|  | 배정일시 | `assigned_at` | TIMESTAMPTZ | ● |  |  | 배정된 시각 |
|  | 배정 종료일시 | `ended_at` | TIMESTAMPTZ |  |  |  | 정정으로 다른 시스템·담당자에게 넘어간 시각. NULL이면 현재 배정 |
|  | 생성일시 | `created_at` | TIMESTAMPTZ | ● | now() |  | 언제 만들어졌는지. 목록 정렬과 기간 조회에 쓰려고 |
|  | 수정일시 | `updated_at` | TIMESTAMPTZ | ● | now() |  | 마지막으로 언제 바뀌었는지 추적하려고 |
|  | 삭제일시 | `deleted_at` | TIMESTAMPTZ |  |  |  | 삭제 처리한 시각 (NULL이면 사용 중). 실제로 지우지 않고 기록을 남기려고 |

- 티켓을 만들 때 `AUTO` 배정 1건을 같은 트랜잭션으로 저장한다. 수동 정정은 현재 배정의 `ended_at`을 채우고 새 `MANUAL_CORRECTION` 배정을 추가한 뒤, 티켓의 책임 시스템·담당자를 같은 트랜잭션으로 바꾼다. 현재 배정(`ended_at`이 NULL)은 티켓당 1건이며 부분 유일 인덱스 `uk_ticket_assignment_current`로 보장한다.
- 자동 재이관은 없다. 다른 시스템과의 의존 관계가 검색돼도 최초 담당자를 자동으로 바꾸지 않는다 (최종기획서 5.2).

#### 티켓 처리 기록 `ticket_work_log`
담당자가 한 조치·실패한 시도·해결·기능 구현을 추가만 해서 남겨요. 해결·구현 기록이 저장되면 Curator가 사례 초안을 만들어요. *(근거: 최종기획서 6, 7.3, 8.2, 8.3, 8.4)*

| 테이블명 | 컬럼명 | 물리명 | 타입 | 필수 | 기본값 | 참조 | 설명 (왜 쓰는지) |
|---|---|---|---|:-:|---|---|---|
| **티켓 처리 기록** | 처리 기록 ID | `id` | UUID | ● | uuidv7() |  | 티켓 처리 기록 한 건을 구분하는 고유 번호 |
|  | 티켓 ID | `ticket_id` | UUID | ● |  | 티켓 | 어느 티켓의 처리 기록인지 |
|  | 작성 담당자 ID | `author_member_id` | UUID | ● |  | 회원 | 누가 조치하고 기록했는지. 사례 작성자로도 이어짐 |
|  | 기록 종류 | `log_type` | VARCHAR(30) | ● |  |  | 진행 중 조치·실패한 시도·해결 완료·기능 구현 완료를 구분하려고 (PROGRESS / FAILED_ATTEMPT / RESOLVED / IMPLEMENTED) |
|  | 조치 내용 | `action_detail` | TEXT | ● |  |  | 실제로 한 조치·변경. 해결 확인 요청 알림과 사례 초안의 재료 |
|  | 확인된 원인 | `confirmed_cause` | TEXT |  |  |  | 담당자가 근거로 확인한 원인. 확인하지 못했으면 NULL로 두고 모델이 채우지 않음 |
|  | 검증 결과 | `verification_result` | TEXT |  |  |  | 조치 후 다시 확인한 결과. RESOLVED·IMPLEMENTED 기록이면 비어 있으면 안 됨(CHECK). 검증하지 않은 조치를 완료로 남기지 않으려고 |
|  | 적용 버전 | `applied_version` | VARCHAR(100) |  |  |  | 기능 구현이 반영된 시스템 버전. 기능 구현 사례에 적용 버전을 남기려고 |
|  | 생성일시 | `created_at` | TIMESTAMPTZ | ● | now() |  | 언제 만들어졌는지. 목록 정렬과 기간 조회에 쓰려고 |

추가만 하는 이력 테이블이라 수정·삭제일시를 두지 않는다. 고칠 내용은 새 기록으로 남기며, 사례는 만들어질 때의 처리 기록 ID를 가리켜 어느 기록에서 나왔는지 고정한다 (최종기획서 8.4 "연결 처리 기록 버전").

- `RESOLVED`·`IMPLEMENTED` 기록이 저장되면 티켓은 문의자 해결 확인 대기로 바뀌고 Curator를 자동 실행한다 (최종기획서 6, 7.3). Curator가 실패해도 이 기록과 티켓 상태는 취소하지 않는다.
- `PROGRESS`·`FAILED_ATTEMPT`는 처리 이력으로만 보존하고 사례를 만들지 않는다 (최종기획서 8.3 "실패 CASE 지식은 만들지 않는다").

### AI 실행

#### AI 실행 `agent_run`
상담 분석 또는 Curator 지식화 1회 실행 기록이에요. 실행 추적과 응답 시간 평가에 써요. *(근거: 최종기획서 7.2, 7.3, 12.2)*

| 테이블명 | 컬럼명 | 물리명 | 타입 | 필수 | 기본값 | 참조 | 설명 (왜 쓰는지) |
|---|---|---|---|:-:|---|---|---|
| **AI 실행** | 실행 ID | `id` | UUID | ● |  |  | Spring이 만들어 AI 서버에 넘기는 실행 번호 (기본값 없음) |
|  | 문의 ID | `inquiry_id` | UUID | ● |  | 문의 | 어느 문의를 분석했는지. 실행 이력 검색 기준 |
|  | 대상 티켓 ID | `ticket_id` | UUID |  |  | 티켓 | Curator 실행이면 처리 기록이 달린 티켓 (상담 실행이면 NULL) |
|  | 시작 메시지 ID | `trigger_message_id` | UUID |  |  | 대화 메시지 | 상담 실행을 시작한 메시지 (Curator 실행이면 NULL) |
|  | 시작 처리 기록 ID | `trigger_work_log_id` | UUID |  |  | 티켓 처리 기록 | Curator 실행을 시작한 해결·구현 기록 (상담 실행이면 NULL) |
|  | 작업 종류 | `task` | VARCHAR(30) | ● |  |  | 사용자 상담 실행과 사후 지식화 실행을 나눠 관리하려고 (INQUIRY_ANALYSIS / CURATION) |
|  | 처리 경로 | `route` | VARCHAR(30) |  |  |  | Supervisor가 고른 상담 경로 (GUIDE 사용법 / REQUEST 신청·변경 / SYMPTOM 현상 확인). Curator 실행이면 NULL |
|  | 실행 상태 | `run_status` | VARCHAR(30) | ● |  |  | 실행이 어디까지 왔고 어떻게 끝났는지 (QUEUED / RUNNING / SUCCEEDED / FAILED / TIMED_OUT / CANCELLED). CANCELLED는 외부에 SUPERSEDED로 보냄. 실행 성공은 업무 저장 성공이 아님 |
|  | 문의 버전 | `input_version` | INT | ● |  |  | 실행 당시 문의 버전. 정정 후라면 결과를 버리려고 |
|  | 입력 스냅샷 | `input_snapshot` | JSONB | ● |  |  | AI 서버에 보낸 검증된 입력(AiRunRequest) 원본. 상담 실행의 answerMessageId를 고정하고 재전달 때 같은 입력을 쓰려고 |
|  | 모델 버전 | `model_version` | VARCHAR(100) | ● |  |  | 어떤 모델로 실행했는지. 평가 결과를 같은 조건으로 재현하려고 |
|  | 프롬프트 버전 | `prompt_version` | VARCHAR(50) | ● |  |  | 프롬프트를 바꿨을 때 결과를 비교하려고 |
|  | 스키마 버전 | `schema_version` | VARCHAR(50) | ● |  |  | Spring이 결과 형식을 검증하는 기준 |
|  | 추적 ID | `trace_id` | VARCHAR(100) |  |  |  | 실행 이력에서 추적 ID로 검색하고 로그와 연결하려고 |
|  | 에이전트 제안 | `result` | JSONB |  |  |  | AI가 제안한 판단 (서버 검증 전) |
|  | 서버 검증 결과 | `validation_result` | JSONB |  |  |  | Spring이 검증·저장한 결과. 실패해도 이미 반영된 업무를 보여주려고 |
|  | 오류 코드 | `error_code` | VARCHAR(50) |  |  |  | 실행이 왜 실패했는지 |
|  | 시작일시 | `started_at` | TIMESTAMPTZ |  |  |  | 실제로 실행을 시작한 시각 (대기 중이면 NULL, 성공한 실행이면 필수). 응답 시간 측정 기준 |
|  | 종료일시 | `completed_at` | TIMESTAMPTZ |  |  |  | 실행 종료 시각 (진행 중이면 NULL). 시작 시각보다 이를 수 없음 |
|  | 생성일시 | `created_at` | TIMESTAMPTZ | ● | now() |  | 언제 만들어졌는지. 목록 정렬과 기간 조회에 쓰려고 |
|  | 수정일시 | `updated_at` | TIMESTAMPTZ | ● | now() |  | 마지막으로 언제 바뀌었는지 추적하려고 |

- Curator 실행(`CURATION`)은 `trigger_work_log_id`가 필수이고 상담 실행에는 없다(CHECK). 처리 기록 하나에 진행 중이거나 성공한 Curator 실행이 둘 이상 생기지 않도록 부분 유일 인덱스 `uk_agent_run_curation_work_log`(`task = 'CURATION' AND run_status IN ('QUEUED','RUNNING','SUCCEEDED')`)를 둔다. 실패(FAILED·TIMED_OUT·CANCELLED)한 실행은 이력으로 남고, 수동 재시도는 같은 처리 기록에 새 실행을 만든다(V26).

#### AI 실행 단계 `agent_step`
AI 실행 안에서 어느 에이전트가 무엇을 했는지 단계별로 남겨요. 채팅 진행 문구와 실패 단계 추적에 써요. *(근거: 최종기획서 7.1, 7.2, 9.3)*

| 테이블명 | 컬럼명 | 물리명 | 타입 | 필수 | 기본값 | 참조 | 설명 (왜 쓰는지) |
|---|---|---|---|:-:|---|---|---|
| **AI 실행 단계** | 실행 단계 ID | `id` | UUID | ● | uuidv7() |  | AI 실행 단계 한 건을 구분하는 고유 번호 |
|  | 실행 ID | `agent_run_id` | UUID | ● |  | AI 실행 | 어느 실행의 단계인지 |
|  | 단계 순번 | `sequence_no` | INT | ● |  |  | 실행 상세 타임라인 순서 |
|  | 에이전트 종류 | `agent_type` | VARCHAR(30) | ● |  |  | 어느 에이전트가 한 단계인지 (SUPERVISOR / INQUERY / KNOWLEDGE / TICKET / CURATOR) |
|  | 단계 종류 | `step_type` | VARCHAR(30) | ● |  |  | Vector 검색·Neo4j 관계 탐색·Mock 도구 조회·모델 호출 등 단계 종류. Vector와 관계 보강 검색의 비교 평가에 씀 |
|  | 도구 이름 | `tool_name` | VARCHAR(100) |  |  |  | 어떤 읽기 전용 도구를 불렀는지 |
|  | 단계 상태 | `step_status` | VARCHAR(30) | ● |  |  | 단계 성공·실패·권한 거부를 구분하려고 |
|  | 진행 문구 | `display_summary` | VARCHAR(300) | ● |  |  | 채팅에 '문서 검색 중…'처럼 진행 상황을 보여주려고 |
|  | 근거 요약 | `reason_summary` | VARCHAR(1000) |  |  |  | 판단 근거를 나중에 추적하려고 (내부 사고 과정은 저장 안 함) |
|  | 입출력 | `step_data` | JSONB |  |  |  | 마스킹된 도구 입출력과 참고 문서를 남기려고 |
|  | 시도 횟수 | `attempt_count` | INT | ● | 1 |  | 도구 호출을 몇 번 다시 시도했는지 |
|  | 오류 코드 | `error_code` | VARCHAR(50) |  |  |  | 단계가 왜 실패했는지 |
|  | 시작일시 | `started_at` | TIMESTAMPTZ | ● |  |  | 단계 시작 시각 |
|  | 종료일시 | `completed_at` | TIMESTAMPTZ |  |  |  | 단계 종료 시각. 단계별 소요 시간 측정 |
|  | 생성일시 | `created_at` | TIMESTAMPTZ | ● | now() |  | 언제 만들어졌는지. 목록 정렬과 기간 조회에 쓰려고 |

중복 불가 조합: (실행 ID, 단계 순번)

상담 실행과 Curator 실행은 같은 테이블에 두되 `task`로 나눈다. Curator 실행이 실패해도 저장된 처리 기록과 업무 완료는 취소하지 않는다 (최종기획서 7.3). Diagnosis는 별도 에이전트가 아니라 Knowledge의 조건부 기능이라 `agent_type`에 두지 않는다 (최종기획서 7.1). `INQUERY`는 이 물리 모델의 저장 명칭을 유지하며 신규 외부 role=INQUIRY와 Spring에서 변환한다.

### 요청 처리

#### 중복 요청 방지 기록 `idempotency_key`
같은 변경 요청이 다시 와도 한 번만 반영하고, 처음 결과를 그대로 돌려주려고 처리한 요청을 남겨요. 응답이 유실돼 프론트가 같은 `Idempotency-Key`로 다시 보낼 때 써요. *(근거: DATA 5 · 팀 합의 2026-10-28, WIRE_CONTRACT 10절)*

| 테이블명 | 컬럼명 | 물리명 | 타입 | 필수 | 기본값 | 참조 | 설명 (왜 쓰는지) |
|---|---|---|---|:-:|---|---|---|
| **중복 요청 방지 기록** | 기록 ID | `id` | UUID | ● | uuidv7() |  | 중복 요청 방지 기록 한 건을 구분하는 고유 번호 |
|  | 요청자 ID | `actor_member_id` | UUID | ● |  | 회원 | 다른 사람이 같은 키를 보내도 남의 결과를 받지 않게 요청자별로 나누려고 |
|  | 계약 버전 | `contract_version` | VARCHAR(50) | ● |  |  | 어느 계약의 요청인지 (예: hops-system-1). 같은 키라도 계약별로 나누려고 |
|  | 요청 방식 | `http_method` | VARCHAR(10) | ● |  |  | 같은 키라도 다른 API 요청과 섞이지 않게 (POST / PATCH 등) |
|  | 요청 경로 | `request_path` | VARCHAR(500) | ● |  |  | 같은 키라도 다른 API 요청과 섞이지 않게 (예: /api/inquiries/{id}/messages) |
|  | 요청 키 | `idempotency_key` | VARCHAR(100) | ● |  |  | 프론트가 요청마다 만들어 `Idempotency-Key` 헤더로 보낸 값 |
|  | 요청 해시 | `request_hash` | VARCHAR(64) | ● |  |  | 같은 키로 내용이 다른 요청이 오면 거절하려고 (본문 SHA-256) |
|  | 작업 ID | `operation_id` | UUID | ● |  |  | 이 요청으로 시작한 업무 작업. 같은 키로 다시 오면 이 작업 결과를 확인하려고 · 중복 불가 |
|  | 작업 상태 | `operation_state` | VARCHAR(30) | ● |  |  | PENDING 접수 / SUCCEEDED / REJECTED / UNKNOWN. 결과를 모르면 실패로 단정하지 않으려고 |
|  | 대상 종류 | `resource_type` | VARCHAR(30) |  |  |  | 만들어지거나 바뀐 업무 대상의 종류 (예: INQUIRY) |
|  | 대상 ID | `resource_id` | UUID |  |  |  | 만들어지거나 바뀐 업무 대상의 ID |
|  | 응답 상태 코드 | `response_status` | INT |  |  |  | 처음 처리했을 때의 HTTP 상태를 다시 돌려주려고 (접수 중이거나 만료 정리 뒤에는 NULL) |
|  | 응답 내용 | `response_body` | JSONB |  |  |  | 처음 처리한 저장 결과를 그대로 다시 돌려주려고 (접수 중이거나 만료 정리 뒤에는 NULL) |
|  | 만료일시 | `expires_at` | TIMESTAMPTZ | ● |  |  | 이 시각까지 상세 응답을 보존. 생성 후 최소 24시간 |
|  | 정리일시 | `tombstoned_at` | TIMESTAMPTZ |  |  |  | 만료돼 응답 본문을 비운 시각. 행은 지우지 않고 키·해시·작업 표식을 남겨 같은 키의 새 실행을 막으려고 |
|  | 생성일시 | `created_at` | TIMESTAMPTZ | ● | now() |  | 언제 만들어졌는지. 목록 정렬과 기간 조회에 쓰려고 |

중복 불가 조합: (요청자 ID, 계약 버전, 요청 키). `operation_id`도 중복 불가다. 같은 키로 다른 메서드·경로·본문을 보내면 409로 거부한다.

- 업무 변경과 **같은 트랜잭션**으로 저장한다. 전환 전 프로필의 롤백 기록과 신규 PENDING 접수 기록을 구분한다. 신규 프로필은 예약된 operation을 유지하고 업무 실패를 같은 키로 재확인한다. 새 업무 의도로 자동 재실행하지 않는다. 신규 프로필은 6절 operation_state와 저장 명령을 함께 관리한다. 장기 작업의 PENDING 접수 기록과 실제 업무 반영 트랜잭션을 구분한다. 같은 키가 동시에 오면 중복 불가 조합에서 한 요청만 저장된다.
- 로그인 세션·Refresh 검증값과 전환 전 프로필의 SSE replay 이벤트 이력은 Redis에 두고 이 테이블에 넣지 않는다 (팀 합의 2026-10-28).
- 업무 기록이 아니라 기술 기록이다. 신규 프로필은 만료 후 응답 본문만 비우고 사용 키·해시·operation/resource 표식은 tombstone으로 유지한다. 행 삭제로 새 실행을 허용하지 않는다. 문의·티켓·처리 기록·사례를 삭제하지 않는 원칙(최종기획서 8.3)과 별개다. 만료 뒤의 중복 티켓은 `ticket.inquiry_problem_id` 중복 불가로 막는다.

### 알림

#### 알림 `notification`
서비스 내부 알림함이에요. 배정·해결 확인 요청·결과 통보·사례 승인 요청을 알려요. *(근거: 최종기획서 6, 8.2, 13.2)*

| 테이블명 | 컬럼명 | 물리명 | 타입 | 필수 | 기본값 | 참조 | 설명 (왜 쓰는지) |
|---|---|---|---|:-:|---|---|---|
| **알림** | 알림 ID | `id` | UUID | ● | uuidv7() |  | 알림 한 건을 구분하는 고유 번호 |
|  | 수신자 ID | `recipient_member_id` | UUID | ● |  | 회원 | 알림을 받을 사람. 본인 알림만 보이게 |
|  | 문의 ID | `inquiry_id` | UUID |  |  | 문의 | 문의자 알림을 누르면 이동할 문의 |
|  | 티켓 ID | `ticket_id` | UUID |  |  | 티켓 | 담당자 알림을 누르면 이동할 티켓 |
|  | 알림 종류 | `notification_type` | VARCHAR(30) | ● |  |  | 받는 사람과 이동할 화면을 구분하려고 (TICKET_ASSIGNED 담당자 배정 / CONFIRM_REQUESTED 문의자 해결 확인 요청 / RESULT_NOTIFIED 기능 구현 결과 통보 / CASE_APPROVAL_REQUESTED 담당자 사례 승인 요청) |
|  | 제목 | `title` | VARCHAR(200) | ● |  |  | 알림함에 보일 제목 |
|  | 알림 내용 | `payload` | JSONB | ● |  |  | 번호·요약·링크를 알림에 보여주려고 |
|  | 생성일시 | `created_at` | TIMESTAMPTZ | ● | now() |  | 언제 만들어졌는지. 목록 정렬과 기간 조회에 쓰려고 |

예약 재알림·무응답 자동 종료가 없어서 알림 시각을 상태 전이 기준으로 쓰지 않는다 (최종기획서 6, 13.2). 알림을 읽었다고 해결 확인이나 처리 완료로 보지 않는다.

### 지식

#### 지식 문서 `knowledge_document`
직원 가이드·정책·담당자 런북이에요. 출처·검수·공개 범위·평가 분할을 함께 관리해요. 해결 사례는 해결 사례 테이블에 따로 둬요. *(근거: 최종기획서 3, 8.1, 12.1)*

| 테이블명 | 컬럼명 | 물리명 | 타입 | 필수 | 기본값 | 참조 | 설명 (왜 쓰는지) |
|---|---|---|---|:-:|---|---|---|
| **지식 문서** | 지식 문서 ID | `id` | UUID | ● | uuidv7() |  | 지식 문서 한 건을 구분하는 고유 번호 |
|  | 제목 | `title` | VARCHAR(200) | ● |  |  | 답변 근거의 출처로 보여줄 문서 제목 |
|  | 문서 종류 | `document_type` | VARCHAR(30) | ● |  |  | 사용법 안내·업무 규칙·담당자 절차·승인 사례로 검색 범위를 나누려고 (EMPLOYEE_GUIDE / POLICY / STAFF_RUNBOOK / CASE). CASE는 승인된 현재 사례의 검색용 문서이며 원본·승인 여부는 해결 사례에서 확인 |
|  | 시스템 ID | `it_system_id` | UUID |  |  | 시스템 | 가이드·런북이 어느 시스템 이야기인지. 검색 범위를 시스템으로 좁히려고 (여러 시스템 공통 정책이면 NULL) |
|  | 공개 범위 | `access_level` | VARCHAR(30) | ● |  |  | 직원에게 보여줄 문서와 담당자만 볼 문서를 검색 전에 걸러내려고 (EMPLOYEE / STAFF) |
|  | 출처 정보 | `source_data` | JSONB |  |  |  | 어느 공개 데이터셋·레코드에서 만들었는지 출처를 남기려고 |
|  | 데이터 용도 | `data_split` | VARCHAR(30) | ● |  |  | 테스트 정답·숨겨진 요구사항이 검색 자료에 섞이지 않게 (RAG / DEV / EVAL) |
|  | 검수 상태 | `review_status` | VARCHAR(30) | ● | 'PENDING' |  | 사람이 검수한 문서만 검색에 쓰려고 (APPROVED만 사용) |
|  | 가상 데이터 여부 | `is_synthetic` | BOOLEAN | ● | true |  | 가상 데이터와 실제 운영 기록을 구분하려고 |
|  | 생성일시 | `created_at` | TIMESTAMPTZ | ● | now() |  | 언제 만들어졌는지. 목록 정렬과 기간 조회에 쓰려고 |
|  | 수정일시 | `updated_at` | TIMESTAMPTZ | ● | now() |  | 마지막으로 언제 바뀌었는지 추적하려고 |
|  | 삭제일시 | `deleted_at` | TIMESTAMPTZ |  |  |  | 삭제 처리한 시각 (NULL이면 사용 중). 실제로 지우지 않고 기록을 남기려고 |

#### 문서 버전 `knowledge_document_version`
문서 내용을 덮어쓰지 않고 버전별로 보관해요. 평가는 검수해 고정한 버전으로 해요. *(근거: 최종기획서 8.5)*

| 테이블명 | 컬럼명 | 물리명 | 타입 | 필수 | 기본값 | 참조 | 설명 (왜 쓰는지) |
|---|---|---|---|:-:|---|---|---|
| **문서 버전** | 문서 버전 ID | `id` | UUID | ● | uuidv7() |  | 문서 버전 한 건을 구분하는 고유 번호 |
|  | 지식 문서 ID | `knowledge_document_id` | UUID | ● |  | 지식 문서 | 어느 문서의 버전인지 |
|  | 버전 번호 | `version_no` | INT | ● |  |  | 몇 번째 버전인지. 예전 답변이 참고한 버전을 남기려고 |
|  | 본문 | `content` | TEXT | ● |  |  | 그 버전의 문서 본문 |
|  | 본문 해시 | `content_digest` | CHAR(64) | ● |  |  | 승인 원문의 UTF-8 바이트 SHA-256. 검색 조각이 같은 원문에서 나왔는지 대조하려고 |
|  | 출처 메타데이터 | `source_metadata` | JSONB | ● |  |  | origin·출처·적용 조건을 버전마다 고정하려고. 사례 문서는 caseId·caseVersion·indexRequests도 둠 (DATA 11.3절) |
|  | 검수 상태 | `review_status` | VARCHAR(30) | ● | 'PENDING' |  | 버전마다 검수해 승인된 버전만 검색에 쓰려고 (PENDING / APPROVED) |
|  | 생성일시 | `created_at` | TIMESTAMPTZ | ● | now() |  | 언제 만들어졌는지. 목록 정렬과 기간 조회에 쓰려고 |

중복 불가 조합: (지식 문서 ID, 버전 번호)

#### 검색 조각 `hops_knowledge.knowledge_chunk`
문서를 검색하기 좋게 잘게 나눈 조각과 임베딩이에요. *(근거: 최종기획서 9.1, 9.3)*

| 테이블명 | 컬럼명 | 물리명 | 타입 | 필수 | 기본값 | 참조 | 설명 (왜 쓰는지) |
|---|---|---|---|:-:|---|---|---|
| **검색 조각** | 검색 조각 ID | `id` | UUID | ● | uuidv7() |  | 검색 조각 한 건을 구분하는 고유 번호 |
|  | 문서 버전 ID | `knowledge_document_version_id` | UUID | ● |  | 문서 버전 | 어느 문서 버전에서 잘라낸 조각인지. 답변 근거 출처 추적 |
|  | 조각 순번 | `chunk_no` | INT | ● |  |  | 문서 안에서 몇 번째 조각인지 |
|  | 내용 | `content` | TEXT | ● |  |  | 검색되면 AI에게 넘길 본문 |
|  | 임베딩 벡터 | `embedding` | VECTOR | ● |  |  | 표현이 달라도 의미가 비슷한 조각을 찾으려고 |
|  | 원문 해시 | `content_digest` | CHAR(64) | ● |  |  | 잘라낸 문서 버전의 본문 해시. 모델에 넣기 전에 원문 버전과 대조하려고 |
|  | 조각 해시 | `chunk_digest` | CHAR(64) | ● |  |  | 이 조각 본문의 SHA-256. 저장된 조각이 바뀌지 않았는지 확인하려고 |
|  | 생성일시 | `created_at` | TIMESTAMPTZ | ● | now() |  | 언제 만들어졌는지. 목록 정렬과 기간 조회에 쓰려고 |

중복 불가 조합: (문서 버전 ID, 조각 순번)

신규 프로필은 같은 Spring PostgreSQL DB의 hops_knowledge 스키마에 pgvector를 사용한다. 임베딩 모델과 벡터 차원은 별도 선정하며 여기서 고정하지 않는다. 문서 버전 FK는 기존 knowledge_document_version을 참조한다. 승인 CASE도 기존 knowledge_document/knowledge_document_version에 검색용 스냅샷으로 투영하고 source_metadata에 caseId/caseVersion을 연결한다. 별도 사례 버전·반영 작업 테이블은 만들지 않는다. 반영 결과는 resolved_case.vector_status/graph_status로 관리하며 시스템·기능·증상 등 관계는 Neo4j에 둔다.

#### 해결 사례 `resolved_case`
담당자의 해결·구현 기록으로 만든 사례예요. Curator가 초안을 만들고, 해당 시스템 주담당자가 승인한 뒤 Vector·Neo4j에 모두 반영돼야 검색에 나와요. *(근거: 최종기획서 7.3, 8.1~8.5, 9.4)*

| 테이블명 | 컬럼명 | 물리명 | 타입 | 필수 | 기본값 | 참조 | 설명 (왜 쓰는지) |
|---|---|---|---|:-:|---|---|---|
| **해결 사례** | 해결 사례 ID | `id` | UUID | ● | uuidv7() |  | 해결 사례 한 건을 구분하는 고유 번호 |
|  | 사례 번호 | `case_number` | VARCHAR(20) | ● |  |  | 담당자가 부르기 쉬운 사례 번호 (CASE-0001) · 중복 불가 |
|  | 문서 버전 | `version_no` | INT | ● | 1 |  | 사례 문서의 버전. 답변 근거가 어느 버전을 참고했는지 남기려고 |
|  | 원 처리 기록 ID | `source_work_log_id` | UUID | ● |  | 티켓 처리 기록 | 어느 해결·구현 기록에서 만든 사례인지. 티켓·문의는 이 기록을 따라가서 찾음 · 중복 불가 |
|  | 시스템 ID | `it_system_id` | UUID | ● |  | 시스템 | 어느 시스템의 사례인지. 검색 범위와 승인할 주담당자를 정하려고 |
|  | 시스템 기능 ID | `system_feature_id` | UUID |  |  | 시스템 기능 | 어느 기능의 사례인지 (특정 기능이 아니면 NULL) |
|  | 사례 종류 | `case_type` | VARCHAR(30) | ● |  |  | 해결 사례와 기능 구현 사례를 구분하려고. 승인 전제 조건이 다름 (RESOLUTION / IMPLEMENTATION) |
|  | 제목 | `title` | VARCHAR(200) | ● |  |  | 검색 결과·담당자 참고자료에 보여줄 제목 |
|  | 증상 | `problem_statement` | TEXT | ● |  |  | 해결 사례는 증상, 기능 구현 사례는 변경 목적. 다음 문의와 비교하는 기준 |
|  | 발생 상황 | `preconditions` | TEXT |  |  |  | 어떤 상황에서 생긴 일인지. 비슷해 보여도 조건이 다른 문의에 잘못 쓰지 않으려고 |
|  | 확인한 사실 | `confirmed_facts` | JSONB | ● | '[]' |  | 사례를 뒷받침하는 확인 사실과 출처 |
|  | 확인된 원인 | `confirmed_cause` | TEXT |  |  |  | 담당자가 확인한 원인 (확인하지 못했으면 NULL) |
|  | 실제 조치 | `action_detail` | TEXT | ● |  |  | 실제로 한 조치·변경 |
|  | 검증 결과 | `verification_result` | TEXT | ● |  |  | 해결·구현을 확인한 결과. 검증되지 않은 조치를 사례로 만들지 않으려고 |
|  | 적용 조건 | `applicability` | TEXT |  |  |  | 이 사례를 쓸 수 있는 조건과 쓸 수 없는 경우. 승인 때 반드시 확인 |
|  | 적용 버전 | `applied_version` | VARCHAR(100) |  |  |  | 기능 구현 사례가 반영된 시스템 버전 |
|  | 공개 범위 | `access_level` | VARCHAR(30) | ● |  |  | 직원에게 보여줄 수 있는 사례와 담당자만 볼 사례를 나누려고 (EMPLOYEE / STAFF) |
|  | 출처 구분 | `origin_type` | VARCHAR(30) | ● |  |  | 합성·데모 검증·실제 운영 사례를 구분하려고. 사람이 승인한 합성 사례를 운영 사례로 표시하지 않음 (SYNTHETIC / DEMO_VERIFIED / PRODUCTION) |
|  | 사례 상태 | `case_status` | VARCHAR(30) | ● | 'DRAFT' |  | Curator 초안과 승인된 사례를 구분하려고. 초안은 검색에서 제외 (DRAFT / APPROVED) |
|  | Curator 실행 ID | `curator_run_id` | UUID |  |  | AI 실행 | 초안을 만든 Curator 실행을 추적하려고 |
|  | 중복 후보 | `duplicate_candidates` | JSONB | ● | '[]' |  | Curator가 찾은 비슷한 기존 사례. 승인 전에 담당자가 중복인지 확인하려고 |
|  | 관계 연결 후보 | `relation_candidates` | JSONB | ● | '[]' |  | Curator가 제안한 기존 시스템·기능·증상과의 연결. 담당자가 승인한 연결만 Neo4j에 반영 |
|  | 승인자 ID | `approved_by_member_id` | UUID |  |  | 회원 | 사례를 승인한 해당 시스템 주담당자 (승인 전이면 NULL) |
|  | 승인일시 | `approved_at` | TIMESTAMPTZ |  |  |  | 승인한 시각 (승인 전이면 NULL) |
|  | Vector 반영 상태 | `vector_status` | VARCHAR(30) | ● | 'PENDING' |  | 승인 후 Vector 색인에 반영됐는지 (PENDING / RUNNING / SUCCEEDED / FAILED / UNKNOWN) |
|  | Neo4j 반영 상태 | `graph_status` | VARCHAR(30) | ● | 'PENDING' |  | 승인 후 Neo4j 관계에 반영됐는지 (PENDING / RUNNING / SUCCEEDED / FAILED / UNKNOWN) |
|  | Neo4j 노드 ID | `graph_node_id` | VARCHAR(100) |  |  |  | Neo4j에 만든 사례 노드를 찾아가려고 (반영 전이면 NULL) |
|  | 생성일시 | `created_at` | TIMESTAMPTZ | ● | now() |  | 언제 만들어졌는지. 목록 정렬과 기간 조회에 쓰려고 |
|  | 수정일시 | `updated_at` | TIMESTAMPTZ | ● | now() |  | 마지막으로 언제 바뀌었는지 추적하려고 |
|  | 삭제일시 | `deleted_at` | TIMESTAMPTZ |  |  |  | 삭제 처리한 시각 (NULL이면 사용 중). 실제로 지우지 않고 기록을 남기려고 |

- **초안 → 승인:** Curator가 `DRAFT`로 저장하고 담당자가 확인·수정한다. Spring은 사례 시스템의 주담당자인지, 해결 사례면 문의자 해결 확인이 끝났는지, 기능 구현 사례면 `IMPLEMENTED` 기록인지 확인한 뒤 `APPROVED`로 바꾼다 (최종기획서 8.2). 별도 지식 검수자는 없고 주담당자의 자기 승인을 허용한다.
- **검색 공개:** MVP는 현재 resolved_case가 승인됐고 원 처리 기록의 문의자 해결 확인과 vector_status/graph_status의 양쪽 성공을 확인해야 검색에 공개한다. 승인 후 내용은 읽기 전용이며 결과의 caseVersion을 현재 승인 버전과 대조한다. 한쪽이 실패·대기·미확인이면 양쪽 검색에서 제외한다. 승인 후 새 초안과 이전 공개 버전의 병행 관리는 후순위다.
- **이번 범위:** PRD1.44에 따라 실패한 저장소의 수동 재실행과 UNKNOWN 결과 확인을 제공한다. 승인 취소·자동 재검토·자동 비활성화·자동 색인 재시도·전용 복구 화면은 제외한다. 반영이 실패하면 `FAILED`로 남기고 성공으로 바꾸지 않는다 (7.3).
- 작성자는 원 처리 기록의 작성 담당자다. 처리 기록 하나에서 사례는 최대 하나만 만든다.

### 관계 데이터

증상 → 가능 원인 → 확인 항목 → 직원용 질문 문구, 그리고 승인 사례와의 연결이에요. 1차 `RelationSource`(에이전트 설계 8절)의 저장소이고, Neo4j는 2차에 같은 인터페이스 뒤로 교체해요(노드는 테이블, 관계는 연결 테이블로 1:1 대응). 연결은 후보이며 원인을 확정하지 않아요. 모든 행이 근거 문서 버전·공개 범위·승인 상태를 가져요. 마이그레이션은 V25예요.

#### 증상 `relation_symptom`
직원이 말하는 현상이에요. 원인을 담지 않아요. 시스템 기능에 묶여요. *(근거: 에이전트 설계 8절, 정본 6.1절)*

| 테이블명 | 컬럼명 | 물리명 | 타입 | 필수 | 기본값 | 참조 | 설명 (왜 쓰는지) |
|---|---|---|---|:-:|---|---|---|
| **증상** | 증상 ID | `id` | UUID | ● | uuidv7() |  | 한 건을 구분하는 고유 번호 |
|  | 카탈로그 ID | `catalog_key` | VARCHAR(100) | ● |  |  | 정본 카탈로그 ID(예: sym-ea-doc-not-listed). 문서가 바뀌어도 같은 항목을 가리키려고. 유일 |
|  | 이름 | `label` | VARCHAR(200) | ● |  |  | 사람이 읽는 이름. 빈 값 불가 |
|  | 시스템 ID | `it_system_id` | UUID | ● |  | 시스템 | 어느 시스템 이야기인지. 시스템으로 좁혀 찾으려고 |
|  | 시스템 기능 ID | `system_feature_id` | UUID | ● |  | 시스템 기능 | 증상이 나타나는 기능 |
|  | 공개 범위 | `access_level` | VARCHAR(30) | ● |  |  | 상담에 쓰기 전에 직원 공개 자료와 담당자 전용 자료를 걸러내려고 (EMPLOYEE / STAFF) |
|  | 검수 상태 | `review_status` | VARCHAR(30) | ● | 'PENDING' |  | 승인된 항목만 상담에 쓰려고 (PENDING / APPROVED) |
|  | 근거 문서 버전 ID | `source_document_version_id` | UUID | ● |  | 문서 버전 | 근거 없는 연결을 만들지 않으려고. 문서 ID·버전은 이 버전에서 읽어요. 사례 연결은 그 사례의 CASE 문서 버전이에요 |
|  | 생성일시 | `created_at` | TIMESTAMPTZ | ● | now() |  | 언제 만들어졌는지 |
|  | 수정일시 | `updated_at` | TIMESTAMPTZ | ● | now() |  | 마지막으로 언제 바뀌었는지 추적하려고 |
|  | 삭제일시 | `deleted_at` | TIMESTAMPTZ |  |  |  | 삭제 처리한 시각 (NULL이면 사용 중). 실제로 지우지 않고 기록을 남기려고 |

#### 가능 원인 `relation_cause`
증상의 가능한 원인 후보예요. 담당자 전용이며, 연결만으로 원인을 확정하지 않아요.

| 테이블명 | 컬럼명 | 물리명 | 타입 | 필수 | 기본값 | 참조 | 설명 (왜 쓰는지) |
|---|---|---|---|:-:|---|---|---|
| **가능 원인** | 원인 ID | `id` | UUID | ● | uuidv7() |  | 한 건을 구분하는 고유 번호 |
|  | 카탈로그 ID | `catalog_key` | VARCHAR(100) | ● |  |  | 정본 카탈로그 ID(예: sym-ea-doc-not-listed). 문서가 바뀌어도 같은 항목을 가리키려고. 유일 |
|  | 이름 | `label` | VARCHAR(200) | ● |  |  | 사람이 읽는 이름. 빈 값 불가 |
|  | 시스템 ID | `it_system_id` | UUID | ● |  | 시스템 | 어느 시스템 이야기인지. 시스템으로 좁혀 찾으려고 |
|  | 공개 범위 | `access_level` | VARCHAR(30) | ● |  |  | 상담에 쓰기 전에 직원 공개 자료와 담당자 전용 자료를 걸러내려고 (EMPLOYEE / STAFF) |
|  | 검수 상태 | `review_status` | VARCHAR(30) | ● | 'PENDING' |  | 승인된 항목만 상담에 쓰려고 (PENDING / APPROVED) |
|  | 근거 문서 버전 ID | `source_document_version_id` | UUID | ● |  | 문서 버전 | 근거 없는 연결을 만들지 않으려고. 문서 ID·버전은 이 버전에서 읽어요. 사례 연결은 그 사례의 CASE 문서 버전이에요 |
|  | 생성일시 | `created_at` | TIMESTAMPTZ | ● | now() |  | 언제 만들어졌는지 |
|  | 수정일시 | `updated_at` | TIMESTAMPTZ | ● | now() |  | 마지막으로 언제 바뀌었는지 추적하려고 |
|  | 삭제일시 | `deleted_at` | TIMESTAMPTZ |  |  |  | 삭제 처리한 시각 (NULL이면 사용 중). 실제로 지우지 않고 기록을 남기려고 |

#### 확인 항목 `relation_check_item`
원인을 가르는 확인거리예요. 직원이 화면·설정에서 스스로 알 수 있는 사실만 직원 공개로 표시해요.

| 테이블명 | 컬럼명 | 물리명 | 타입 | 필수 | 기본값 | 참조 | 설명 (왜 쓰는지) |
|---|---|---|---|:-:|---|---|---|
| **확인 항목** | 확인 항목 ID | `id` | UUID | ● | uuidv7() |  | 한 건을 구분하는 고유 번호 |
|  | 카탈로그 ID | `catalog_key` | VARCHAR(100) | ● |  |  | 정본 카탈로그 ID(예: sym-ea-doc-not-listed). 문서가 바뀌어도 같은 항목을 가리키려고. 유일 |
|  | 이름 | `label` | VARCHAR(200) | ● |  |  | 사람이 읽는 이름. 빈 값 불가 |
|  | 시스템 ID | `it_system_id` | UUID | ● |  | 시스템 | 어느 시스템 이야기인지. 시스템으로 좁혀 찾으려고 |
|  | 직원 공개 여부 | `employee_disclosable` | BOOLEAN | ● | false |  | 직원에게 질문으로 나갈 수 있는지. 담당자가 시스템 기록으로만 확인하는 항목은 false |
|  | 공개 범위 | `access_level` | VARCHAR(30) | ● |  |  | 상담에 쓰기 전에 직원 공개 자료와 담당자 전용 자료를 걸러내려고 (EMPLOYEE / STAFF) |
|  | 검수 상태 | `review_status` | VARCHAR(30) | ● | 'PENDING' |  | 승인된 항목만 상담에 쓰려고 (PENDING / APPROVED) |
|  | 근거 문서 버전 ID | `source_document_version_id` | UUID | ● |  | 문서 버전 | 근거 없는 연결을 만들지 않으려고. 문서 ID·버전은 이 버전에서 읽어요. 사례 연결은 그 사례의 CASE 문서 버전이에요 |
|  | 생성일시 | `created_at` | TIMESTAMPTZ | ● | now() |  | 언제 만들어졌는지 |
|  | 수정일시 | `updated_at` | TIMESTAMPTZ | ● | now() |  | 마지막으로 언제 바뀌었는지 추적하려고 |
|  | 삭제일시 | `deleted_at` | TIMESTAMPTZ |  |  |  | 삭제 처리한 시각 (NULL이면 사용 중). 실제로 지우지 않고 기록을 남기려고 |

#### 질문 문구 `relation_question_template`
확인 항목을 직원에게 묻는 문구예요. 한 확인 항목에 여러 개를 둘 수 있어요. 직원 상담은 승인되고 공개 표시가 있는 문구만 써요. 문구의 공개 표시가 확인 항목의 공개 표시를 넘지 않는지는 테이블을 가로지르므로 시드 검사와 테스트가 확인해요.

| 테이블명 | 컬럼명 | 물리명 | 타입 | 필수 | 기본값 | 참조 | 설명 (왜 쓰는지) |
|---|---|---|---|:-:|---|---|---|
| **질문 문구** | 질문 문구 ID | `id` | UUID | ● | uuidv7() |  | 한 건을 구분하는 고유 번호 |
|  | 카탈로그 ID | `catalog_key` | VARCHAR(100) | ● |  |  | 질문 키(q-{확인 항목}-{번호}). 유일 |
|  | 확인 항목 ID | `check_item_id` | UUID | ● |  | 확인 항목 | 어느 확인 항목을 묻는 문구인지 |
|  | 질문 문구 | `question_text` | VARCHAR(500) | ● |  |  | 직원에게 그대로 보여 줄 문장. 빈 값 불가, 담당자 전용 문구 금지 |
|  | 직원 공개 여부 | `employee_disclosable` | BOOLEAN | ● | false |  | 직원에게 나갈 수 있는 문구인지. true이면 공개 범위가 EMPLOYEE여야 해요(CHECK) |
|  | 공개 범위 | `access_level` | VARCHAR(30) | ● |  |  | 상담에 쓰기 전에 직원 공개 자료와 담당자 전용 자료를 걸러내려고 (EMPLOYEE / STAFF) |
|  | 검수 상태 | `review_status` | VARCHAR(30) | ● | 'PENDING' |  | 승인된 항목만 상담에 쓰려고 (PENDING / APPROVED) |
|  | 근거 문서 버전 ID | `source_document_version_id` | UUID | ● |  | 문서 버전 | 근거 없는 연결을 만들지 않으려고. 문서 ID·버전은 이 버전에서 읽어요. 사례 연결은 그 사례의 CASE 문서 버전이에요 |
|  | 생성일시 | `created_at` | TIMESTAMPTZ | ● | now() |  | 언제 만들어졌는지 |
|  | 수정일시 | `updated_at` | TIMESTAMPTZ | ● | now() |  | 마지막으로 언제 바뀌었는지 추적하려고 |
|  | 삭제일시 | `deleted_at` | TIMESTAMPTZ |  |  |  | 삭제 처리한 시각 (NULL이면 사용 중). 실제로 지우지 않고 기록을 남기려고 |

#### 증상-원인 연결 `relation_symptom_cause`
증상에서 가능한 원인 후보로 가는 연결(MAY_HAVE_CAUSE)이에요.

| 테이블명 | 컬럼명 | 물리명 | 타입 | 필수 | 기본값 | 참조 | 설명 (왜 쓰는지) |
|---|---|---|---|:-:|---|---|---|
| **증상-원인 연결** | 증상-원인 연결 ID | `id` | UUID | ● | uuidv7() |  | 한 건을 구분하는 고유 번호 |
|  | 증상 ID | `symptom_id` | UUID | ● |  | 증상 | 연결의 한쪽 끝 |
|  | 원인 ID | `cause_id` | UUID | ● |  | 가능 원인 | 연결의 다른쪽 끝. (symptom_id, cause_id)는 유일 |
|  | 공개 범위 | `access_level` | VARCHAR(30) | ● |  |  | 상담에 쓰기 전에 직원 공개 자료와 담당자 전용 자료를 걸러내려고 (EMPLOYEE / STAFF) |
|  | 검수 상태 | `review_status` | VARCHAR(30) | ● | 'PENDING' |  | 승인된 항목만 상담에 쓰려고 (PENDING / APPROVED) |
|  | 근거 문서 버전 ID | `source_document_version_id` | UUID | ● |  | 문서 버전 | 근거 없는 연결을 만들지 않으려고. 문서 ID·버전은 이 버전에서 읽어요. 사례 연결은 그 사례의 CASE 문서 버전이에요 |
|  | 생성일시 | `created_at` | TIMESTAMPTZ | ● | now() |  | 언제 만들어졌는지 |
|  | 수정일시 | `updated_at` | TIMESTAMPTZ | ● | now() |  | 마지막으로 언제 바뀌었는지 추적하려고 |
|  | 삭제일시 | `deleted_at` | TIMESTAMPTZ |  |  |  | 삭제 처리한 시각 (NULL이면 사용 중). 실제로 지우지 않고 기록을 남기려고 |

#### 원인-확인 항목 연결 `relation_cause_check`
원인에서 그 원인을 가르는 확인 항목으로 가는 연결(CHECKED_BY)이에요.

| 테이블명 | 컬럼명 | 물리명 | 타입 | 필수 | 기본값 | 참조 | 설명 (왜 쓰는지) |
|---|---|---|---|:-:|---|---|---|
| **원인-확인 항목 연결** | 원인-확인 항목 연결 ID | `id` | UUID | ● | uuidv7() |  | 한 건을 구분하는 고유 번호 |
|  | 원인 ID | `cause_id` | UUID | ● |  | 가능 원인 | 연결의 한쪽 끝 |
|  | 확인 항목 ID | `check_item_id` | UUID | ● |  | 확인 항목 | 연결의 다른쪽 끝. (cause_id, check_item_id)는 유일 |
|  | 공개 범위 | `access_level` | VARCHAR(30) | ● |  |  | 상담에 쓰기 전에 직원 공개 자료와 담당자 전용 자료를 걸러내려고 (EMPLOYEE / STAFF) |
|  | 검수 상태 | `review_status` | VARCHAR(30) | ● | 'PENDING' |  | 승인된 항목만 상담에 쓰려고 (PENDING / APPROVED) |
|  | 근거 문서 버전 ID | `source_document_version_id` | UUID | ● |  | 문서 버전 | 근거 없는 연결을 만들지 않으려고. 문서 ID·버전은 이 버전에서 읽어요. 사례 연결은 그 사례의 CASE 문서 버전이에요 |
|  | 생성일시 | `created_at` | TIMESTAMPTZ | ● | now() |  | 언제 만들어졌는지 |
|  | 수정일시 | `updated_at` | TIMESTAMPTZ | ● | now() |  | 마지막으로 언제 바뀌었는지 추적하려고 |
|  | 삭제일시 | `deleted_at` | TIMESTAMPTZ |  |  |  | 삭제 처리한 시각 (NULL이면 사용 중). 실제로 지우지 않고 기록을 남기려고 |

#### 사례-증상 연결 `relation_case_symptom`
승인 사례가 다룬 증상(ADDRESSES)이에요. 근거는 그 사례의 CASE 문서 버전이에요.

| 테이블명 | 컬럼명 | 물리명 | 타입 | 필수 | 기본값 | 참조 | 설명 (왜 쓰는지) |
|---|---|---|---|:-:|---|---|---|
| **사례-증상 연결** | 사례-증상 연결 ID | `id` | UUID | ● | uuidv7() |  | 한 건을 구분하는 고유 번호 |
|  | 해결 사례 ID | `resolved_case_id` | UUID | ● |  | 해결 사례 | 연결의 한쪽 끝 |
|  | 증상 ID | `symptom_id` | UUID | ● |  | 증상 | 연결의 다른쪽 끝. (resolved_case_id, symptom_id)는 유일 |
|  | 공개 범위 | `access_level` | VARCHAR(30) | ● |  |  | 상담에 쓰기 전에 직원 공개 자료와 담당자 전용 자료를 걸러내려고 (EMPLOYEE / STAFF) |
|  | 검수 상태 | `review_status` | VARCHAR(30) | ● | 'PENDING' |  | 승인된 항목만 상담에 쓰려고 (PENDING / APPROVED) |
|  | 근거 문서 버전 ID | `source_document_version_id` | UUID | ● |  | 문서 버전 | 근거 없는 연결을 만들지 않으려고. 문서 ID·버전은 이 버전에서 읽어요. 사례 연결은 그 사례의 CASE 문서 버전이에요 |
|  | 생성일시 | `created_at` | TIMESTAMPTZ | ● | now() |  | 언제 만들어졌는지 |
|  | 수정일시 | `updated_at` | TIMESTAMPTZ | ● | now() |  | 마지막으로 언제 바뀌었는지 추적하려고 |
|  | 삭제일시 | `deleted_at` | TIMESTAMPTZ |  |  |  | 삭제 처리한 시각 (NULL이면 사용 중). 실제로 지우지 않고 기록을 남기려고 |

#### 사례-확인 항목 연결 `relation_case_check`
승인 사례가 쓴 확인 항목(USES_CHECK)이에요.

| 테이블명 | 컬럼명 | 물리명 | 타입 | 필수 | 기본값 | 참조 | 설명 (왜 쓰는지) |
|---|---|---|---|:-:|---|---|---|
| **사례-확인 항목 연결** | 사례-확인 항목 연결 ID | `id` | UUID | ● | uuidv7() |  | 한 건을 구분하는 고유 번호 |
|  | 해결 사례 ID | `resolved_case_id` | UUID | ● |  | 해결 사례 | 연결의 한쪽 끝 |
|  | 확인 항목 ID | `check_item_id` | UUID | ● |  | 확인 항목 | 연결의 다른쪽 끝. (resolved_case_id, check_item_id)는 유일 |
|  | 공개 범위 | `access_level` | VARCHAR(30) | ● |  |  | 상담에 쓰기 전에 직원 공개 자료와 담당자 전용 자료를 걸러내려고 (EMPLOYEE / STAFF) |
|  | 검수 상태 | `review_status` | VARCHAR(30) | ● | 'PENDING' |  | 승인된 항목만 상담에 쓰려고 (PENDING / APPROVED) |
|  | 근거 문서 버전 ID | `source_document_version_id` | UUID | ● |  | 문서 버전 | 근거 없는 연결을 만들지 않으려고. 문서 ID·버전은 이 버전에서 읽어요. 사례 연결은 그 사례의 CASE 문서 버전이에요 |
|  | 생성일시 | `created_at` | TIMESTAMPTZ | ● | now() |  | 언제 만들어졌는지 |
|  | 수정일시 | `updated_at` | TIMESTAMPTZ | ● | now() |  | 마지막으로 언제 바뀌었는지 추적하려고 |
|  | 삭제일시 | `deleted_at` | TIMESTAMPTZ |  |  |  | 삭제 처리한 시각 (NULL이면 사용 중). 실제로 지우지 않고 기록을 남기려고 |

## 4. 주요 흐름에서 쓰이는 테이블

| 흐름 | 테이블 순서 |
|---|---|
| 사용법 안내 (무티켓) | 문의 → 대화 메시지 → AI 실행(GUIDE)·단계 → 문의 문제(GUIDED) + 표준 요청서 → 해결 확인 → 문의 문제(RESOLVED_WITHOUT_TICKET) |
| 문의 → 티켓 배정 | 문의 → 대화 메시지 → AI 실행·단계 → 문의 문제 + 표준 요청서(책임 시스템) → 시스템(주담당자 조회) → 티켓 + 담당 배정 이력(AUTO) → 알림(TICKET_ASSIGNED) |
| 추가 질문 5회 | 문의 문제(추가 질문 횟수 5) → 시스템을 알면 표준 요청서(미확인 항목 표시) → 티켓 / 모르면 문의 문제(SYSTEM_PENDING) |
| 복합 문의 | 문의 → 문의 문제 여러 개 → 문제마다 표준 요청서·티켓. 실패한 문제만 문의 문제(TICKET_FAILED) |
| 처리 → 해결 확인 | 티켓 처리 기록(RESOLVED / IMPLEMENTED) → 티켓(WAITING_CONFIRM) → 알림(CONFIRM_REQUESTED / RESULT_NOTIFIED) → 티켓 변경 이력(USER_CONFIRMED → 티켓 RESOLVED / USER_NOT_RESOLVED → 티켓 IN_PROGRESS) |
| 지식화 | 티켓 처리 기록 → AI 실행(CURATION) → 해결 사례(DRAFT) → 알림(CASE_APPROVAL_REQUESTED) → 해결 사례(APPROVED) → Vector·Neo4j 반영 상태 → 다음 문의 검색 |
| 수동 정정 | 담당 배정 이력(현재 배정 종료 + MANUAL_CORRECTION 추가) + 티켓(책임 시스템·담당자 변경) |
| 중복 요청 | 중복 요청 방지 기록 조회 → 있으면 처음 응답을 그대로 반환 / 없으면 업무 변경과 함께 저장 |

## 5. 물리 적용 상태

- 22개 테이블 전부 [Flyway 마이그레이션](../backend/spring/hops/src/main/resources/db/migration/) V1~V21로 실제 DB에 적용했다 (#23). 테이블마다 파일 하나(V2~V20)이며, 순환 참조 FK는 뒤 파일에서 `ALTER TABLE`로 붙인다 (`inquiry.current_run_id`→V7, `agent_run.ticket_id`→V11, `agent_run.trigger_work_log_id`→V14).
- 관계 데이터 8개 테이블(`relation_*`)은 V25로 적용한다(#191). 마이그레이션은 EC2에서 자동 실행되지 않으므로 배포 담당자가 적용 시점을 맞춘다. 개발용 시드는 `R__seed_dev_relations.sql`이 넣는다.
- 검색 조각은 pgvector `vector` 타입을 쓴다. V19는 `CREATE EXTENSION IF NOT EXISTS vector`를 실행한다. 제한 권한의 로컬 DB 계정은 슈퍼유저로 확장을 먼저 켜고, 통합 Compose는 pgvector 이미지와 확장 생성 권한이 있는 DB 계정으로 Flyway를 실행한다. 환경별 준비는 [Spring README](../backend/spring/hops/README.md)를 따른다.
- 아직 적용하지 않은 것: `department.is_routable`·`AGENT_OPS_ADMIN` 제거(서버 권한·DTO 전환), AI 서버 계정의 `hops_knowledge` 전용 권한, 모델 선정 후 임베딩 차원 고정, JSONB 내부 구조의 전용 타입. 초기 검색은 QUESTIONS의 정확 코사인 기준이며 ANN 인덱스는 MVP 필수 미완료 항목이 아니다.
- JSONB·상태 변환·저장 제약은 DATA11절/본 문서6절, 그래프·검색 계약은 에이전트 계약/QUESTIONS, 티켓 재시도 주체는 F010의 채택값을 따른다.
- 신규 상태·필드는 [DATA_TYPES 신규 절](../specs/contracts/DATA_TYPES.md#신규-계약-hops-system-1)·[WIRE12절](../specs/contracts/WIRE_CONTRACT.md#12-신규-계약-hops-system-1)을 따르고 기존 본문은 전환 전 프로필로 보존한다.


## 6. hops-system-1 공통 계약 보완

MVP는 병합된 22개 테이블을 재사용한다. 별도 해결 확인·사례 버전·반영 작업·이벤트 전달 테이블을 추가하지 않는다. 6.1의 컬럼·제약은 3절 표와 Flyway V2~V21에 반영했으며(#23), 아래 표는 보완 근거로 남긴다. 승인 후 복수 버전 병행, 반영 시도 전체 이력, 프로세스 재시작 뒤 후속 호출 자동 복구는 후순위다.

### 6.1 기존 컬럼과 제약 보완

| 대상 | 추가/수정 정의 | 조건 |
|---|---|---|
| member | policy_revision INT NOT NULL DEFAULT1 | 현재 권한 문맥 버전. 시스템 PRIMARY/역할 변경 때 영향 회원 행과 같은 거래에서 증가 |
| it_system | ownership_version INT NOT NULL DEFAULT1 | 현재 PRIMARY 변경 세대. 권한 검증에 사용하며 과거 티켓 assignee를 바꾸지 않음 |
| inquiry | current_run_id UUID NULL FK→agent_run.id, result_revision INT NOT NULL DEFAULT1. inquiry_status CHECK에 CLOSED 추가 | current_run은 상담 실행만. 현재 입력 버전과 둘 다 맞는 결과만 적용. 상태는 IN_PROGRESS/WAITING_USER/RESOLVED/CLOSED이며 CLOSED는 모든 문제의 범위 밖 안내 저장 후에만 집계. 무응답 자동 종료에 사용하지 않음 |
| message | revision INT NOT NULL DEFAULT1, payload JSONB NOT NULL DEFAULT{} | revision≥1, 신규 최종 저장 행의 message_status=COMPLETED. 상담 run의 예약 answerMessageId를 최종 PK로 사용하며 미완성 행은 만들지 않음. payload의 requestId/answerRunId·문제/원문 구간/질문/가이드는 DATA11.3절 |
| inquiry_problem | creation_outcome VARCHAR(30) NULL, creation_input_version INT NULL | NULL은 작업 없음. PENDING/STORED/NOT_CREATED/UNKNOWN, STORED에만 같은 문제의 ticket 존재. 상태 확인과 행 잠금 후만 변경 |
| standard_request | agent_run_id는 신규 프로필에서 NOT NULL, UNIQUE(inquiry_problem_id,inquiry_version,agent_run_id) | 같은 문의 버전의 새 run도 새 스냅샷. 옛 스냅샷을 덮어쓰지 않음 |
| ticket | 기존 version=0 유지, 0~2,147,483,646 CHECK | 외부 ticketRevision=version+1. 외부 expected-1을 비교. ticket.work_type 저장값은5종, 신규 AI 제안은 INCIDENT를 제외한4종 |
| ticket_assignment | evidence JSONB NOT NULL DEFAULT{"messageIds":[],"note":null} | 현 배정 UNIQUE(ticket_id) WHERE ended_at IS NULL. 근거는 DATA11.3절 |
| ticket_work_log | 완료 RESOLVED/IMPLEMENTED이면 verification_result 비공백 CHECK | FAILED_ATTEMPT는 Curator/해결 확인을 만들지 않음. confirmed_cause·applied_version은 실제 담당자 입력만 저장 |
| agent_run | input_snapshot JSONB NOT NULL, QUEUED 상태 추가, started_at NULL 허용 | 입력은 AiRunRequest. 상담 answerMessageId는 input_snapshot에 고정하며 CURATION은null. result와 validation_result를 분리. CURATION이면 trigger_work_log_id 필수·UNIQUE(trigger_work_log_id) WHERE task=CURATION, 상담이면 처리 기록 없음 |
| idempotency_key | contract_version VARCHAR(50), operation_id UUID UNIQUE, operation_state VARCHAR(30), resource_type VARCHAR(30) NULL, resource_id UUID NULL, tombstoned_at TIMESTAMPTZ NULL | 신규 필수 키는 NOT NULL, UNIQUE(actor_member_id,contract_version,idempotency_key). operation_state=PENDING/SUCCEEDED/REJECTED/UNKNOWN. 메서드/경로/본문 해시를 재확인 후 버전 비교 |
| idempotency_key | response_status/response_body는 tombstone 정리 뒤 NULL 허용 | 상세 최소24시간. 만료 뒤410, 기존 키/해시/operation/resource는 보존. 같은 키 새 실행 금지. PENDING 접수는202 receipt이며 업무 반영+최종 journal은 한 거래로 저장 |
| knowledge_document_version | content_digest CHAR(64) NOT NULL, source_metadata JSONB NOT NULL, review_status VARCHAR(30) NOT NULL DEFAULT PENDING | 승인 원문·origin/출처/조건을 버전 고정. CASE의 source_metadata.indexRequests는 VECTOR/NEO4J 현재 IndexApplyRequest를 승인과 같은 거래로 보존. 기술 요청 메타데이터는 본문 해시·공개 DTO·모델 입력에서 제외 |
| knowledge_document | document_type에 CASE 허용 | 승인된 현재 사례의 검색용 문서. 원본과 승인 여부는 resolved_case에서 확인하며 직원 공개 범위도 재검사 |
| hops_knowledge.knowledge_chunk | embedding VECTOR, 차원은 모델 선정 후 정의. 기존 knowledge_document_version_id FK 유지, content_digest/chunk_digest CHAR(64) NOT NULL | 문서와 승인 CASE 모두 기존 문서 버전 경로로 검색 조각 저장. UNIQUE(knowledge_document_version_id,chunk_no). source_metadata의 원 사례 ID/버전과 실제 승인 내용을 대조 |

검색 조각은 별도 PostgreSQL **DB**로 옮기지 않고 같은 DB의 hops_knowledge **schema**에 둬 실제 FK를 유지한다. AI 계정은 이 schema의 조각 DML과 FK 검사용 REFERENCES만 허용하며 업무 원본 쓰기 권한을 주지 않는다. 같은 계정으로 Spring 업무 저장을 수행하지 않는다. AI RunLedger는 기존 결정대로 별도 hops_ai_runtime DB다. 임베딩/CASE 저장소 작업은 별도 서비스 계약을 사용한다.

### 6.2 기존 테이블을 사용하는 MVP 범위

- **해결 확인:** 가이드는 inquiry_problem.confirm_requested_at/resolved_at과 message.payload, 티켓은 ticket.confirm_requested_at/resolved_at과 ticket_event를 사용한다. 요청의 원 메시지·처리 기록·현재 확인 상태·revision은 기존 JSONB 메타데이터에 연결해 ResolutionRequest DTO로 투영한다. 새 테이블이나 그 테이블의 FK를 요구하지 않는다.
- **사례 편집과 승인:** resolved_case의 현재 초안과 version_no를 사용한다. 초안을 고칠 때 현재 행의 버전을 증가시키며 과거 초안 전체를 별도 행으로 보존하지 않는다. 승인 후에는 내용 편집을 막고, 현재 승인 버전과 두 반영 결과만 검증한다. 승인 후 새 초안·과거 승인 버전의 병행 관리는 MVP에 넣지 않는다.
- **반영과 검색:** resolved_case.vector_status/graph_status를 PENDING/SUCCEEDED/FAILED로 관리한다. 진행 중이거나 결과를 모르면 DB의 PENDING을 유지해 비공개로 둔다. 승인 거래에서 기존 knowledge_document/knowledge_document_version의 현재 CASE 스냅샷을 만들고 source_metadata.indexRequests에 저장소별 전체 요청을 보존한다. 현재 요청 두 건은 승인 버전·jobId·attempt·digest·approvedCase를 포함하며 journal 상세 만료 뒤에도 유지한다. 새 반영 이력 테이블·추가 컬럼은 필요하지 않다. 반영 결과의 버전이 현재 승인 버전과 다르면 성공으로 적용하지 않는다.
- **후속 실행과 수동 복구:** commit 뒤 Spring이 보존한 색인 요청을 전달한다. 호출 직전 서버가 종료돼도 담당자의 기존 index-checks에서 같은 작업을 확인하고, 유효한 원격 NOT_FOUND이면 같은 요청을 멱등 재접수할 수 있다. PENDING/UNKNOWN을 FAILED로 바꾸거나 새 attempt를 만드는 복구가 아니다. FAILED 재시도만 현재 요청의 attempt를 증가시켜 먼저 저장한다. 외부 호출은 DB 거래 밖에서 수행하고 자동 복구 큐는 추가하지 않는다. 자세한 동작은 [에이전트 I1.1](../contracts/agents/README.md#i11-승인-후-미전달-요청의-수동-복구)을 따른다.

resolution_request, resolved_case_version, case_index_job, outbox_event의 추가 DDL·FK 정의는 이번 범위에서 제외한다. 사용자 해결 확인, 승인 전 비공개, 같은 승인 버전의 양쪽 반영 성공 후 공개라는 제품 조건은 기존 테이블과 서버 검증으로 유지한다. 실제 migration·동시성·외부 호출 검증은 구현 PR에서 기록한다.
