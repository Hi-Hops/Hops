---
runbook_id: RB-PORTAL-DB-LOCK
version_no: 1
status: DRAFT
access_scope: STAFF
exposure_policy: INTERNAL_ONLY
ingest_status: PENDING
content_review_status: OWNER_CONFIRMED
team_review_status: PENDING
tool_binding_status: PENDING
scenario_validation_status: NOT_RUN
source_content_sha256: 1c6d5714461878edc9925f069f0a291eed1c6a98a0cdf4eb23d44d2480443d38
---

> **PRD 1.44 전환 전 자료:** 이 문서는 기존 v0 데이터의 자동 생성본이다. 파일명의 `.v1`은 해당 문서의 버전이며 새 기획용 데이터셋 v1을 뜻하지 않는다. 부서 배정·질문 한도·해결 정책 등은 현재 기획과 다를 수 있으므로 새 기획의 검색 정본으로 사용하지 않는다. [새 정책](../policies/POLICY-PRD-1.44.md) · [자료 전환 검토](../DATA_MIGRATION_REVIEW.md)

# 사내 포털 PostgreSQL Lock 및 Deadlock 진단 및 대응

## 적용 범위와 사용자 증상

- 서비스/컴포넌트: PORTAL / PORTAL_DB
- 기본 담당: DB
- 적용 환경: DEMO_PORTAL
- 제품: Spring Boot 4.1.1, PostgreSQL 18
- 문서 상태/인덱싱: DRAFT / PENDING

## 적용 가정

- Demo Portal이 DB 세션에 application_name과 요청 상관 식별자를 남긴다.
- pg_stat_activity, pg_locks와 마스킹된 DB 로그를 읽기 전용으로 조회한다.
- PID, 사용자명, IP, SQL 원문과 바인딩 값은 Agent 응답에 노출하지 않는다.
- 구체적인 MCP Tool ID는 아직 확정되지 않아 tool_query는 null이다.

## 범위 밖

- 단순 slow query지만 lock wait 신호가 없는 장애
- DB 연결 한도 초과로 새 세션을 만들지 못하는 장애
- Agent가 트랜잭션 종료·세션 종료·SQL 수정·DB 설정 변경을 수행하는 작업

## 사용자 증상

### SYM-01

사용자 표현:
- 강의 수강 완료 저장이 계속 멈춰 있어요.
- 한 사용자가 수정 중이면 다른 사람의 저장이 안 돼요.

관측 신호:
- 요청 세션의 wait_event_type이 Lock이고 승인되지 않은 lock 대기가 존재한다.
- blocker가 종료되면 대기 요청이 진행된다.

### SYM-02

사용자 표현:
- 동시에 저장하면 한쪽에서 오류가 나요.
- 저장 실패가 간헐적으로 발생하고 다시 하면 되는 경우가 있어요.

관측 신호:
- PostgreSQL deadlock detected 오류가 기록되고 관련 트랜잭션 하나가 중단된다.
- 서로 다른 트랜잭션이 반대 순서로 같은 자원을 잠근 흔적이 있다.

---

## 진단 1: 요청·로그 연결

### DIA-01 · 사용자 요청의 지연·실패와 DB 대기 또는 deadlock 로그를 연결한다.

- 조치: X-Request-Id, 발생 시각, API 경로 템플릿으로 애플리케이션 로그와 마스킹된 PostgreSQL 로그를 비교한다.
- 도구: 미등록 — 실행 불가
- 기대 신호:
- 같은 시간대 요청에서 Lock 대기 또는 deadlock 오류가 확인된다.
- 일반 API 예외가 아니라 DB 트랜잭션 단계와 연결된다.
- 일치 시: DIA-02에서 현재 대기 관계를 확인한다.
- 불일치 시: lock 근거가 없으면 slow query 또는 애플리케이션 오류를 조사한다.
- 근거: PE-021, PE-022, PE-028

### DIA-02 · 대기 세션과 차단 세션의 관계를 확인한다.

- 조치: pg_stat_activity와 pg_locks의 마스킹된 결과에서 waiter·blocker 식별자, wait_event_type, lock granted, 트랜잭션 시작 시각과 상태를 확인한다.
- 도구: 미등록 — 실행 불가
- 기대 신호:
- granted=false인 대기 lock과 충돌하는 blocker가 연결된다.
- 대기 세션의 wait_event_type이 Lock이거나 관련 로그가 deadlock을 보고한다.
- 일치 시: DIA-03에서 단방향 lock wait와 순환 deadlock을 구분한다.
- 불일치 시: 현재 대기가 사라졌다면 당시 로그를 사용하고, 로그도 없으면 근거 부족으로 이관한다.
- 근거: PE-015, PE-017

### DIA-03 · 단순 lock wait와 deadlock을 구분한다.

- 조치: 대기 관계가 한 방향인지 순환인지, PostgreSQL이 deadlock을 감지해 한 트랜잭션을 중단했는지 확인한다.
- 도구: 미등록 — 실행 불가
- 기대 신호:
- 단방향 대기이면 blocker 종료 후 waiter가 진행된다.
- deadlock이면 순환 대기와 자동 중단 오류가 확인된다.
- 일치 시: CAUSE-01 또는 CAUSE-02로 분류한다.
- 불일치 시: 잠금 종류와 관계가 불명확하면 확정 원인 없이 DB로 이관한다.
- 근거: PE-017, PE-018, PE-019

---

## 진단 2: 계층 구분과 근거 충분성

### DIA-04 · 장기 열린 트랜잭션 또는 잠금 순서 문제의 근거를 정리한다.

- 조치: 트랜잭션 상태·시작 시각·application_name과 반복되는 대기 패턴을 마스킹 집계한다. log_lock_waits 시점은 deadlock_timeout과 함께 해석한다.
- 도구: 미등록 — 실행 불가
- 기대 신호:
- idle in transaction 또는 장기 트랜잭션이 blocker로 반복된다.
- deadlock 사례는 동일 자원의 반대 잠금 순서가 반복된다.
- 일치 시: DB를 기본 담당으로 하고 코드 변경이 필요하면 BACKEND 협업을 표시한다.
- 불일치 시: 관측 시점 한계와 미수집 정보를 이관 근거에 명시한다.
- 근거: PE-015, PE-019, PE-022, PE-028

---

## 원인 후보와 반대 신호

### CAUSE-01 · 장기 열린 트랜잭션 또는 동일 행을 점유한 세션 때문에 다른 쓰기 요청이 lock을 기다린다.

- 필요 신호 ID: SIG-014, SIG-015, SIG-016
- 필요 신호:
- waiter와 단일 blocker 관계
- wait_event_type=Lock 또는 granted=false
- blocker 종료 뒤 waiter 진행
- 반대 신호 ID: SIG-017
- 반대 신호:
- 순환 대기와 deadlock detected 오류
- lock 대기 없이 실행시간만 김
- 담당 부서: DB
- 근거: PE-015, PE-017, PE-018, PE-022

#### 레지스트리에서 결합된 신호 정의

- SIG-014 [필요] · idle in transaction (DB_STATE)
  - 매칭: idle in transaction
  - 의미: 열린 트랜잭션이 작업 없이 유지됨
  - 확정 제한: 잠금 보유 여부를 별도 확인
- SIG-015 [필요] · Lock wait (DB_WAIT)
  - 매칭: wait_event_type=Lock
  - 의미: 잠금 해제를 기다리는 세션
  - 확정 제한: blocker·waiter 관계 확인 필요
- SIG-016 [필요] · 미승인 lock (DB_LOCK)
  - 매칭: pg_locks.granted=false
  - 의미: 아직 획득하지 못한 대기 lock
  - 확정 제한: 충돌 lock과 세션 관계를 함께 확인
- SIG-017 [반대] · deadlock detected (DB_ERROR)
  - 매칭: deadlock detected
  - 의미: PostgreSQL이 순환 대기를 감지하고 트랜잭션 하나를 중단함
  - 확정 제한: 재시도 안전성과 잠금 순서를 별도 확인

### CAUSE-02 · 두 개 이상의 트랜잭션이 자원을 서로 다른 순서로 잠가 순환 대기가 발생했고 PostgreSQL이 한 트랜잭션을 중단했다.

- 필요 신호 ID: SIG-015, SIG-016, SIG-017
- 필요 신호:
- 순환 blocker·waiter 관계 또는 deadlock 로그
- PostgreSQL의 deadlock detected 오류
- 관련 트랜잭션 중 하나의 자동 중단
- 반대 신호 ID: SIG-014
- 반대 신호:
- 한 방향 대기만 존재
- blocker 종료까지 기다린 뒤 오류 없이 완료
- 담당 부서: DB
- 근거: PE-019, PE-021, PE-028

#### 레지스트리에서 결합된 신호 정의

- SIG-015 [필요] · Lock wait (DB_WAIT)
  - 매칭: wait_event_type=Lock
  - 의미: 잠금 해제를 기다리는 세션
  - 확정 제한: blocker·waiter 관계 확인 필요
- SIG-016 [필요] · 미승인 lock (DB_LOCK)
  - 매칭: pg_locks.granted=false
  - 의미: 아직 획득하지 못한 대기 lock
  - 확정 제한: 충돌 lock과 세션 관계를 함께 확인
- SIG-017 [필요] · deadlock detected (DB_ERROR)
  - 매칭: deadlock detected
  - 의미: PostgreSQL이 순환 대기를 감지하고 트랜잭션 하나를 중단함
  - 확정 제한: 재시도 안전성과 잠금 순서를 별도 확인
- SIG-014 [반대] · idle in transaction (DB_STATE)
  - 매칭: idle in transaction
  - 의미: 열린 트랜잭션이 작업 없이 유지됨
  - 확정 제한: 잠금 보유 여부를 별도 확인

---

## Agent 사용자 안내와 처리 경계

### GUID-01 · Agent 안내

- 조건: deadlock으로 한 요청이 중단됐고 해당 업무가 안전하게 재시도 가능한 조회·멱등 작업인 경우
- 안내: 사용자에게 현재 화면을 새로 확인한 뒤 같은 동작을 한 번만 다시 시도하도록 안내한다. 결제·중복 저장 가능 작업에는 임의 재시도를 안내하지 않는다.
- 예상 결과: 중단된 트랜잭션 이후 잠금이 해제되었다면 요청이 정상 완료된다.
- 미해결 시: 새 요청 ID와 시각을 첨부해 DB로 이관한다.
- 근거: PE-019

### GUID-02 · Agent 안내

- 조건: lock wait가 지속되거나 재시도 안전성을 확인할 수 없는 경우
- 안내: 사용자에게 데이터 저장 지연을 확인하여 DB 담당 부서로 이관했다고 안내하고 반복 저장을 중단하도록 요청한다.
- 예상 결과: 중복 작업 위험 없이 담당자 복구를 기다린다.
- 미해결 시: 티켓 진행 상태를 안내하고 추가 조치를 반복하지 않는다.
- 근거: PE-017, PE-018, PE-019

### Agent 처리 경계

- 허용 결과: GUIDE_USER, HANDOFF
- 이관 사유: INSUFFICIENT_EVIDENCE, GUIDANCE_NOT_RESOLVED
- 금지 작업: CODE_CHANGE, CONFIG_CHANGE, DEPLOY, RESTART, DB_WRITE
- 해결 확정 근거: USER_CONFIRMATION, BACKEND_STATE

---

## 이관 후 IT 담당자 조치·검증

### RES-01 · 사람 수행

- 조치: 마스킹된 대기 그래프와 업무 영향으로 blocker·waiter 관계를 확인하고, 정상 종료·커밋·롤백을 우선 조정한다.
- 승인 필요: 예
- 확인: 차단 관계가 해소되고 대기 요청이 완료되거나 안전하게 실패 처리된다.
- 롤백: 해당 없음
- 근거: PE-015, PE-017, PE-018

### RES-02 · 사람 수행

- 조치: 업무 중단이 크고 정상 종료가 불가능한 경우에만 소유자·영향 범위를 확인해 세션 종료를 승인받는다. Agent는 이 작업을 실행하지 않는다.
- 승인 필요: 예
- 확인: 종료 대상 외 정상 세션에 영향이 없고 대기 관계가 해소된다.
- 롤백: 종료된 트랜잭션은 되돌릴 수 없으므로 실행 전 복구·재처리 계획을 확정한다.
- 근거: PE-015, PE-017

### RES-03 · 사람 수행

- 조치: 반복 lock wait는 트랜잭션 범위를 줄이고, 반복 deadlock은 자원 잠금 순서를 일관되게 수정하거나 안전한 재시도 정책을 코드 리뷰 후 적용한다.
- 승인 필요: 예
- 확인: 동시 요청 테스트에서 장기 대기와 deadlock이 재발하지 않고 데이터 정합성이 유지된다.
- 롤백: 회귀나 정합성 오류가 발생하면 변경을 되돌리고 티켓을 재개한다.
- 근거: PE-018, PE-019

### 완료 판정

성공 신호:
- blocker·waiter 관계가 해소된다.
- 동일 동시성 시나리오에서 lock wait 또는 deadlock이 재발하지 않는다.
- 사용자 요청과 데이터 정합성이 정상임을 확인한다.

실패 신호:
- 같은 blocker 또는 대기 패턴이 지속된다.
- deadlock detected 오류가 반복된다.
- 재시도 후 중복 처리나 데이터 정합성 문제가 발생한다.

### 이관

- 대상: DB
- 조건:
- 지속되는 lock wait 또는 deadlock 확인
- 차단 세션을 안전하게 해소할 수 없음
- 세션 종료·트랜잭션 또는 코드 변경이 필요함
- 첨부 근거:
- 발생 시각·X-Request-Id·API 경로 템플릿
- 마스킹된 waiter·blocker 관계
- wait_event_type·lock granted·트랜잭션 상태
- deadlock 또는 lock wait 로그 지문
- 재시도 안전성 여부

---

## 사용자 안내 정책

## 안전한 사용자 안내

사내 포털의 데이터 처리 대기 문제를 확인하여 DB 담당 부서에서 점검 중입니다. 중복 저장을 피하고 요청 추적 번호 {request_id}를 보관해 주세요.

## 노출 금지

- PID·사용자명·클라이언트 IP
- SQL 원문·바인딩 값
- 잠금 대상 데이터 값
- 세션 종료 명령
- 근거 없이 특정 사용자를 blocker로 지목하는 표현
