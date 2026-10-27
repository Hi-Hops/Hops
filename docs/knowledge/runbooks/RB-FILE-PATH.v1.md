---
runbook_id: RB-FILE-PATH
version_no: 1
status: DRAFT
access_scope: STAFF
exposure_policy: INTERNAL_ONLY
ingest_status: PENDING
content_review_status: OWNER_CONFIRMED
team_review_status: PENDING
tool_binding_status: PENDING
scenario_validation_status: NOT_RUN
source_content_sha256: 0b4d3374013156694feacd82c77e8a49cb2163581eebb43e7ad2261a7e2deec9
---

# SMB 공유 경로·TCP 445 연결 실패 진단 및 대응

## 적용 범위와 사용자 증상

- 서비스/컴포넌트: FILE_SHARE / SMB
- 기본 담당: NETWORK
- 적용 환경: HOPS_VIRTUAL_COMPANY
- 제품: Windows 11, Windows Server SMB 지원 버전
- 문서 상태/인덱싱: DRAFT / PENDING

## 적용 가정

- 가상 기업 공유 경로는 회사 DNS 이름과 TCP 445를 사용하는 SMB 2 이상으로 제공된다.
- 직원은 사내망 또는 승인된 VPN을 통해 공유 드라이브에 접근한다.
- DNS·포트·listener·서비스·방화벽 상태는 읽기 전용으로 조회한다.
- Agent는 방화벽 규칙·서비스·NetBIOS·IP·SMB 기능을 변경하지 않는다.

## 범위 밖

- TCP 연결 뒤 발생하는 공유·NTFS Access Denied
- Event ID 1020과 저장장치 I/O 지연
- SMB1 활성화 또는 insecure guest logon을 요구하는 장치
- Agent가 방화벽·서비스·네트워크 주소·레지스트리를 변경하는 작업

## 사용자 증상

### SYM-01

사용자 표현:
- 공유 드라이브에 빨간 X가 보이고 열리지 않아요.
- 시스템 오류 53 또는 네트워크 경로를 찾을 수 없다고 나와요.

관측 신호:
- 오류 53·0x80070035·0x80004005 중 하나가 기록될 수 있다.
- UNC 서버 이름의 DNS 또는 TCP 445 연결 확인이 필요하다.

### SYM-02

사용자 표현:
- 서버 이름은 맞는데 공유 폴더 연결이 시작되지 않아요.
- 여러 사람이 같은 파일 서버에 연결할 수 없어요.

관측 신호:
- SMB TCP three-way handshake에서 SYN-ACK가 없거나 SYN이 서버에 도착하지 않는다.
- 서버 TCP 445 listener·Server 서비스 또는 네트워크 경로 문제가 후보가 된다.

---

## 진단 1: 요청·로그 연결

### DIA-01 · 정확한 공유 경로와 네트워크 연결 전제조건을 확인한다.

- 조치: 발생 시각, 드라이브 문자, 마스킹된 UNC 서버·공유 이름, 사내망·VPN 연결 여부, 오류 코드를 확인한다. 사용자 비밀번호와 파일 내용은 수집하지 않는다.
- 도구: 미등록 — 실행 불가
- 기대 신호:
- 오류 코드와 대상 서버 이름이 확인된다.
- 사내망 또는 승인된 VPN 연결 상태가 확인된다.
- 일치 시: DIA-02에서 DNS와 TCP 445를 확인한다.
- 불일치 시: 경로와 시각을 확인할 수 없으면 한 번만 재현을 요청하고 이후 근거 부족으로 이관한다.
- 근거: GE-060, GE-023

### DIA-02 · DNS 해석과 TCP 445 도달성을 구분한다.

- 조치: 회사 DNS에서 서버 이름 해석 결과와 승인된 TCP 445 연결 시험을 읽는다. IP로 직접 연결하도록 안내하지 않고 결과 주소는 Agent 응답에서 마스킹한다.
- 도구: 미등록 — 실행 불가
- 기대 신호:
- DNS가 회사 서버 이름을 올바르게 해석하거나 실패한다.
- TCP 445 연결 성공 또는 handshake 실패가 구분된다.
- 일치 시: DNS 실패면 NETWORK 이관, TCP 실패면 DIA-03으로 이동한다.
- 불일치 시: DNS와 TCP가 정상이면 Access Denied 또는 SMB 세션 문제 런북으로 전환한다.
- 근거: GE-023, GE-060

---

## 진단 2: 계층 구분과 근거 충분성

### DIA-03 · SYN이 서버에 도착한 뒤 응답이 없는 경우와 서버에 도착하지 않는 경우를 구분한다.

- 조치: 담당자가 승인된 동시 network trace 또는 방화벽 로그에서 SYN 도착 여부를 확인하고, 서버의 TCP 445 listener와 Server 서비스 상태를 읽는다.
- 도구: 미등록 — 실행 불가
- 기대 신호:
- SYN 도착·SYN-ACK 부재 또는 SYN 미도착 중 하나가 확인된다.
- 서버 listener·서비스 또는 중간 네트워크 장비 후보가 구분된다.
- 일치 시: CAUSE-01 또는 CAUSE-02 후보와 담당 부서를 기록한다.
- 불일치 시: trace가 없으면 방화벽이나 서버 장애로 단정하지 않고 NETWORK로 이관한다.
- 근거: GE-023

---

## 원인 후보와 반대 신호

### CAUSE-01 · SYN이 SMB 서버에 도착하지만 TCP 445 listener·Server 서비스 또는 서버 로컬 방화벽이 SYN-ACK 응답을 막는다.

- 필요 신호 ID: SIG-044, SIG-045
- 필요 신호:
- 클라이언트 SYN이 서버에 도착함
- SYN-ACK가 없고 TCP 445 listener·Server 서비스·WFP drop 중 하나가 비정상
- 반대 신호 ID: 없음
- 반대 신호:
- SYN이 서버에 전혀 도착하지 않음
- TCP handshake가 정상 완료됨
- 담당 부서: SYSTEM
- 근거: GE-023

#### 레지스트리에서 결합된 신호 정의

- SIG-044 [필요] · SMB TCP 445 도달 실패 (NETWORK_REACHABILITY)
  - 매칭: tcp/445 unreachable
  - 의미: 클라이언트와 SMB 서버 사이 TCP 연결이 성립하지 않음
  - 확정 제한: 서버 listener와 중간 방화벽·라우팅을 패킷 관측으로 구분
- SIG-045 [필요] · SMB 서버 SYN 수신·SYN-ACK 부재 (PACKET_TRACE)
  - 매칭: syn_arrived+no_syn_ack
  - 의미: 요청은 서버에 도달했지만 서버 listener·서비스·로컬 방화벽 계층에서 응답하지 않는 상태
  - 확정 제한: 동시 trace 또는 서버 측 관측 없이 중간망과 서버를 구분하지 않음

### CAUSE-02 · 클라이언트와 서버 사이의 방화벽·라우팅·VPN·네트워크 장비가 TCP 445 SYN을 차단한다.

- 필요 신호 ID: SIG-044
- 필요 신호:
- 클라이언트에서 SYN 전송
- 동시 trace에서 서버에 SYN이 도착하지 않음
- 반대 신호 ID: SIG-045
- 반대 신호:
- SYN이 서버에 도착함
- TCP 445 handshake가 정상 완료됨
- 담당 부서: NETWORK
- 근거: GE-023

#### 레지스트리에서 결합된 신호 정의

- SIG-044 [필요] · SMB TCP 445 도달 실패 (NETWORK_REACHABILITY)
  - 매칭: tcp/445 unreachable
  - 의미: 클라이언트와 SMB 서버 사이 TCP 연결이 성립하지 않음
  - 확정 제한: 서버 listener와 중간 방화벽·라우팅을 패킷 관측으로 구분
- SIG-045 [반대] · SMB 서버 SYN 수신·SYN-ACK 부재 (PACKET_TRACE)
  - 매칭: syn_arrived+no_syn_ack
  - 의미: 요청은 서버에 도달했지만 서버 listener·서비스·로컬 방화벽 계층에서 응답하지 않는 상태
  - 확정 제한: 동시 trace 또는 서버 측 관측 없이 중간망과 서버를 구분하지 않음

### CAUSE-03 · 회사 DNS 해석 또는 클라이언트 네트워크 서비스 문제로 올바른 SMB 서버 경로를 찾지 못한다.

- 필요 신호 ID: SIG-042, SIG-043
- 필요 신호:
- 오류 53 또는 경로 미발견
- 회사 서버 이름 해석 실패나 클라이언트 네트워크 서비스 이상
- 반대 신호 ID: SIG-046
- 반대 신호:
- DNS와 TCP 445가 정상이며 Access Denied만 발생
- 다른 사용자도 동일 서버 listener 문제를 겪음
- 담당 부서: NETWORK
- 근거: GE-060

#### 레지스트리에서 결합된 신호 정의

- SIG-042 [필요] · 시스템 오류 53 (ERROR_CODE)
  - 매칭: ERROR_BAD_NETPATH|53
  - 의미: 네트워크 경로를 찾을 수 없는 SMB 경로 계열 오류
  - 확정 제한: DNS·TCP 445·VPN·서버 상태를 확인하기 전 원인을 확정하지 않음
- SIG-043 [필요] · UNC 서버 이름 해석 실패 (DNS)
  - 매칭: unc_hostname_resolution_failed
  - 의미: 공유 경로의 서버 이름을 회사 DNS에서 올바르게 해석하지 못함
  - 확정 제한: IP 직접 접속 성공만으로 장기 해결로 간주하지 않음
- SIG-046 [반대] · SMB Access Denied (ERROR_MESSAGE)
  - 매칭: Access Denied|ACCESS_DENIED
  - 의미: SMB 세션은 시도됐지만 인증·보안 정책·공유 또는 NTFS 권한에서 거부됨
  - 확정 제한: 경로 미발견과 구분하고 권한 완화를 바로 제안하지 않음

---

## Agent 사용자 안내와 처리 경계

### GUID-01 · Agent 안내

- 조건: 공유 드라이브 연결이 끊기거나 경로를 찾지 못하는 경우
- 안내: 사용자에게 사내망 또는 승인된 VPN 연결을 확인하고 회사에서 제공한 서버 이름의 공유 경로를 한 번만 다시 열도록 안내한다.
- 예상 결과: 일시 연결 문제이면 드라이브가 다시 열리고, 지속되면 같은 오류 코드가 재현된다.
- 미해결 시: 오류 코드·발생 시각·마스킹된 공유 경로를 첨부해 NETWORK로 이관한다.
- 근거: GE-060, GE-023

### GUID-02 · Agent 안내

- 조건: DNS·TCP 445·서버 서비스 점검이 필요한 경우
- 안내: 사용자에게 네트워크 또는 파일 서버 점검이 필요하다고 안내한다. SMB1·NetBIOS·고정 IP·방화벽·서비스 설정 변경은 안내하지 않는다.
- 예상 결과: 사용자가 단말 설정을 변경하지 않고 담당자 점검으로 이동한다.
- 미해결 시: 진단 근거에 따라 NETWORK 기본 이관 후 SYSTEM 협업을 요청한다.
- 근거: GE-023, GE-060

### Agent 처리 경계

- 허용 결과: GUIDE_USER, HANDOFF
- 이관 사유: INSUFFICIENT_EVIDENCE, GUIDANCE_NOT_RESOLVED
- 금지 작업: CODE_CHANGE, CONFIG_CHANGE, DEPLOY, RESTART, DB_WRITE
- 해결 확정 근거: USER_CONFIRMATION, BACKEND_STATE

---

## 이관 후 IT 담당자 조치·검증

### RES-01 · 사람 수행

- 조치: DNS 결과, TCP 445 시험, 서버 listener와 Server 서비스 상태를 같은 시각 기준으로 확인한다.
- 승인 필요: 아니오
- 확인: 장애 지점을 DNS·중간 경로·서버 listener·서비스 중 하나로 좁힌다.
- 롤백: 해당 없음
- 근거: GE-023, GE-060

### RES-02 · 사람 수행

- 조치: 필요하면 승인된 짧은 구간의 클라이언트·서버 동시 trace와 WFP·방화벽 로그를 수집해 SYN 손실 위치를 확인한다. 보안 프로그램 제거를 기본 조치로 사용하지 않는다.
- 승인 필요: 예
- 확인: SYN 도착 여부와 drop 지점이 로그 또는 trace로 확인된다.
- 롤백: trace가 성능 또는 개인정보 위험을 만들면 즉시 중단하고 캡처 파일을 보안 위치로 이동한다.
- 근거: GE-023

### RES-03 · 사람 수행

- 조치: 확정 원인에 따라 NETWORK는 DNS·라우팅·방화벽 경로를, SYSTEM은 서버 listener·Server 서비스를 승인된 변경으로 복구한다.
- 승인 필요: 예
- 확인: TCP three-way handshake와 SMB 공유 열기가 정상 완료된다.
- 롤백: 변경 후 다른 서비스 통신이나 SMB 접근이 악화되면 이전 설정으로 되돌린다.
- 근거: GE-023, GE-060

### 완료 판정

성공 신호:
- 회사 DNS 이름이 정상 해석된다.
- TCP 445 handshake와 SMB 공유 연결이 성공한다.
- 사용자가 공유 드라이브 접근을 확인한다.

실패 신호:
- 오류 53·0x80070035가 반복된다.
- SYN 또는 SYN-ACK가 계속 손실된다.
- 드라이브가 다시 연결되지 않는다.

### 이관

- 대상: NETWORK
- 조건:
- 직원 안전 재시도로 경로 오류가 해결되지 않음
- DNS 또는 TCP 445 실패
- 서버 listener·서비스 또는 네트워크 경로 변경 필요
- 첨부 근거:
- 발생 시각·오류 코드
- 마스킹된 UNC 서버·공유 이름
- DNS와 TCP 445 결과
- SYN 도착 여부·listener·Server 서비스 요약

---

## 사용자 안내 정책

## 안전한 사용자 안내

공유 드라이브의 네트워크 경로 연결 문제를 확인하여 네트워크 담당 부서로 이관했습니다. 임의로 SMB나 방화벽 설정을 변경하지 마세요.

## 노출 금지

- 서버 IP·방화벽 규칙 원문
- network trace의 사용자·파일 정보
- SMB1·NetBIOS·고정 IP 활성화 절차
- 보안 프로그램 제거 안내
