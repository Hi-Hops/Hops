---
runbook_id: RB-PORTAL-DB-CONNECTION-LIMIT
version_no: 1
status: DRAFT
access_scope: STAFF
exposure_policy: INTERNAL_ONLY
ingest_status: PENDING
content_review_status: OWNER_CONFIRMED
team_review_status: PENDING
tool_binding_status: PENDING
scenario_validation_status: NOT_RUN
source_content_sha256: 98bf4672f2db4a6e62978954b9bc790e903b9d063a35ca5d27a6b2c9f0b1f512
---

> **PRD 1.44 전환 전 자료:** 이 문서는 기존 v0 데이터의 자동 생성본이다. 파일명의 `.v1`은 해당 문서의 버전이며 새 기획용 데이터셋 v1을 뜻하지 않는다. 부서 배정·질문 한도·해결 정책 등은 현재 기획과 다를 수 있으므로 새 기획의 검색 정본으로 사용하지 않는다. [새 정책](../policies/POLICY-PRD-1.44.md) · [자료 전환 검토](../DATA_MIGRATION_REVIEW.md)

# 사내 포털 PostgreSQL 연결 한도 초과 진단 및 대응

## 적용 범위와 사용자 증상

- 서비스/컴포넌트: PORTAL / PORTAL_DB
- 기본 담당: DB
- 적용 환경: DEMO_PORTAL
- 제품: Spring Boot 4.1.1, HikariCP 7.0.2, PostgreSQL 18
- 문서 상태/인덱싱: DRAFT / PENDING

## 적용 가정

- Demo Portal과 다른 DB 클라이언트는 식별 가능한 application_name을 사용한다.
- numbackends, 마스킹된 pg_stat_activity 집계, HikariCP 풀 관측값을 읽기 전용으로 조회한다.
- DB 사용자명·클라이언트 IP·SQL 원문은 Agent에 제공하기 전에 마스킹한다.
- 구체적인 MCP Tool ID는 아직 확정되지 않아 tool_query는 null이다.

## 범위 밖

- DB 서버 연결 수에 여유가 있는 애플리케이션 풀 자체 고갈
- 인증 실패·네트워크 단절로 인한 DB 접속 실패
- Agent가 세션 종료, max_connections 변경 또는 DB 재시작을 수행하는 작업

## 사용자 증상

### SYM-01

사용자 표현:
- 포털 전체에서 데이터 조회가 안 돼요.
- 로그인은 되는데 강의와 학습 기록을 불러오지 못해요.

관측 신호:
- PostgreSQL이 일반 연결을 거부하는 연결 슬롯 오류를 기록한다.
- PORTAL의 복수 API에서 DB 연결 실패가 같은 시간대에 발생한다.

### SYM-02

사용자 표현:
- 사용자가 많아진 뒤부터 포털이 계속 실패해요.
- 포털뿐 아니라 DB를 쓰는 기능들이 함께 느리거나 실패해요.

관측 신호:
- DB 전체 연결 수가 max_connections의 일반 사용 가능 범위에 도달한다.
- application_name별 세션 집계에서 특정 클라이언트 또는 여러 클라이언트의 급증이 확인된다.

---

## 진단 1: 요청·로그 연결

### DIA-01 · PORTAL 오류와 PostgreSQL 연결 거부를 시간상 연결한다.

- 조치: X-Request-Id와 발생 시각으로 애플리케이션의 DB 연결 실패 로그와 PostgreSQL의 연결 슬롯 오류를 비교한다.
- 도구: 미등록 — 실행 불가
- 기대 신호:
- PORTAL 요청 실패 시각에 PostgreSQL 연결 거부가 발생한다.
- 오류가 단일 SQL이 아니라 새 연결 획득 단계에서 발생한다.
- 일치 시: DIA-02에서 서버 전체 연결 수를 확인한다.
- 불일치 시: 인증·네트워크·애플리케이션 풀 장애를 조사한다.
- 근거: PE-014, PE-015

### DIA-02 · DB 서버 전체 연결 한도 도달 여부를 확인한다.

- 조치: PostgreSQL max_connections, 예약 슬롯 정책, pg_stat_database.numbackends를 읽기 전용으로 비교한다.
- 도구: 미등록 — 실행 불가
- 기대 신호:
- 전체 연결 수가 일반 연결에 허용된 한도에 도달한다.
- 예약 슬롯 때문에 일반 애플리케이션 연결이 먼저 거부될 수 있음을 함께 확인한다.
- 일치 시: DIA-03에서 연결 점유 주체를 분류한다.
- 불일치 시: DB 한도에 여유가 있으면 HikariCP 풀 고갈 런북으로 전환한다.
- 근거: PE-014, PE-016

### DIA-03 · PORTAL 세션 급증과 다른 클라이언트 세션 급증을 구분한다.

- 조치: 마스킹된 pg_stat_activity를 application_name과 상태별로 집계해 PORTAL과 기타 클라이언트의 세션 수, 연결 시작 시각, idle in transaction 여부를 비교한다.
- 도구: 미등록 — 실행 불가
- 기대 신호:
- PORTAL application_name의 세션만 급증하거나, 별도 클라이언트의 세션 급증이 명확히 구분된다.
- 원시 사용자명·IP·SQL 없이도 점유 주체를 서비스 수준으로 분류할 수 있다.
- 일치 시: 해당 원인 후보와 협업 부서를 기록한다.
- 불일치 시: 주체를 특정할 수 없으면 DB에 근거 부족으로 이관한다.
- 근거: PE-015, PE-016

---

## 진단 2: 계층 구분과 근거 충분성

### DIA-04 · DB 한도 초과와 애플리케이션 풀 설정의 관계를 확인한다.

- 조치: PORTAL 인스턴스 수와 인스턴스별 HikariCP max·active를 확인해 이론상 최대 연결 합과 실제 DB 연결 수를 비교한다.
- 도구: 미등록 — 실행 불가
- 기대 신호:
- PORTAL의 최대 연결 합이 DB 일반 사용 가능 범위를 초과하거나 다른 클라이언트와 합쳐 한도에 도달한다.
- 풀 관측값과 DB 세션 집계가 같은 방향을 보인다.
- 일치 시: DB를 기본 담당으로 이관하고 BACKEND 협업 필요 여부를 표시한다.
- 불일치 시: 설정 관계가 설명되지 않으면 결론을 확정하지 않고 추가 점검 항목을 첨부한다.
- 근거: PE-006, PE-008, PE-014, PE-016

---

## 원인 후보와 반대 신호

### CAUSE-01 · PORTAL 인스턴스의 세션 또는 풀 연결이 증가해 DB 일반 연결 한도에 도달했다.

- 필요 신호 ID: SIG-010, SIG-011, SIG-012, SIG-013
- 필요 신호:
- DB 전체 연결 수가 일반 사용 가능 한도에 도달
- PORTAL application_name 세션 비중 또는 증가량이 큼
- PORTAL 풀 관측값도 연결 증가를 지지
- 반대 신호 ID: 없음
- 반대 신호:
- PORTAL 세션 수는 안정적이고 다른 클라이언트만 급증
- DB 전체 연결 수가 한도보다 충분히 낮음
- 담당 부서: DB
- 근거: PE-006, PE-014, PE-015, PE-016

#### 레지스트리에서 결합된 신호 정의

- SIG-010 [필요] · max_connections (DB_SETTING)
  - 매칭: max_connections
  - 의미: PostgreSQL 최대 동시 연결 수
  - 확정 제한: 예약 슬롯과 실제 연결 수를 함께 확인
- SIG-011 [필요] · 예약 연결 슬롯 오류 (LOG_PATTERN)
  - 매칭: remaining connection slots are reserved
  - 의미: 일반 연결 슬롯이 소진됨
  - 확정 제한: 연결 점유 주체는 별도 확인
- SIG-012 [필요] · numbackends (DB_STAT)
  - 매칭: pg_stat_database.numbackends
  - 의미: DB별 현재 연결 수
  - 확정 제한: 클러스터 전체·예약 슬롯과 함께 해석
- SIG-013 [필요] · application_name별 세션 수 (DB_STAT)
  - 매칭: pg_stat_activity.application_name
  - 의미: 연결 점유 서비스를 분류
  - 확정 제한: 사용자명·IP·SQL은 마스킹

### CAUSE-02 · PORTAL 외의 DB 클라이언트 세션 급증이 연결 슬롯을 점유해 PORTAL의 새 연결이 거부되었다.

- 필요 신호 ID: SIG-010, SIG-011, SIG-012, SIG-013
- 필요 신호:
- DB 전체 연결 한도 도달
- 다른 application_name의 세션 급증
- PORTAL 풀 최대치만으로는 전체 연결 수를 설명할 수 없음
- 반대 신호 ID: 없음
- 반대 신호:
- PORTAL 세션만 급증
- 외부 클라이언트 세션이 평상 범위
- 담당 부서: DB
- 근거: PE-014, PE-015, PE-016

#### 레지스트리에서 결합된 신호 정의

- SIG-010 [필요] · max_connections (DB_SETTING)
  - 매칭: max_connections
  - 의미: PostgreSQL 최대 동시 연결 수
  - 확정 제한: 예약 슬롯과 실제 연결 수를 함께 확인
- SIG-011 [필요] · 예약 연결 슬롯 오류 (LOG_PATTERN)
  - 매칭: remaining connection slots are reserved
  - 의미: 일반 연결 슬롯이 소진됨
  - 확정 제한: 연결 점유 주체는 별도 확인
- SIG-012 [필요] · numbackends (DB_STAT)
  - 매칭: pg_stat_database.numbackends
  - 의미: DB별 현재 연결 수
  - 확정 제한: 클러스터 전체·예약 슬롯과 함께 해석
- SIG-013 [필요] · application_name별 세션 수 (DB_STAT)
  - 매칭: pg_stat_activity.application_name
  - 의미: 연결 점유 서비스를 분류
  - 확정 제한: 사용자명·IP·SQL은 마스킹

---

## Agent 사용자 안내와 처리 경계

### GUID-01 · Agent 안내

- 조건: DB 연결 한도 오류가 일시적으로 해소되고 서비스가 회복된 경우
- 안내: 사용자에게 같은 동작을 한 번만 다시 시도하도록 안내하고, 실패하면 X-Request-Id와 시각을 요청한다.
- 예상 결과: 연결 슬롯이 확보되었다면 요청이 정상 처리된다.
- 미해결 시: 반복 오류 근거를 첨부해 DB로 이관한다.
- 근거: PE-014, PE-016

### GUID-02 · Agent 안내

- 조건: DB 연결 한도가 계속 포화되거나 다수 사용자가 영향을 받는 경우
- 안내: 사용자에게 포털 데이터 연결 장애를 확인해 DB 담당 부서로 이관했다고 안내한다. DB 설정과 세션 정보는 노출하지 않는다.
- 예상 결과: 사용자가 장애 범위와 이관 상태를 이해한다.
- 미해결 시: 사용자 재시도를 반복하지 않고 티켓 진행 상태를 안내한다.
- 근거: PE-014, PE-015, PE-016

### Agent 처리 경계

- 허용 결과: GUIDE_USER, HANDOFF
- 이관 사유: INSUFFICIENT_EVIDENCE, GUIDANCE_NOT_RESOLVED
- 금지 작업: CODE_CHANGE, CONFIG_CHANGE, DEPLOY, RESTART, DB_WRITE
- 해결 확정 근거: USER_CONFIRMATION, BACKEND_STATE

---

## 이관 후 IT 담당자 조치·검증

### RES-01 · 사람 수행

- 조치: 예약된 관리 연결을 포함한 승인된 관리 경로로 application_name별 세션 점유와 장기 트랜잭션을 확인한다.
- 승인 필요: 예
- 확인: PORTAL 또는 다른 클라이언트 중 연결 급증 주체가 서비스 수준으로 확인된다.
- 롤백: 해당 없음
- 근거: PE-014, PE-015, PE-016

### RES-02 · 사람 수행

- 조치: 원인 서비스 담당자와 협의해 불필요한 연결·장기 열린 트랜잭션의 원인을 제거한다. 세션 강제 종료는 영향 범위를 확인하고 승인받은 경우에만 수행한다.
- 승인 필요: 예
- 확인: 전체 연결 수가 정상 범위로 내려가고 PORTAL 새 연결이 성공한다.
- 롤백: 정상 업무 세션에 영향이 생기면 추가 종료를 중단하고 서비스 복구 절차를 재평가한다.
- 근거: PE-015, PE-016

### RES-03 · 사람 수행

- 조치: max_connections 또는 애플리케이션 풀 조정이 필요하면 메모리·예약 슬롯·전체 인스턴스 연결 합을 계산하고 변경·재시작 승인을 받아 적용한다.
- 승인 필요: 예
- 확인: 변경 후 일반 연결과 관리 예약 슬롯이 의도대로 동작하고 목표 부하에서 연결 거부가 재발하지 않는다.
- 롤백: 메모리 압박이나 신규 연결 문제가 발생하면 승인된 이전 설정으로 복구한다.
- 근거: PE-006, PE-008, PE-014

### 완료 판정

성공 신호:
- DB 전체 연결 수가 일반 사용 가능 범위로 회복된다.
- PORTAL의 새 DB 연결과 사용자 요청이 성공한다.
- 같은 부하에서 연결 슬롯 오류가 재발하지 않는다.

실패 신호:
- 연결 수가 계속 한도에 머문다.
- 일반 연결이 계속 거부된다.
- 세션 급증 주체 또는 재발 원인을 확인하지 못한다.

### 이관

- 대상: DB
- 조건:
- DB 전체 연결 한도 도달
- 연결 점유 주체를 식별할 수 없음
- 세션 종료·DB 설정 변경·재시작이 필요함
- 첨부 근거:
- 오류 시각·X-Request-Id·마스킹된 연결 오류
- max_connections와 예약 슬롯 값
- DB별 numbackends
- application_name별 마스킹 세션 집계
- PORTAL 풀 active·max

---

## 사용자 안내 정책

## 안전한 사용자 안내

사내 포털의 데이터 연결 장애를 확인하여 DB 담당 부서에서 점검 중입니다. 요청 추적 번호는 {request_id}입니다.

## 노출 금지

- DB 사용자명·클라이언트 IP
- SQL 원문·바인딩 값
- DB 주소·비밀번호
- 강제 종료 대상 세션 정보
- 확인되지 않은 특정 서비스의 책임을 단정하는 표현
