---
runbook_id: RB-FILE-SMB-ACCESS
version_no: 1
status: DRAFT
access_scope: STAFF
exposure_policy: INTERNAL_ONLY
ingest_status: PENDING
content_review_status: OWNER_CONFIRMED
team_review_status: PENDING
tool_binding_status: PENDING
scenario_validation_status: NOT_RUN
source_content_sha256: 9120348ba44a3501488d67659e2b9534a7d11ed18bb7165b8eba1ef7fc4506bb
---

# SMB 공유 폴더 액세스 거부 진단 및 대응

## 적용 범위와 사용자 증상

- 서비스/컴포넌트: FILE_SHARE / SMB
- 기본 담당: SYSTEM
- 적용 환경: HOPS_VIRTUAL_COMPANY
- 제품: Windows 11, Windows Server SMB 지원 버전
- 문서 상태/인덱싱: DRAFT / PENDING

## 적용 가정

- 가상 기업 공유 드라이브는 인증된 SMB 2 이상과 최소 권한을 사용한다.
- Windows 11 24H2의 SMB signing·guest·NTLM 보안 기본값을 약화하지 않는다.
- 공유 권한과 NTFS 권한 변경은 시스템 담당자가 사람 승인 후 수행한다.
- Agent는 Everyone 전체 제어, insecure guest logon, SMB1 활성화, 레지스트리 편집을 제안하거나 실행하지 않는다.

## 범위 밖

- TCP 445 도달성 또는 DNS 실패가 주원인인 경로 오류
- 파일 서버 Event ID 1020과 저장장치 I/O 지연
- WebDAV·SharePoint·타사 클라우드 파일 공유
- Agent가 권한·정책·레지스트리·SMB 기능을 변경하는 작업

## 사용자 증상

### SYM-01

사용자 표현:
- 공유 폴더에 액세스할 권한이 없다고 나와요.
- 네트워크 드라이브에서 시스템 오류 5가 보여요.

관측 신호:
- Access is denied, System error 5 또는 STATUS_ACCESS_DENIED가 기록된다.
- 특정 사용자·그룹 또는 특정 폴더에서만 실패할 수 있다.

### SYM-02

사용자 표현:
- 업데이트 후 NAS 공유 폴더가 막혔어요.
- 보안 정책이 인증되지 않은 게스트 접근을 차단한다고 나와요.

관측 신호:
- SMBClient Event ID 31017 또는 unauthenticated guest access 차단 문구가 있다.
- Windows 11 24H2 이후 SMB signing·NTLM 정책과 대상 장치 호환성 후보가 있다.

---

## 진단 1: 요청·로그 연결

### DIA-01 · 경로 미발견과 인증·권한 거부를 구분한다.

- 조치: 정확한 오류 문구·코드, UNC 경로의 서버 별칭, 발생 시각을 확인한다. TCP 445·DNS 실패면 RB-FILE-PATH로 전환하고 자격 증명 원문은 수집하지 않는다.
- 도구: 미등록 — 실행 불가
- 기대 신호:
- SMB 세션은 시도되지만 Access Denied 또는 보안 정책 거부가 발생한다.
- 오류 53·TCP handshake 실패가 주 신호가 아니다.
- 일치 시: DIA-02에서 보안 기본값과 권한 문제를 구분한다.
- 불일치 시: 경로·포트 오류이면 RB-FILE-PATH로 전환한다.
- 근거: GE-022, GE-060

### DIA-02 · guest·signing·NTLM 정책과 공유·NTFS 권한을 구분한다.

- 조치: SMBClient Connectivity·Security 이벤트, Get-SmbConnection, Get-SmbClientConfiguration의 읽기 결과와 대상 서버 유형을 확인한다. 설정은 변경하지 않는다.
- 도구: 미등록 — 실행 불가
- 기대 신호:
- guest 차단, signing 미지원, NTLM 제한 또는 일반 Access Denied 중 하나가 관측된다.
- 오류와 클라이언트 보안 상태가 일치한다.
- 일치 시: 보안 정책 계열이면 CAUSE-01 또는 CAUSE-02, 일반 권한이면 DIA-03으로 이동한다.
- 불일치 시: 단일 신호로 구분할 수 없으면 정책을 완화하지 않고 SYSTEM으로 이관한다.
- 근거: GE-022

---

## 진단 2: 계층 구분과 근거 충분성

### DIA-03 · 공유 권한과 NTFS 권한 불일치 범위를 확인한다.

- 조치: 같은 공유에 대한 다른 사용자 결과, 대상 사용자의 승인된 그룹, 공유 권한과 NTFS 유효 권한을 읽기 전용으로 비교한다. 다른 사용자의 세부 권한은 Agent 응답에 노출하지 않는다.
- 도구: 미등록 — 실행 불가
- 기대 신호:
- 특정 사용자·그룹에서만 실패한다.
- 공유 권한 또는 NTFS 권한 중 하나가 업무상 필요한 최소 접근을 허용하지 않는다.
- 일치 시: CAUSE-03 후보와 최소 권한 변경 필요성을 기록한다.
- 불일치 시: 권한이 정상인데 실패하면 인증 방식·경로 문제를 다시 확인한다.
- 근거: GE-022, GE-060

---

## 원인 후보와 반대 신호

### CAUSE-01 · 대상 NAS·서버가 guest 접근에 의존하지만 Windows가 insecure guest logon을 차단한다.

- 필요 신호 ID: SIG-046, SIG-047
- 필요 신호:
- unauthenticated guest access 차단 문구 또는 Event ID 31017
- 대상 장치가 인증된 사용자 접근을 제공하지 않거나 guest 연결을 요구함
- 반대 신호 ID: SIG-049
- 반대 신호:
- 유효한 회사 자격 증명으로 인증됨
- 일반 공유·NTFS 권한 거부가 확인됨
- 담당 부서: SYSTEM
- 근거: GE-022

#### 레지스트리에서 결합된 신호 정의

- SIG-046 [필요] · SMB Access Denied (ERROR_MESSAGE)
  - 매칭: Access Denied|ACCESS_DENIED
  - 의미: SMB 세션은 시도됐지만 인증·보안 정책·공유 또는 NTFS 권한에서 거부됨
  - 확정 제한: 경로 미발견과 구분하고 권한 완화를 바로 제안하지 않음
- SIG-047 [필요] · Insecure guest logon 차단 (WINDOWS_EVENT)
  - 매칭: SMBClient/31017|unauthenticated guest access blocked
  - 의미: 대상 장치가 guest 접근에 의존하지만 Windows가 비인증 guest 연결을 차단
  - 확정 제한: 안전하지 않은 guest 허용을 해결책으로 제시하지 않고 인증된 접근으로 이관
- SIG-049 [반대] · 특정 사용자·그룹만 SMB 거부 (IMPACT_SCOPE)
  - 매칭: single_user_or_group_denied
  - 의미: 공유·NTFS 유효 권한 또는 Access-Based Enumeration 불일치 가능성
  - 확정 제한: 다른 사용자 결과와 최소 필요 권한을 확인하기 전 권한 원인을 확정하지 않음

### CAUSE-02 · 대상 장치가 Windows 11의 SMB signing 또는 NTLM 보안 요구를 충족하지 못한다.

- 필요 신호 ID: SIG-046, SIG-048
- 필요 신호:
- OS 업그레이드 후 시작된 Access Denied
- signing 미지원 또는 NTLM 차단 관련 SMBClient 신호
- 반대 신호 ID: SIG-049
- 반대 신호:
- 대상 장치가 signing과 Kerberos를 정상 지원함
- 특정 사용자 권한만 누락됨
- 담당 부서: SYSTEM
- 근거: GE-022

#### 레지스트리에서 결합된 신호 정의

- SIG-046 [필요] · SMB Access Denied (ERROR_MESSAGE)
  - 매칭: Access Denied|ACCESS_DENIED
  - 의미: SMB 세션은 시도됐지만 인증·보안 정책·공유 또는 NTFS 권한에서 거부됨
  - 확정 제한: 경로 미발견과 구분하고 권한 완화를 바로 제안하지 않음
- SIG-048 [필요] · SMB signing·NTLM 정책 거부 (SECURITY_POLICY)
  - 매칭: smb_signing_unsupported|ntlm_blocked
  - 의미: 대상 장치가 Windows의 SMB signing 또는 NTLM 보안 요구를 충족하지 못함
  - 확정 제한: 보안 정책 해제를 제안하지 않고 장치 지원 상태와 조직 정책을 확인
- SIG-049 [반대] · 특정 사용자·그룹만 SMB 거부 (IMPACT_SCOPE)
  - 매칭: single_user_or_group_denied
  - 의미: 공유·NTFS 유효 권한 또는 Access-Based Enumeration 불일치 가능성
  - 확정 제한: 다른 사용자 결과와 최소 필요 권한을 확인하기 전 권한 원인을 확정하지 않음

### CAUSE-03 · 공유 권한과 NTFS 권한 또는 Access-Based Enumeration이 사용자의 업무상 필요한 접근과 일치하지 않는다.

- 필요 신호 ID: SIG-046, SIG-049
- 필요 신호:
- 특정 사용자·그룹만 거부됨
- 공유 또는 NTFS 유효 권한에 필요한 권한이 없음
- 반대 신호 ID: SIG-047, SIG-048
- 반대 신호:
- 모든 사용자에게 경로 미발견이 발생함
- guest·signing·NTLM 정책 오류가 직접 확인됨
- 담당 부서: SYSTEM
- 근거: GE-022, GE-060

#### 레지스트리에서 결합된 신호 정의

- SIG-046 [필요] · SMB Access Denied (ERROR_MESSAGE)
  - 매칭: Access Denied|ACCESS_DENIED
  - 의미: SMB 세션은 시도됐지만 인증·보안 정책·공유 또는 NTFS 권한에서 거부됨
  - 확정 제한: 경로 미발견과 구분하고 권한 완화를 바로 제안하지 않음
- SIG-049 [필요] · 특정 사용자·그룹만 SMB 거부 (IMPACT_SCOPE)
  - 매칭: single_user_or_group_denied
  - 의미: 공유·NTFS 유효 권한 또는 Access-Based Enumeration 불일치 가능성
  - 확정 제한: 다른 사용자 결과와 최소 필요 권한을 확인하기 전 권한 원인을 확정하지 않음
- SIG-047 [반대] · Insecure guest logon 차단 (WINDOWS_EVENT)
  - 매칭: SMBClient/31017|unauthenticated guest access blocked
  - 의미: 대상 장치가 guest 접근에 의존하지만 Windows가 비인증 guest 연결을 차단
  - 확정 제한: 안전하지 않은 guest 허용을 해결책으로 제시하지 않고 인증된 접근으로 이관
- SIG-048 [반대] · SMB signing·NTLM 정책 거부 (SECURITY_POLICY)
  - 매칭: smb_signing_unsupported|ntlm_blocked
  - 의미: 대상 장치가 Windows의 SMB signing 또는 NTLM 보안 요구를 충족하지 못함
  - 확정 제한: 보안 정책 해제를 제안하지 않고 장치 지원 상태와 조직 정책을 확인

---

## Agent 사용자 안내와 처리 경계

### GUID-01 · Agent 안내

- 조건: 오류 문구와 대상 공유 경로를 확인할 수 있는 경우
- 안내: 사용자에게 사내망 또는 승인된 VPN 연결을 확인하고 서버의 회사 FQDN으로 한 번만 재접속하도록 안내한다. 암호·자격 증명 원문은 받지 않는다.
- 예상 결과: 정상 회사 인증 경로로 재시도하거나 동일한 Access Denied를 재현한다.
- 미해결 시: 정확한 오류 문구와 공유 경로를 첨부해 SYSTEM으로 이관한다.
- 근거: GE-022

### GUID-02 · Agent 안내

- 조건: guest 차단·signing·NTLM 또는 권한 변경이 필요한 경우
- 안내: 사용자에게 보안 정책 또는 접근 권한 점검이 필요하다고 안내한다. SMB1, insecure guest logon, Everyone 전체 제어, 레지스트리 변경을 안내하지 않는다.
- 예상 결과: 사용자가 보안 설정을 약화하지 않고 담당자 점검으로 이동한다.
- 미해결 시: 담당자 변경과 재시험 전까지 티켓을 유지한다.
- 근거: GE-022, GE-060

### Agent 처리 경계

- 허용 결과: GUIDE_USER, HANDOFF
- 이관 사유: INSUFFICIENT_EVIDENCE, GUIDANCE_NOT_RESOLVED
- 금지 작업: CODE_CHANGE, CONFIG_CHANGE, DEPLOY, RESTART, DB_WRITE
- 해결 확정 근거: USER_CONFIRMATION, BACKEND_STATE

---

## 이관 후 IT 담당자 조치·검증

### RES-01 · 사람 수행

- 조치: SMBClient 이벤트와 보안 구성을 수집하고, 대상 장치의 인증·signing·Kerberos 지원 및 공유·NTFS 유효 권한을 확인한다.
- 승인 필요: 아니오
- 확인: 보안 정책 호환성 문제와 사용자 권한 문제 중 하나를 근거로 분류한다.
- 롤백: 해당 없음
- 근거: GE-022, GE-060

### RES-02 · 사람 수행

- 조치: guest 의존 장치는 인증된 계정과 SMB signing을 지원하도록 서버·NAS를 우선 수정한다. 클라이언트 보안 요구 완화는 보안 승인 없는 기본 해결책으로 사용하지 않는다.
- 승인 필요: 예
- 확인: 회사 자격 증명과 보안 요구를 유지한 상태에서 공유 접근이 성공한다.
- 롤백: 변경 후 다른 클라이언트 접근이 실패하면 장치 변경을 되돌리고 보안 담당과 재검토한다.
- 근거: GE-022

### RES-03 · 사람 수행

- 조치: 권한 문제이면 업무상 필요한 사용자·그룹에 최소 공유·NTFS 권한만 부여하고 Everyone 전체 제어는 사용하지 않는다.
- 승인 필요: 예
- 확인: 대상 사용자는 필요한 폴더만 접근하고 불필요한 위치에는 접근할 수 없다.
- 롤백: 과도한 접근이 확인되면 신규 권한을 제거하고 이전 ACL로 복원한다.
- 근거: GE-022, GE-060

### 완료 판정

성공 신호:
- 승인된 회사 계정으로 공유 폴더에 접근한다.
- 필요한 최소 폴더 권한만 동작한다.
- 보안 기본값을 약화하지 않고 사용자가 해결을 확인한다.

실패 신호:
- Access Denied가 반복된다.
- 보안 요구를 끄거나 guest 접근을 허용해야만 연결된다.
- 변경 후 불필요한 폴더까지 접근된다.

### 이관

- 대상: SYSTEM
- 조건:
- Access Denied 또는 정책 차단 지속
- SMB signing·NTLM·guest 호환성 확인 필요
- 공유·NTFS 권한 변경 필요
- 첨부 근거:
- 오류 코드·문구·발생 시각
- 마스킹된 UNC 서버·공유 이름
- SMBClient 이벤트와 보안 상태 요약
- 영향 사용자 범위와 유효 권한 요약

---

## 사용자 안내 정책

## 안전한 사용자 안내

공유 폴더의 인증 또는 접근 권한 확인이 필요하여 시스템 담당 부서로 이관했습니다. SMB1이나 게스트 접근을 임의로 활성화하지 마세요.

## 노출 금지

- 사용자 비밀번호·자격 증명
- 다른 사용자의 그룹·ACL 세부정보
- Everyone 전체 제어 절차
- insecure guest logon·SMB1·레지스트리 활성화 절차
