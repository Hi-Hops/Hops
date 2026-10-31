---
runbook_id: RB-PORTAL-HIKARI-POOL
version_no: 1
status: DRAFT
access_scope: STAFF
exposure_policy: INTERNAL_ONLY
ingest_status: PENDING
content_review_status: OWNER_CONFIRMED
team_review_status: PENDING
tool_binding_status: PENDING
scenario_validation_status: NOT_RUN
source_content_sha256: 24bf7183aa843749fde36816e9fa15e3c0beedfa177770560bf6d5674b1a5799
---

> **PRD 1.44 전환 전 자료:** 이 문서는 기존 v0 데이터의 자동 생성본이다. 파일명의 `.v1`은 해당 문서의 버전이며 새 기획용 데이터셋 v1을 뜻하지 않는다. 부서 배정·질문 한도·해결 정책 등은 현재 기획과 다를 수 있으므로 새 기획의 검색 정본으로 사용하지 않는다. [새 정책](../policies/POLICY-PRD-1.44.md) · [자료 전환 검토](../DATA_MIGRATION_REVIEW.md)

# 사내 포털 HikariCP 커넥션 풀 고갈 진단 및 대응

## 적용 범위와 사용자 증상

- 서비스/컴포넌트: PORTAL / PORTAL_API
- 기본 담당: BACKEND
- 적용 환경: DEMO_PORTAL
- 제품: Spring Boot 4.1.1, HikariCP 7.0.2, PostgreSQL 18
- 문서 상태/인덱싱: DRAFT / PENDING

## 적용 가정

- Demo Portal이 X-Request-Id를 구조화 로그에 기록한다.
- HikariCP active, idle, max, pending과 PostgreSQL numbackends를 권한이 적용된 읽기 전용 경로로 조회한다.
- 구체적인 MCP Tool ID는 아직 확정되지 않아 tool_query는 null이다.
- leakDetectionThreshold 메시지는 누수 가능성 신호일 뿐 단독 확정 근거가 아니다.

## 범위 밖

- PostgreSQL 서버 전체 연결 한도에 먼저 도달한 장애
- 애플리케이션 시작 단계에서 DB 연결에 실패한 장애
- Agent가 풀 설정 변경, 애플리케이션 재시작 또는 세션 종료를 수행하는 작업

## 사용자 증상

### SYM-01

사용자 표현:
- 포털이 한참 멈춘 뒤 오류가 나요.
- 강의 목록이 간헐적으로 늦게 뜨거나 실패해요.

관측 신호:
- 요청 처리 중 DB 연결 획득 timeout 로그가 발생한다.
- 동일 시간대에 여러 PORTAL API 요청의 응답 지연 또는 5xx가 증가한다.

### SYM-02

사용자 표현:
- 처음에는 되다가 사용자가 많아지면 포털이 안 돼요.
- 잠시 지나면 다시 되지만 같은 문제가 반복돼요.

관측 신호:
- HikariCP active가 max에 근접하고 idle은 0이며 pending이 증가한다.
- 부하가 줄거나 연결이 반환되면 요청 처리가 다시 시작된다.

---

## 진단 1: 요청·로그 연결

### DIA-01 · 사용자 요청과 커넥션 획득 timeout을 연결한다.

- 조치: X-Request-Id와 발생 시각으로 마스킹된 애플리케이션 로그를 조회해 HikariCP 연결 대기 timeout과 SQLException 여부를 확인한다.
- 도구: 미등록 — 실행 불가
- 기대 신호:
- 같은 요청 ID에서 connectionTimeout 경과 후 연결 획득 실패가 기록된다.
- 동일 시간대에 복수 요청이 같은 연결 획득 실패를 보인다.
- 일치 시: DIA-02에서 풀 포화 상태를 확인한다.
- 불일치 시: 일반 API 예외나 DB 서버 오류를 확인하고 이 런북으로 원인을 확정하지 않는다.
- 근거: PE-003, PE-009

### DIA-02 · 애플리케이션 커넥션 풀이 실제로 포화되었는지 확인한다.

- 조치: 같은 시간 구간의 jdbc.connections 및 hikaricp 읽기 전용 관측값에서 active, idle, max, pending을 비교한다.
- 도구: 미등록 — 실행 불가
- 기대 신호:
- active가 max와 같거나 지속적으로 근접한다.
- idle이 0이고 pending 또는 연결 대기 요청이 증가한다.
- 일치 시: DIA-03에서 점유 지속과 누수 보조 신호를 구분한다.
- 불일치 시: 풀 포화 근거가 없으면 단순 지연·API 예외·DB 계층 장애를 조사한다.
- 근거: PE-004A, PE-004B, PE-004C, PE-004D, PE-006, PE-007, PE-010

### DIA-03 · 장시간 연결 점유와 누수 의심 신호를 구분한다.

- 조치: 요청 지속시간, 풀 active 유지 시간, 연결 반환 이후 회복 여부와 leak detection 보조 로그를 비교한다. SQL 원문과 바인딩 값은 Agent에 전달하지 않는다.
- 도구: 미등록 — 실행 불가
- 기대 신호:
- 장시간 요청이 끝난 뒤 active와 pending이 감소하면 장기 점유 가능성이 높다.
- leak 메시지가 반복되어도 반환 여부와 코드 경로 근거가 없으면 누수로 확정하지 않는다.
- 일치 시: CAUSE-01 또는 CAUSE-02를 근거 수준에 맞게 후보로 기록한다.
- 불일치 시: 점유·반환 근거를 확보하지 못하면 불확실성을 명시한다.
- 근거: PE-003, PE-006, PE-012

---

## 진단 2: 계층 구분과 근거 충분성

### DIA-04 · 애플리케이션 풀 고갈과 DB 서버 연결 한도 초과를 구분한다.

- 조치: PostgreSQL numbackends와 DB 연결 거부 로그를 읽기 전용으로 확인해 서버 전체 한도 여유가 있는지 비교한다.
- 도구: 미등록 — 실행 불가
- 기대 신호:
- 애플리케이션 풀은 포화지만 PostgreSQL 서버 전체 연결 수에는 여유가 있다.
- reserved connection slot 오류가 없다.
- 일치 시: PORTAL_API/BACKEND 소유 풀 고갈 후보로 이관한다.
- 불일치 시: DB 전체 연결 한도 신호가 있으면 DB 연결 한도 런북으로 전환하고 담당 부서를 재판정한다.
- 근거: PE-008, PE-016

---

## 원인 후보와 반대 신호

### CAUSE-01 · 장시간 요청 또는 트랜잭션이 연결을 오래 점유해 풀의 사용 가능한 연결이 일시적으로 소진되었다.

- 필요 신호 ID: SIG-004, SIG-005, SIG-006, SIG-007, SIG-008
- 필요 신호:
- active=max, idle=0, pending 증가
- 장시간 요청 종료 또는 연결 반환 뒤 풀 상태 회복
- DB 서버 전체 연결 한도에는 여유
- 반대 신호 ID: SIG-010, SIG-011
- 반대 신호:
- 짧은 요청만 존재하는데 연결이 반환되지 않음
- PostgreSQL 서버 전체 연결 한도가 먼저 소진됨
- 담당 부서: BACKEND
- 근거: PE-006, PE-009, PE-010, PE-016

#### 레지스트리에서 결합된 신호 정의

- SIG-004 [필요] · Hikari 연결 획득 timeout (LOG_PATTERN)
  - 매칭: Connection is not available|connectionTimeout
  - 의미: 풀에서 제한시간 안에 연결을 얻지 못함
  - 확정 제한: DB 서버 한도와 풀 자체 포화를 추가 확인
- SIG-005 [필요] · Hikari active (METRIC)
  - 매칭: hikaricp.connections.active
  - 의미: 현재 사용 중인 풀 연결
  - 확정 제한: max·idle·pending과 함께 판단
- SIG-006 [필요] · Hikari idle (METRIC)
  - 매칭: hikaricp.connections.idle
  - 의미: 즉시 사용 가능한 풀 연결
  - 확정 제한: 0 하나만으로 고갈 확정 금지
- SIG-007 [필요] · Hikari max (METRIC)
  - 매칭: hikaricp.connections.max
  - 의미: 풀 최대 연결 수
  - 확정 제한: DB 서버 수용 한도와 함께 판단
- SIG-008 [필요] · Hikari pending (METRIC)
  - 매칭: hikaricp.connections.pending
  - 의미: 연결을 기다리는 요청 수
  - 확정 제한: active=max·idle=0과 함께 판단
- SIG-010 [반대] · max_connections (DB_SETTING)
  - 매칭: max_connections
  - 의미: PostgreSQL 최대 동시 연결 수
  - 확정 제한: 예약 슬롯과 실제 연결 수를 함께 확인
- SIG-011 [반대] · 예약 연결 슬롯 오류 (LOG_PATTERN)
  - 매칭: remaining connection slots are reserved
  - 의미: 일반 연결 슬롯이 소진됨
  - 확정 제한: 연결 점유 주체는 별도 확인

### CAUSE-02 · 애플리케이션 코드 경로에서 연결 반환이 지연되거나 누락되었을 가능성이 있다.

- 필요 신호 ID: SIG-004, SIG-005, SIG-009
- 필요 신호:
- 요청 종료 후에도 active가 감소하지 않음
- 동일 코드 경로에서 leak detection 보조 메시지가 반복됨
- DB 서버 연결 한도에는 여유
- 반대 신호 ID: SIG-010, SIG-011
- 반대 신호:
- 장시간 정상 쿼리 종료와 동시에 연결이 반환됨
- leak 메시지 한 건만 있고 반환 누락 근거가 없음
- 담당 부서: BACKEND
- 근거: PE-006, PE-012, PE-016

#### 레지스트리에서 결합된 신호 정의

- SIG-004 [필요] · Hikari 연결 획득 timeout (LOG_PATTERN)
  - 매칭: Connection is not available|connectionTimeout
  - 의미: 풀에서 제한시간 안에 연결을 얻지 못함
  - 확정 제한: DB 서버 한도와 풀 자체 포화를 추가 확인
- SIG-005 [필요] · Hikari active (METRIC)
  - 매칭: hikaricp.connections.active
  - 의미: 현재 사용 중인 풀 연결
  - 확정 제한: max·idle·pending과 함께 판단
- SIG-009 [필요] · Hikari leak detection 경고 (LOG_PATTERN)
  - 매칭: possible connection leak|leakDetectionThreshold
  - 의미: 설정된 임계시간보다 연결이 오래 풀 밖에 있어 누수 가능성 경고가 기록된 상태
  - 확정 제한: 실제 반환 여부·요청 지속시간·코드 경로를 함께 확인하며 단일 경고만으로 연결 누수를 확정하지 않음
- SIG-010 [반대] · max_connections (DB_SETTING)
  - 매칭: max_connections
  - 의미: PostgreSQL 최대 동시 연결 수
  - 확정 제한: 예약 슬롯과 실제 연결 수를 함께 확인
- SIG-011 [반대] · 예약 연결 슬롯 오류 (LOG_PATTERN)
  - 매칭: remaining connection slots are reserved
  - 의미: 일반 연결 슬롯이 소진됨
  - 확정 제한: 연결 점유 주체는 별도 확인

---

## Agent 사용자 안내와 처리 경계

### GUID-01 · Agent 안내

- 조건: 일시적인 연결 대기 오류이고 서비스가 회복된 경우
- 안내: 사용자에게 잠시 후 같은 동작을 한 번만 다시 시도하도록 안내하고, 재발하면 새 X-Request-Id와 시각을 알려 달라고 요청한다.
- 예상 결과: 일시 포화가 해소되었다면 요청이 정상 처리된다.
- 미해결 시: 재발 요청 근거를 첨부해 BACKEND로 이관한다.
- 근거: PE-003, PE-009

### GUID-02 · Agent 안내

- 조건: 풀 포화가 지속되거나 여러 사용자에게 반복되는 경우
- 안내: 사용자에게 서버 연결 처리 지연이 확인되어 담당 부서로 이관했다고 안내한다. 풀 크기·서버명·내부 로그는 노출하지 않는다.
- 예상 결과: 사용자가 장애 상태와 이관 사실을 이해한다.
- 미해결 시: 추가 사용자 조치를 반복하지 않고 BACKEND 티켓 상태를 안내한다.
- 근거: PE-006, PE-009, PE-010

### Agent 처리 경계

- 허용 결과: GUIDE_USER, HANDOFF
- 이관 사유: INSUFFICIENT_EVIDENCE, GUIDANCE_NOT_RESOLVED
- 금지 작업: CODE_CHANGE, CONFIG_CHANGE, DEPLOY, RESTART, DB_WRITE
- 해결 확정 근거: USER_CONFIRMATION, BACKEND_STATE

---

## 이관 후 IT 담당자 조치·검증

### RES-01 · 사람 수행

- 조치: 격리된 Demo Portal에서 장시간 연결 점유 또는 반환 지연 시나리오를 재현하고 요청 ID, 풀 관측값, DB 연결 수를 함께 보존한다.
- 승인 필요: 아니오
- 확인: active=max, idle=0, pending 증가와 DB 한도 여유가 같은 시간대에 재현된다.
- 롤백: 해당 없음
- 근거: PE-006, PE-009, PE-010, PE-016

### RES-02 · 사람 수행

- 조치: 확정 원인에 따라 트랜잭션·연결 반환 코드 또는 장시간 처리 경로를 수정하고 자동 테스트와 코드 리뷰를 수행한다. 근거 없이 maximumPoolSize만 늘리지 않는다.
- 승인 필요: 예
- 확인: 같은 부하에서 연결이 정상 반환되고 pending과 timeout이 재발하지 않는다.
- 롤백: 회귀 또는 DB 연결 급증이 발생하면 변경을 되돌리고 미해결 티켓을 재개한다.
- 근거: PE-009, PE-010, PE-012

### RES-03 · 사람 수행

- 조치: 풀 크기 변경이 필요하면 DB 수용 한도와 동시 인스턴스 수를 함께 계산해 승인 후 적용한다.
- 승인 필요: 예
- 확인: 애플리케이션 전체 최대 연결 합이 DB 허용 범위 안이고 목표 부하에서 timeout이 발생하지 않는다.
- 롤백: DB 연결 압박 또는 다른 서비스 영향이 발생하면 승인된 이전 설정으로 되돌린다.
- 근거: PE-008, PE-010, PE-016

### 완료 판정

성공 신호:
- 같은 부하에서 연결 획득 timeout이 재발하지 않는다.
- active·idle·pending이 정상 범위로 회복된다.
- 사용자 또는 백엔드 상태가 요청 정상 처리를 확인한다.

실패 신호:
- active=max와 pending 증가가 지속된다.
- DB 서버 전체 연결 한도 오류가 확인된다.
- 사용자 재시도 후에도 같은 timeout이 발생한다.

### 이관

- 대상: BACKEND
- 조건:
- 풀 포화 근거가 있으나 연결 점유 경로를 특정하지 못함
- 안내 후 같은 timeout이 재발함
- 코드·풀 설정 변경이 필요함
- 첨부 근거:
- 발생 시각·X-Request-Id·API 경로 템플릿
- 마스킹된 timeout 로그
- active·idle·max·pending 스냅샷
- PostgreSQL numbackends와 DB 한도 오류 유무

---

## 사용자 안내 정책

## 안전한 사용자 안내

사내 포털의 서버 연결 처리 지연을 확인하여 담당 부서에서 점검 중입니다. 요청 추적 번호는 {request_id}입니다.

## 노출 금지

- DB 주소·계정·비밀번호
- 풀 설정 전체와 서버명
- SQL 원문·바인딩 값
- 사용자 ID·내부 IP
- 누수로 확정되지 않은 상태에서 연결 누수라고 단정하는 표현
