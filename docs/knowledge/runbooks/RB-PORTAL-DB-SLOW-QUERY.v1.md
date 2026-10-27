---
runbook_id: RB-PORTAL-DB-SLOW-QUERY
version_no: 1
status: DRAFT
access_scope: STAFF
exposure_policy: INTERNAL_ONLY
ingest_status: PENDING
content_review_status: OWNER_CONFIRMED
team_review_status: PENDING
tool_binding_status: PENDING
scenario_validation_status: NOT_RUN
source_content_sha256: c3f08e24b7148c4c902f1bfd4b6af25de0fa003f9f8b819bc9280c915a619fc3
---

# 사내 포털 PostgreSQL Slow Query 진단 및 대응

## 적용 범위와 사용자 증상

- 서비스/컴포넌트: PORTAL / PORTAL_DB
- 기본 담당: DB
- 적용 환경: DEMO_PORTAL
- 제품: Spring Boot 4.1.1, PostgreSQL 18
- 문서 상태/인덱싱: DRAFT / PENDING

## 적용 가정

- Demo Portal 요청 ID와 DB application_name 또는 query_id를 안전한 상관 정보로 연결한다.
- pg_stat_statements와 실행계획 결과에서 SQL 원문·바인딩 값·사용자 정보를 마스킹한다.
- EXPLAIN ANALYZE는 실제 쿼리를 실행하므로 읽기 전용 SELECT와 IT 담당자 승인 조건에서만 사람이 수행한다.
- 구체적인 MCP Tool ID는 아직 확정되지 않아 tool_query는 null이다.

## 범위 밖

- lock wait 또는 deadlock이 주원인인 지연
- HikariCP 풀·DB 연결 한도 초과
- pg_sleep처럼 트리거 확인만 위한 인위적 지연을 실제 해결 사례로 저장하는 것
- Agent가 인덱스 생성·ANALYZE·SQL 수정·DB 설정 변경을 수행하는 작업

## 사용자 증상

### SYM-01

사용자 표현:
- 강의 검색 결과가 너무 늦게 나와요.
- 포털의 특정 목록만 계속 로딩돼요.

관측 신호:
- 특정 API의 응답시간 증가와 동일 query_id의 높은 실행시간이 연결된다.
- 반복 호출에서 mean_exec_time 또는 max_exec_time이 기준보다 높다.

### SYM-02

사용자 표현:
- 데이터가 많아진 뒤부터 검색이 느려졌어요.
- 같은 검색인데 조건에 따라 속도 차이가 커요.

관측 신호:
- 실행계획에서 넓은 Seq Scan 또는 많은 필터 제거가 관측된다.
- 예상 행 수와 실제 행 수의 차이가 크거나 적절한 Index Scan을 사용하지 않는다.

---

## 진단 1: 요청·로그 연결

### DIA-01 · 사용자 지연 요청과 느린 DB 작업을 연결한다.

- 조치: X-Request-Id, API 경로 템플릿, 발생 시각으로 구조화 애플리케이션 로그와 마스킹된 DB 실행시간 로그를 비교한다.
- 도구: 미등록 — 실행 불가
- 기대 신호:
- 느린 API 요청 시간대에 동일 서비스의 장기 실행 query_id가 존재한다.
- 네트워크·lock wait가 아닌 DB 실행시간이 응답 지연의 주요 구간이다.
- 일치 시: DIA-02에서 반복성과 영향도를 확인한다.
- 불일치 시: lock·풀·네트워크 또는 애플리케이션 처리 지연을 조사한다.
- 근거: PE-003, PE-020, PE-021

### DIA-02 · 단발성 지연과 반복되는 slow query를 구분한다.

- 조치: pg_stat_statements의 마스킹된 query_id, calls, mean_exec_time, max_exec_time, total_exec_time을 비교한다.
- 도구: 미등록 — 실행 불가
- 기대 신호:
- 동일 query_id가 반복 호출되며 평균 또는 최대 실행시간이 높다.
- 통계 초기화 시점을 고려해 비교 구간을 명시한다.
- 일치 시: DIA-03에서 실행계획을 확인한다.
- 불일치 시: 단발성 지연이면 확정 원인으로 저장하지 않고 재현 여부를 확인한다.
- 근거: PE-023, PE-024

### DIA-03 · 실행계획의 scan 방식과 행 수 추정을 확인한다.

- 조치: IT 담당자가 승인된 비식별화 읽기 SELECT에 먼저 EXPLAIN을 사용해 scan node, 비용, 예상 행 수를 확인한다. Agent는 SQL을 실행하지 않는다.
- 도구: 미등록 — 실행 불가
- 기대 신호:
- 선택도가 높은 조건인데 넓은 Seq Scan이 사용된다.
- 예상 행 수가 데이터 분포와 크게 어긋나거나 필터 단계에서 많은 행이 제거된다.
- 일치 시: CAUSE-01 또는 CAUSE-02 후보를 기록한다.
- 불일치 시: 실행계획만으로 원인이 설명되지 않으면 애플리케이션 처리와 I/O 범위를 추가 확인한다.
- 근거: PE-025

---

## 진단 2: 계층 구분과 근거 충분성

### DIA-04 · 실제 수행 통계가 필요한지 안전하게 판단한다.

- 조치: 추가 확인이 필요하면 IT 담당자가 읽기 전용 SELECT, 격리 환경, 승인 조건에서만 EXPLAIN ANALYZE를 수행해 actual rows, time, loops, buffers를 확인한다.
- 도구: 미등록 — 실행 불가
- 기대 신호:
- 예상과 실제 행 수·시간 차이가 원인 후보를 지지한다.
- 측정 오버헤드와 시연용 데이터 크기의 한계를 함께 기록한다.
- 일치 시: 근거와 한계를 포함해 DB로 이관한다.
- 불일치 시: 안전 조건을 충족하지 못하면 실행하지 않고 기존 근거만으로 이관한다.
- 근거: PE-026, PE-027

---

## 원인 후보와 반대 신호

### CAUSE-01 · 조회 조건에 적합한 인덱스가 없어 선택도가 높은 검색도 넓은 순차 스캔을 수행한다.

- 필요 신호 ID: SIG-019, SIG-020, SIG-021, SIG-022, SIG-023
- 필요 신호:
- 반복되는 높은 query 실행시간
- 선택도가 높은 조건에서 Seq Scan 또는 과도한 rows removed
- 후보 인덱스가 현재 실행계획에 사용되지 않음
- 반대 신호 ID: SIG-024, SIG-015
- 반대 신호:
- 적절한 Index Scan을 사용하고 DB 실행시간이 짧음
- 실제 지연 대부분이 lock wait 또는 애플리케이션 처리임
- 담당 부서: DB
- 근거: PE-023, PE-025, PE-026

#### 레지스트리에서 결합된 신호 정의

- SIG-019 [필요] · query_id (DB_QUERY_ID)
  - 매칭: query_id
  - 의미: 같은 형태의 쿼리 통계를 연결
  - 확정 제한: SQL 원문 대신 사용하는 식별자
- SIG-020 [필요] · calls (DB_QUERY_STAT)
  - 매칭: pg_stat_statements.calls
  - 의미: query_id의 누적 실행 횟수
  - 확정 제한: 통계 초기화 시점을 함께 기록
- SIG-021 [필요] · mean_exec_time (DB_QUERY_STAT)
  - 매칭: pg_stat_statements.mean_exec_time
  - 의미: query_id의 평균 실행시간
  - 확정 제한: 평균만으로 최대 지연을 숨기지 않음
- SIG-022 [필요] · max_exec_time (DB_QUERY_STAT)
  - 매칭: pg_stat_statements.max_exec_time
  - 의미: query_id의 최대 실행시간
  - 확정 제한: 단발성 이상치와 반복성을 구분
- SIG-023 [필요] · Seq Scan (QUERY_PLAN)
  - 매칭: Seq Scan
  - 의미: 테이블 순차 스캔
  - 확정 제한: 작은 테이블에서는 정상일 수 있음
- SIG-024 [반대] · Index Scan (QUERY_PLAN)
  - 매칭: Index Scan|Bitmap Index Scan
  - 의미: 인덱스를 사용한 조회
  - 확정 제한: 사용 자체가 항상 빠름을 의미하지 않음
- SIG-015 [반대] · Lock wait (DB_WAIT)
  - 매칭: wait_event_type=Lock
  - 의미: 잠금 해제를 기다리는 세션
  - 확정 제한: blocker·waiter 관계 확인 필요

### CAUSE-02 · 통계 또는 데이터 분포 변화로 예상 행 수가 실제와 크게 달라 비효율적인 실행계획이 선택된다.

- 필요 신호 ID: SIG-019, SIG-023, SIG-025
- 필요 신호:
- estimated rows와 actual rows의 큰 차이
- 데이터량·분포 변경 이후 성능 저하
- 동일 query_id에서 비효율적인 join·scan 선택
- 반대 신호 ID: SIG-024
- 반대 신호:
- 예상 행 수와 실제 행 수가 유사함
- 인위적 pg_sleep만 지연 원인임
- 담당 부서: DB
- 근거: PE-025, PE-026, PE-027

#### 레지스트리에서 결합된 신호 정의

- SIG-019 [필요] · query_id (DB_QUERY_ID)
  - 매칭: query_id
  - 의미: 같은 형태의 쿼리 통계를 연결
  - 확정 제한: SQL 원문 대신 사용하는 식별자
- SIG-023 [필요] · Seq Scan (QUERY_PLAN)
  - 매칭: Seq Scan
  - 의미: 테이블 순차 스캔
  - 확정 제한: 작은 테이블에서는 정상일 수 있음
- SIG-025 [필요] · estimated vs actual rows (QUERY_PLAN)
  - 매칭: estimated_rows/actual_rows
  - 의미: 플래너 예상 행 수와 실제 행 수 차이
  - 확정 제한: EXPLAIN ANALYZE는 실제 실행과 승인 조건 필요
- SIG-024 [반대] · Index Scan (QUERY_PLAN)
  - 매칭: Index Scan|Bitmap Index Scan
  - 의미: 인덱스를 사용한 조회
  - 확정 제한: 사용 자체가 항상 빠름을 의미하지 않음

---

## Agent 사용자 안내와 처리 경계

### GUID-01 · Agent 안내

- 조건: 특정 검색·목록만 느리고 기능 자체는 완료되는 경우
- 안내: 사용자에게 조회 지연을 확인해 담당 부서에서 점검 중이라고 안내하고, 같은 요청을 반복 실행하지 않도록 요청한다.
- 예상 결과: 중복 부하를 늘리지 않고 업무 영향과 요청 추적 정보를 보존한다.
- 미해결 시: X-Request-Id와 발생 시각을 첨부해 DB로 이관한다.
- 근거: PE-020, PE-023

### GUID-02 · Agent 안내

- 조건: 조회가 timeout으로 실패하거나 안내 후에도 지연이 반복되는 경우
- 안내: 사용자에게 데이터 조회 성능 장애로 이관했음을 안내한다. SQL·테이블·인덱스 정보는 노출하지 않는다.
- 예상 결과: 사용자가 장애 처리 상태를 이해하고 반복 재시도를 중단한다.
- 미해결 시: 티켓 진행 상태를 안내하고 추가 사용자 조치를 요구하지 않는다.
- 근거: PE-020, PE-021, PE-023

### Agent 처리 경계

- 허용 결과: GUIDE_USER, HANDOFF
- 이관 사유: INSUFFICIENT_EVIDENCE, GUIDANCE_NOT_RESOLVED
- 금지 작업: CODE_CHANGE, CONFIG_CHANGE, DEPLOY, RESTART, DB_WRITE
- 해결 확정 근거: USER_CONFIRMATION, BACKEND_STATE

---

## 이관 후 IT 담당자 조치·검증

### RES-01 · 사람 수행

- 조치: Demo Portal의 운영 유사 데이터량에서 느린 query_id와 실행계획을 재현하고 기준 실행시간·행 수·scan 방식을 기록한다.
- 승인 필요: 아니오
- 확인: 사용자 지연 API와 동일 query_id의 병목 실행계획이 연결된다.
- 롤백: 해당 없음
- 근거: PE-023, PE-025, PE-026, PE-027

### RES-02 · 사람 수행

- 조치: 원인이 인덱스 누락이면 쓰기 비용·저장 공간·중복 인덱스를 검토해 승인 후 인덱스를 추가한다. 원인이 행 수 추정이면 통계 갱신과 쿼리 구조를 검토한다.
- 승인 필요: 예
- 확인: 변경 후 실행계획과 실제 실행시간이 개선되고 쓰기 경로의 회귀가 없다.
- 롤백: 부작용이나 성능 회귀가 발생하면 신규 인덱스 또는 변경을 승인 절차에 따라 되돌린다.
- 근거: PE-025, PE-026

### RES-03 · 사람 수행

- 조치: 동일 데이터량·동일 조건의 회귀 테스트와 사용자 API 테스트를 수행하고 작은 시연 데이터 결과를 운영 규모로 일반화하지 않는다.
- 승인 필요: 예
- 확인: 목표 응답시간을 충족하고 pg_stat_statements와 실행계획에서 같은 병목이 재발하지 않는다.
- 롤백: 검증 기준을 충족하지 못하면 해결 처리하지 않고 티켓을 재개한다.
- 근거: PE-023, PE-026, PE-027

### 완료 판정

성공 신호:
- 동일 데이터량과 조건에서 API 응답시간이 목표 범위다.
- 동일 query_id의 실행시간과 실행계획 병목이 개선된다.
- 사용자 또는 백엔드 상태가 정상 조회를 확인한다.

실패 신호:
- 동일 query_id의 지연이 지속된다.
- lock·풀·네트워크 등 다른 병목이 확인된다.
- 개선 결과가 작은 시연 데이터에서만 나타난다.

### 이관

- 대상: DB
- 조건:
- 반복되는 slow query 근거 확인
- 안전한 실행계획 검증이 필요함
- 인덱스·통계·SQL 변경이 필요함
- 첨부 근거:
- 발생 시각·X-Request-Id·API 경로 템플릿
- 마스킹된 query_id와 calls·mean·max 실행시간
- scan 종류와 예상·실제 행 수
- 데이터량과 측정 환경
- lock wait 유무

---

## 사용자 안내 정책

## 안전한 사용자 안내

사내 포털의 데이터 조회 지연을 확인하여 DB 담당 부서에서 점검 중입니다. 요청 추적 번호는 {request_id}입니다.

## 노출 금지

- SQL 원문·바인딩 값
- 테이블·인덱스 내부 이름
- DB 사용자·IP·서버명
- EXPLAIN ANALYZE 실행 명령
- 작은 시연 데이터 결과를 운영 성능처럼 단정하는 표현
