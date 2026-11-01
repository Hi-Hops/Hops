---
runbook_id: RB-REMOTE-VPN-720
version_no: 1
status: DRAFT
access_scope: STAFF
exposure_policy: INTERNAL_ONLY
ingest_status: PENDING
content_review_status: OWNER_CONFIRMED
team_review_status: PENDING
tool_binding_status: PENDING
scenario_validation_status: NOT_RUN
source_content_sha256: 628f138fb8e0774ee4052686e67960584cd7ec9b25d88c26fdd5e8d19314f03e
---

> **PRD 1.44 전환 전 자료:** 이 문서는 기존 v0 데이터의 자동 생성본이다. 파일명의 `.v1`은 해당 문서의 버전이며 새 기획용 데이터셋 v1을 뜻하지 않는다. 부서 배정·질문 한도·해결 정책 등은 현재 기획과 다를 수 있으므로 새 기획의 검색 정본으로 사용하지 않는다. [새 정책](../policies/POLICY-PRD-1.44.md) · [자료 전환 검토](../DATA_MIGRATION_REVIEW.md)

# VPN 오류 720 진단 및 대응

## 적용 범위와 사용자 증상

- 서비스/컴포넌트: REMOTE_ACCESS / VPN
- 기본 담당: NETWORK
- 적용 환경: HOPS_VIRTUAL_COMPANY
- 제품: Windows 11, Windows Server RRAS 지원 버전
- 문서 상태/인덱싱: DRAFT / PENDING

## 적용 가정

- 가상 기업 직원은 회사가 배포한 Windows VPN 프로필을 사용한다.
- 오류 720은 RasClient 이벤트 20227과 함께 확인한다.
- 어댑터 binding 조회는 읽기 전용이며 변경·제거·재설치는 사람이 승인 후 수행한다.
- Agent는 관리자 PowerShell, 장치 제거 또는 서버 주소 풀 변경을 실행하지 않는다.

## 범위 밖

- 오류 809·812 또는 인증서 오류
- PPTP·GRE 기반 레거시 VPN
- Agent가 WAN Miniport 재설치·binding 변경·DHCP 또는 RRAS 설정을 수행하는 작업

## 사용자 증상

### SYM-01

사용자 표현:
- VPN 연결에서 오류 720이 떠요.
- 원격 컴퓨터와 연결할 수 없고 네트워크 설정을 바꾸라고 나와요.

관측 신호:
- RasClient 이벤트 ID 20227에 오류 720이 기록된다.
- WAN Miniport (IP) binding 또는 필터 드라이버 상태 확인이 필요하다.

### SYM-02

사용자 표현:
- 여러 사람의 VPN이 동시에 720 오류로 실패해요.
- VPN 연결은 시작되지만 IP를 받지 못하는 것 같아요.

관측 신호:
- 서버의 정적 IP pool 또는 RRAS용 DHCP scope 소진 후보가 있다.
- 여러 클라이언트에서 같은 시간대 오류 720이 발생한다.

---

## 진단 1: 요청·로그 연결

### DIA-01 · 오류 720과 RasClient 이벤트를 확인한다.

- 조치: 발생 시각과 VPN 프로필 이름을 확인하고 Application 로그의 RasClient 이벤트 20227에서 오류 720인지 읽는다.
- 도구: 미등록 — 실행 불가
- 기대 신호:
- 사용자 발생 시각과 RasClient 20227이 일치한다.
- 반환 오류 코드가 720이다.
- 일치 시: DIA-02에서 단일 단말과 공통 서버 장애를 구분한다.
- 불일치 시: 다른 VPN 오류 코드 런북으로 전환한다.
- 근거: GE-012

### DIA-02 · 클라이언트 binding 문제와 서버 IP 할당 문제를 구분한다.

- 조치: 같은 시간대 영향 사용자 수와 서버 IP pool·DHCP 가용성의 읽기 결과를 확인한다. 한 단말만 실패하면 WAN Miniport (IP)의 이름과 binding 상태를 조회한다.
- 도구: 미등록 — 실행 불가
- 기대 신호:
- 여러 사용자의 공통 실패와 서버 주소 고갈이 함께 나타나거나, 단일 단말에서만 binding 이상이 나타난다.
- 서버와 클라이언트 후보를 구분할 수 있다.
- 일치 시: 서버 문제면 CAUSE-01, 클라이언트 문제면 DIA-03으로 이동한다.
- 불일치 시: 필수 관측값이 없으면 원인을 단정하지 않고 NETWORK로 이관한다.
- 근거: GE-012

---

## 진단 2: 계층 구분과 근거 충분성

### DIA-03 · WAN Miniport의 ARP binding과 제3자 필터를 구분한다.

- 조치: Get-NetAdapter와 Get-NetAdapterBinding의 읽기 결과에서 WAN Miniport (IP), ms_wanarp 활성 상태, 제3자 필터 binding을 확인한다. Agent는 Enable·Disable 명령을 실행하지 않는다.
- 도구: 미등록 — 실행 불가
- 기대 신호:
- ms_wanarp가 비활성화되어 있거나 의심되는 제3자 필터가 binding되어 있다.
- 둘 다 정상이면 Miniport 드라이버 손상 후보가 남는다.
- 일치 시: CAUSE-02 또는 CAUSE-03 후보와 필요한 사람 조치를 기록한다.
- 불일치 시: binding이 모두 정상이면 재설치 필요성을 사람이 판단하도록 이관한다.
- 근거: GE-012

---

## 원인 후보와 반대 신호

### CAUSE-01 · RRAS 정적 IP pool, DHCP scope 또는 사용자 고정 IP를 할당할 수 없어 여러 연결이 실패한다.

- 필요 신호 ID: SIG-034, SIG-037, SIG-039
- 필요 신호:
- 여러 사용자에서 같은 시간대 오류 720
- 서버 주소 pool·DHCP scope 소진 또는 고정 IP 할당 실패
- 반대 신호 ID: SIG-038
- 반대 신호:
- 서버 주소에 충분한 여유가 있음
- 한 단말에서만 binding 이상이 확인됨
- 담당 부서: NETWORK
- 근거: GE-012

#### 레지스트리에서 결합된 신호 정의

- SIG-034 [필요] · RasClient Event ID 20227 (WINDOWS_EVENT)
  - 매칭: RasClient/20227
  - 의미: VPN 연결 실패 시각과 반환 오류 코드를 연결
  - 확정 제한: 이벤트 자체는 원인이 아니며 포함된 오류 코드와 범위를 추가 확인
- SIG-037 [필요] · VPN 오류 720 (ERROR_CODE)
  - 매칭: VPN_ERROR_720
  - 의미: PPP IP 프로토콜 구성 또는 주소 할당 실패 계열 오류
  - 확정 제한: 클라이언트 binding과 서버 주소 할당 문제를 추가 구분
- SIG-039 [필요] · RRAS 주소 풀·DHCP scope 소진 (CAPACITY)
  - 매칭: rras_ip_pool_exhausted|dhcp_scope_exhausted
  - 의미: VPN 연결에 할당할 IP 주소가 부족한 서버 측 상태
  - 확정 제한: 여러 사용자 동시 실패와 실제 할당 상태를 함께 확인
- SIG-038 [반대] · ms_wanarp binding 비활성 (ADAPTER_BINDING)
  - 매칭: ms_wanarp Enabled=false
  - 의미: WAN Miniport IP의 Remote Access IP ARP Driver가 비활성화된 상태
  - 확정 제한: 오류 720과 해당 단말 관측이 함께 있을 때만 원인 후보로 사용

### CAUSE-02 · WAN Miniport (IP)의 Remote Access IP ARP Driver(ms_wanarp)가 비활성화되어 PPP IP 프로토콜을 구성하지 못한다.

- 필요 신호 ID: SIG-034, SIG-037, SIG-038
- 필요 신호:
- 오류 720
- WAN Miniport (IP)에서 ms_wanarp Enabled=false
- 반대 신호 ID: SIG-039
- 반대 신호:
- ms_wanarp가 활성화됨
- 여러 사용자에게 서버 주소 고갈이 확인됨
- 담당 부서: SYSTEM
- 근거: GE-012

#### 레지스트리에서 결합된 신호 정의

- SIG-034 [필요] · RasClient Event ID 20227 (WINDOWS_EVENT)
  - 매칭: RasClient/20227
  - 의미: VPN 연결 실패 시각과 반환 오류 코드를 연결
  - 확정 제한: 이벤트 자체는 원인이 아니며 포함된 오류 코드와 범위를 추가 확인
- SIG-037 [필요] · VPN 오류 720 (ERROR_CODE)
  - 매칭: VPN_ERROR_720
  - 의미: PPP IP 프로토콜 구성 또는 주소 할당 실패 계열 오류
  - 확정 제한: 클라이언트 binding과 서버 주소 할당 문제를 추가 구분
- SIG-038 [필요] · ms_wanarp binding 비활성 (ADAPTER_BINDING)
  - 매칭: ms_wanarp Enabled=false
  - 의미: WAN Miniport IP의 Remote Access IP ARP Driver가 비활성화된 상태
  - 확정 제한: 오류 720과 해당 단말 관측이 함께 있을 때만 원인 후보로 사용
- SIG-039 [반대] · RRAS 주소 풀·DHCP scope 소진 (CAPACITY)
  - 매칭: rras_ip_pool_exhausted|dhcp_scope_exhausted
  - 의미: VPN 연결에 할당할 IP 주소가 부족한 서버 측 상태
  - 확정 제한: 여러 사용자 동시 실패와 실제 할당 상태를 함께 확인

### CAUSE-03 · 제3자 네트워크 필터 binding 또는 WAN Miniport 드라이버 손상이 클라이언트 프로토콜 구성을 방해한다.

- 필요 신호 ID: SIG-034, SIG-037, SIG-055
- 필요 신호:
- 오류 720
- 의심 필터 binding 또는 정상 binding에도 지속되는 Miniport 이상
- 반대 신호 ID: SIG-039
- 반대 신호:
- 다른 단말에서도 동시에 같은 오류 발생
- 서버 주소 할당 실패가 확인됨
- 담당 부서: SYSTEM
- 근거: GE-012

#### 레지스트리에서 결합된 신호 정의

- SIG-034 [필요] · RasClient Event ID 20227 (WINDOWS_EVENT)
  - 매칭: RasClient/20227
  - 의미: VPN 연결 실패 시각과 반환 오류 코드를 연결
  - 확정 제한: 이벤트 자체는 원인이 아니며 포함된 오류 코드와 범위를 추가 확인
- SIG-037 [필요] · VPN 오류 720 (ERROR_CODE)
  - 매칭: VPN_ERROR_720
  - 의미: PPP IP 프로토콜 구성 또는 주소 할당 실패 계열 오류
  - 확정 제한: 클라이언트 binding과 서버 주소 할당 문제를 추가 구분
- SIG-055 [필요] · VPN 필터 binding·WAN Miniport 이상 후보 (ADAPTER_BINDING)
  - 매칭: third_party_filter_binding|wan_miniport_fault_suspected
  - 의미: 단일 단말에서 의심되는 제3자 네트워크 필터 binding이 있거나 정상 binding에도 오류 720이 지속되는 상태
  - 확정 제한: 드라이버 손상을 직접 확정하지 않고 서버 주소 할당과 영향 범위를 먼저 확인하며 자동 제거·재설치는 수행하지 않음
- SIG-039 [반대] · RRAS 주소 풀·DHCP scope 소진 (CAPACITY)
  - 매칭: rras_ip_pool_exhausted|dhcp_scope_exhausted
  - 의미: VPN 연결에 할당할 IP 주소가 부족한 서버 측 상태
  - 확정 제한: 여러 사용자 동시 실패와 실제 할당 상태를 함께 확인

---

## Agent 사용자 안내와 처리 경계

### GUID-01 · Agent 안내

- 조건: 오류 720이 확인된 경우
- 안내: 사용자에게 오류 코드·발생 시각·VPN 프로필 이름을 기록하고 VPN을 한 번만 다시 연결하도록 안내한다. 관리자 PowerShell이나 장치 관리자 작업은 안내하지 않는다.
- 예상 결과: 일시 오류인지 지속 오류인지 확인하고 진단에 필요한 최소 정보를 확보한다.
- 미해결 시: 반복 시도를 중단하고 NETWORK로 이관한다.
- 근거: GE-012

### GUID-02 · Agent 안내

- 조건: binding·드라이버 또는 서버 주소 할당 점검이 필요한 경우
- 안내: 사용자에게 관리자 권한 점검이 필요하다고 안내하고, 임의 드라이버 삭제·보안 프로그램 해제·네트워크 설정 변경을 하지 않도록 알린다.
- 예상 결과: 사용자가 단말 상태를 유지한 채 담당자 원격 또는 현장 점검으로 이동한다.
- 미해결 시: NETWORK 티켓을 유지하고 필요 시 SYSTEM 협업을 요청한다.
- 근거: GE-012

### Agent 처리 경계

- 허용 결과: GUIDE_USER, HANDOFF
- 이관 사유: INSUFFICIENT_EVIDENCE, GUIDANCE_NOT_RESOLVED
- 금지 작업: CODE_CHANGE, CONFIG_CHANGE, DEPLOY, RESTART, DB_WRITE
- 해결 확정 근거: USER_CONFIRMATION, BACKEND_STATE

---

## 이관 후 IT 담당자 조치·검증

### RES-01 · 사람 수행

- 조치: 영향 범위와 서버 IP pool·DHCP 상태를 확인하고, 단일 단말 문제이면 현재 WAN Miniport binding 구성을 보존한다.
- 승인 필요: 아니오
- 확인: 서버 주소 할당 문제와 클라이언트 binding 문제 중 하나를 근거로 분류한다.
- 롤백: 해당 없음
- 근거: GE-012

### RES-02 · 사람 수행

- 조치: ms_wanarp 비활성 또는 승인된 제3자 필터 문제가 확인되면 변경 전 상태를 기록하고 담당자 승인 후 해당 binding만 수정한다.
- 승인 필요: 예
- 확인: binding 상태가 정책값과 일치하고 오류 720 없이 연결된다.
- 롤백: 새 네트워크 장애가 발생하면 기록한 이전 binding 상태로 복원한다.
- 근거: GE-012

### RES-03 · 사람 수행

- 조치: binding 수정으로 해결되지 않고 서버 상태가 정상일 때만 변경 승인을 받아 WAN Miniport 장치를 재설치하고 네트워크 구성 변화를 기록한다.
- 승인 필요: 예
- 확인: 재부팅 또는 장치 재검색 뒤 VPN 연결과 일반 네트워크가 모두 정상이다.
- 롤백: 네트워크 기능이 악화되면 승인된 단말 복구 절차로 이전 드라이버·시스템 상태를 복원한다.
- 근거: GE-012

### 완료 판정

성공 신호:
- 오류 720 없이 VPN 연결이 성공한다.
- 사내 자원 접근과 일반 네트워크가 정상이다.
- 사용자가 해결을 확인한다.

실패 신호:
- 오류 720이 재발한다.
- 서버 주소 고갈이 계속된다.
- binding·드라이버 조치 후 다른 네트워크 기능이 실패한다.

### 이관

- 대상: NETWORK
- 조건:
- 오류 720 재발
- 서버 IP pool·DHCP 확인 필요
- 관리자 binding 변경 또는 Miniport 재설치 필요
- 첨부 근거:
- 발생 시각·VPN 프로필·RasClient 20227
- 동시 영향 사용자 수
- 서버 주소 가용성 요약
- WAN Miniport binding 읽기 결과

---

## 사용자 안내 정책

## 안전한 사용자 안내

VPN 오류 720을 확인하여 네트워크 담당 부서로 이관했습니다. 임의로 네트워크 장치나 드라이버를 제거하지 마세요.

## 노출 금지

- 관리자 PowerShell 변경 명령
- 사용자·서버 IP 전체
- 제3자 필터 내부 구성
- 장치 제거를 직원이 직접 수행하라는 안내
