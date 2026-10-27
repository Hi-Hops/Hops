# 오류·관측 신호 사전 v0

JSON 정본은 `datasets/knowledge/v0/catalogs/error_signals.json`이다. 이 문서는 팀 검토용 자동 생성본이므로 직접 수정하지 않는다.

- 전체 55건: PORTAL 27건, IDENTITY·VPN·FILE_SHARE 28건
- 상태: 데이터 담당자 확정·팀 리뷰 대기
- RAG 적재: 금지. Knowledge Agent가 런북의 signal ID를 해석할 때 조립하는 개발용 정본이다.
- 각 신호는 원인 정답이 아니라 후보를 좁히는 관측값이다. `확정 제한`을 항상 함께 적용한다.

| ID | 이름 | 종류 | 매칭 값 | 의미 | 확정 제한 | 후보 컴포넌트 | 근거 |
|---|---|---|---|---|---|---|---|
| SIG-001 | HTTP 500 | HTTP_STATUS | 500 | 서버 내부 처리 실패 | 단독으로 API·DB 원인을 확정하지 않음 | PORTAL_API, PORTAL_DB | RB-PORTAL-API-500 |
| SIG-002 | X-Request-Id | CORRELATION_ID | X-Request-Id | 응답과 로그의 동일 요청 연결 | 오류 원인이 아니라 추적 식별자 | PORTAL_API | RB-PORTAL-API-500 |
| SIG-003 | 애플리케이션 예외 지문 | LOG_FIELD | exception_class+stack_fingerprint | 반복되는 코드 예외 경로를 구분 | 같은 지문은 같은 예외 경로의 반복을 뜻할 뿐 근본 원인을 확정하지 않으며 전체 스택 원문은 사용자에게 노출하지 않음 | PORTAL_API | RB-PORTAL-API-500 |
| SIG-004 | Hikari 연결 획득 timeout | LOG_PATTERN | Connection is not available\|connectionTimeout | 풀에서 제한시간 안에 연결을 얻지 못함 | DB 서버 한도와 풀 자체 포화를 추가 확인 | PORTAL_API, PORTAL_DB | RB-PORTAL-HIKARI-POOL, RB-PORTAL-DB-CONNECTION-LIMIT |
| SIG-005 | Hikari active | METRIC | hikaricp.connections.active | 현재 사용 중인 풀 연결 | max·idle·pending과 함께 판단 | PORTAL_API | RB-PORTAL-HIKARI-POOL |
| SIG-006 | Hikari idle | METRIC | hikaricp.connections.idle | 즉시 사용 가능한 풀 연결 | 0 하나만으로 고갈 확정 금지 | PORTAL_API | RB-PORTAL-HIKARI-POOL |
| SIG-007 | Hikari max | METRIC | hikaricp.connections.max | 풀 최대 연결 수 | DB 서버 수용 한도와 함께 판단 | PORTAL_API | RB-PORTAL-HIKARI-POOL |
| SIG-008 | Hikari pending | METRIC | hikaricp.connections.pending | 연결을 기다리는 요청 수 | active=max·idle=0과 함께 판단 | PORTAL_API | RB-PORTAL-HIKARI-POOL |
| SIG-009 | Hikari leak detection 경고 | LOG_PATTERN | possible connection leak\|leakDetectionThreshold | 설정된 임계시간보다 연결이 오래 풀 밖에 있어 누수 가능성 경고가 기록된 상태 | 실제 반환 여부·요청 지속시간·코드 경로를 함께 확인하며 단일 경고만으로 연결 누수를 확정하지 않음 | PORTAL_API | RB-PORTAL-HIKARI-POOL |
| SIG-010 | max_connections | DB_SETTING | max_connections | PostgreSQL 최대 동시 연결 수 | 예약 슬롯과 실제 연결 수를 함께 확인 | PORTAL_DB | RB-PORTAL-DB-CONNECTION-LIMIT |
| SIG-011 | 예약 연결 슬롯 오류 | LOG_PATTERN | remaining connection slots are reserved | 일반 연결 슬롯이 소진됨 | 연결 점유 주체는 별도 확인 | PORTAL_DB | RB-PORTAL-DB-CONNECTION-LIMIT |
| SIG-012 | numbackends | DB_STAT | pg_stat_database.numbackends | DB별 현재 연결 수 | 클러스터 전체·예약 슬롯과 함께 해석 | PORTAL_DB | RB-PORTAL-DB-CONNECTION-LIMIT |
| SIG-013 | application_name별 세션 수 | DB_STAT | pg_stat_activity.application_name | 연결 점유 서비스를 분류 | 사용자명·IP·SQL은 마스킹 | PORTAL_DB | RB-PORTAL-DB-CONNECTION-LIMIT |
| SIG-014 | idle in transaction | DB_STATE | idle in transaction | 열린 트랜잭션이 작업 없이 유지됨 | 잠금 보유 여부를 별도 확인 | PORTAL_DB | RB-PORTAL-DB-CONNECTION-LIMIT, RB-PORTAL-DB-LOCK |
| SIG-015 | Lock wait | DB_WAIT | wait_event_type=Lock | 잠금 해제를 기다리는 세션 | blocker·waiter 관계 확인 필요 | PORTAL_DB | RB-PORTAL-DB-LOCK |
| SIG-016 | 미승인 lock | DB_LOCK | pg_locks.granted=false | 아직 획득하지 못한 대기 lock | 충돌 lock과 세션 관계를 함께 확인 | PORTAL_DB | RB-PORTAL-DB-LOCK |
| SIG-017 | deadlock detected | DB_ERROR | deadlock detected | PostgreSQL이 순환 대기를 감지하고 트랜잭션 하나를 중단함 | 재시도 안전성과 잠금 순서를 별도 확인 | PORTAL_DB | RB-PORTAL-DB-LOCK |
| SIG-018 | log_lock_waits | DB_LOG_SETTING | log_lock_waits | 일정 시간 이상 잠금 대기를 로그로 기록 | deadlock_timeout과 함께 해석 | PORTAL_DB | RB-PORTAL-DB-LOCK |
| SIG-019 | query_id | DB_QUERY_ID | query_id | 같은 형태의 쿼리 통계를 연결 | SQL 원문 대신 사용하는 식별자 | PORTAL_DB | RB-PORTAL-DB-SLOW-QUERY |
| SIG-020 | calls | DB_QUERY_STAT | pg_stat_statements.calls | query_id의 누적 실행 횟수 | 통계 초기화 시점을 함께 기록 | PORTAL_DB | RB-PORTAL-DB-SLOW-QUERY |
| SIG-021 | mean_exec_time | DB_QUERY_STAT | pg_stat_statements.mean_exec_time | query_id의 평균 실행시간 | 평균만으로 최대 지연을 숨기지 않음 | PORTAL_DB | RB-PORTAL-DB-SLOW-QUERY |
| SIG-022 | max_exec_time | DB_QUERY_STAT | pg_stat_statements.max_exec_time | query_id의 최대 실행시간 | 단발성 이상치와 반복성을 구분 | PORTAL_DB | RB-PORTAL-DB-SLOW-QUERY |
| SIG-023 | Seq Scan | QUERY_PLAN | Seq Scan | 테이블 순차 스캔 | 작은 테이블에서는 정상일 수 있음 | PORTAL_DB | RB-PORTAL-DB-SLOW-QUERY |
| SIG-024 | Index Scan | QUERY_PLAN | Index Scan\|Bitmap Index Scan | 인덱스를 사용한 조회 | 사용 자체가 항상 빠름을 의미하지 않음 | PORTAL_DB | RB-PORTAL-DB-SLOW-QUERY |
| SIG-025 | estimated vs actual rows | QUERY_PLAN | estimated_rows/actual_rows | 플래너 예상 행 수와 실제 행 수 차이 | EXPLAIN ANALYZE는 실제 실행과 승인 조건 필요 | PORTAL_DB | RB-PORTAL-DB-SLOW-QUERY |
| SIG-026 | 비밀번호 재설정 오류 문구 | USER_OBSERVATION | password_reset_error_message | 비밀번호 재설정 경로에서 표시된 실패 유형과 발생 단계를 구분 | 오류 문구 없이 계정 상태나 원인을 추정하지 않음 | PASSWORD_RESET | SCN-IDENTITY-PASSWORD-RESET |
| SIG-027 | 비밀번호 성공 후 MFA 실패 | AUTH_STAGE | password_verified+mfa_failed | 비밀번호 문제와 추가 인증 수단 문제를 분리 | 계정 차단·MFA 등록 상태는 별도 확인 | MFA | RB-IDENTITY-MFA-RECOVERY |
| SIG-028 | MFA 등록 한도 오류 | ERROR_MESSAGE | mfa_registration_limit | 새 인증 앱 또는 장치를 더 등록할 수 없는 상태 | 기존 등록 수단과 조직 정책을 확인하기 전 관리자 삭제를 제안하지 않음 | MFA | RB-IDENTITY-MFA-RECOVERY, SCN-IDENTITY-MFA-LIMIT |
| SIG-029 | 대체 인증 수단 상태 | USER_OBSERVATION | alternate_auth_method_available=true\|false | 직원이 사용할 수 있는 다른 인증 방법의 존재 여부와 실제 접근 가능 상태 | 사용 가능 상태만으로 본인 확인이나 계정 복구를 확정하지 않으며 비밀번호·인증 코드는 수집하지 않음 | MFA | RB-IDENTITY-MFA-RECOVERY, SCN-IDENTITY-MFA-RECOVERY |
| SIG-030 | MFA 인증 코드 미수신 | USER_OBSERVATION | mfa_code_not_received | 등록된 연락 수단으로 인증 코드가 도착하지 않는 증상 | 번호 변경·통신 지연·계정 차단 중 원인을 단독 확정하지 않음 | MFA | RB-IDENTITY-MFA-RECOVERY, SCN-IDENTITY-MFA-RECOVERY |
| SIG-031 | AADSTS50011 | ERROR_CODE | AADSTS50011 | 요청의 redirect URI가 애플리케이션 등록 값과 일치하지 않음 | 계정 문제로 분류하지 않고 영향 애플리케이션과 등록 설정을 확인 | SSO | SCN-IDENTITY-SSO-REDIRECT |
| SIG-032 | AADSTS50057 | ERROR_CODE | AADSTS50057 | 사용자 계정이 비활성 상태일 가능성 | 오류 코드와 현재 계정 상태를 함께 확인 | SSO | SCN-IDENTITY-SSO-REDIRECT |
| SIG-033 | AADSTS50105 | ERROR_CODE | AADSTS50105 | 사용자가 애플리케이션 역할 또는 할당 대상에 포함되지 않았을 가능성 | 앱 역할·그룹 할당 상태를 확인하기 전 권한 누락으로 확정하지 않음 | SSO | SCN-IDENTITY-SSO-REDIRECT |
| SIG-034 | RasClient Event ID 20227 | WINDOWS_EVENT | RasClient/20227 | VPN 연결 실패 시각과 반환 오류 코드를 연결 | 이벤트 자체는 원인이 아니며 포함된 오류 코드와 범위를 추가 확인 | VPN | RB-REMOTE-VPN-CODES, RB-REMOTE-VPN-720 |
| SIG-035 | VPN 오류 809 | ERROR_CODE | VPN_ERROR_809 | VPN 서버까지 필요한 네트워크 통신이 성립하지 않은 도달성 계열 오류 | 방화벽·NAT·라우팅 중 어느 계층인지 단독 확정하지 않음 | VPN | RB-REMOTE-VPN-CODES, SCN-REMOTE-VPN-809 |
| SIG-036 | IKEv2 UDP 500·4500 도달성 | NETWORK_REACHABILITY | udp/500\|udp/4500 | IKEv2 협상에 필요한 UDP 경로의 허용 여부 | 포트 관측만으로 인증서·NPS 문제를 배제하지 않음 | VPN | RB-REMOTE-VPN-CODES |
| SIG-037 | VPN 오류 720 | ERROR_CODE | VPN_ERROR_720 | PPP IP 프로토콜 구성 또는 주소 할당 실패 계열 오류 | 클라이언트 binding과 서버 주소 할당 문제를 추가 구분 | VPN | RB-REMOTE-VPN-720, SCN-REMOTE-VPN-720 |
| SIG-038 | ms_wanarp binding 비활성 | ADAPTER_BINDING | ms_wanarp Enabled=false | WAN Miniport IP의 Remote Access IP ARP Driver가 비활성화된 상태 | 오류 720과 해당 단말 관측이 함께 있을 때만 원인 후보로 사용 | VPN | RB-REMOTE-VPN-720 |
| SIG-039 | RRAS 주소 풀·DHCP scope 소진 | CAPACITY | rras_ip_pool_exhausted\|dhcp_scope_exhausted | VPN 연결에 할당할 IP 주소가 부족한 서버 측 상태 | 여러 사용자 동시 실패와 실제 할당 상태를 함께 확인 | VPN | RB-REMOTE-VPN-720 |
| SIG-040 | VPN 오류 812·NPS Event 20276 | AUTH_POLICY | VPN_ERROR_812\|NPS/20276 | 클라이언트 인증 방식과 NPS 정책 조건이 일치하지 않을 가능성 | NPS 거부 사유와 적용 정책을 확인하기 전 정책 불일치로 확정하지 않음 | VPN | RB-REMOTE-VPN-CODES |
| SIG-041 | IKEv2 인증서 오류 계열 | CERTIFICATE_ERROR | 13806\|13801\|0x80070040\|0x800B0109 | 인증서 만료·EKU·신뢰 체인·서버 이름 불일치 가능성 | 오류별 인증서 상태와 신뢰 경로를 확인해 세부 원인을 구분 | VPN | RB-REMOTE-VPN-CODES |
| SIG-042 | 시스템 오류 53 | ERROR_CODE | ERROR_BAD_NETPATH\|53 | 네트워크 경로를 찾을 수 없는 SMB 경로 계열 오류 | DNS·TCP 445·VPN·서버 상태를 확인하기 전 원인을 확정하지 않음 | SMB | RB-FILE-PATH, SCN-FILE-PATH-NOT-FOUND |
| SIG-043 | UNC 서버 이름 해석 실패 | DNS | unc_hostname_resolution_failed | 공유 경로의 서버 이름을 회사 DNS에서 올바르게 해석하지 못함 | IP 직접 접속 성공만으로 장기 해결로 간주하지 않음 | SMB | RB-FILE-PATH |
| SIG-044 | SMB TCP 445 도달 실패 | NETWORK_REACHABILITY | tcp/445 unreachable | 클라이언트와 SMB 서버 사이 TCP 연결이 성립하지 않음 | 서버 listener와 중간 방화벽·라우팅을 패킷 관측으로 구분 | SMB | RB-FILE-PATH, SCN-FILE-PATH-NOT-FOUND |
| SIG-045 | SMB 서버 SYN 수신·SYN-ACK 부재 | PACKET_TRACE | syn_arrived+no_syn_ack | 요청은 서버에 도달했지만 서버 listener·서비스·로컬 방화벽 계층에서 응답하지 않는 상태 | 동시 trace 또는 서버 측 관측 없이 중간망과 서버를 구분하지 않음 | SMB | RB-FILE-PATH |
| SIG-046 | SMB Access Denied | ERROR_MESSAGE | Access Denied\|ACCESS_DENIED | SMB 세션은 시도됐지만 인증·보안 정책·공유 또는 NTFS 권한에서 거부됨 | 경로 미발견과 구분하고 권한 완화를 바로 제안하지 않음 | SMB | RB-FILE-SMB-ACCESS, SCN-FILE-ACCESS-DENIED |
| SIG-047 | Insecure guest logon 차단 | WINDOWS_EVENT | SMBClient/31017\|unauthenticated guest access blocked | 대상 장치가 guest 접근에 의존하지만 Windows가 비인증 guest 연결을 차단 | 안전하지 않은 guest 허용을 해결책으로 제시하지 않고 인증된 접근으로 이관 | SMB | RB-FILE-SMB-ACCESS |
| SIG-048 | SMB signing·NTLM 정책 거부 | SECURITY_POLICY | smb_signing_unsupported\|ntlm_blocked | 대상 장치가 Windows의 SMB signing 또는 NTLM 보안 요구를 충족하지 못함 | 보안 정책 해제를 제안하지 않고 장치 지원 상태와 조직 정책을 확인 | SMB | RB-FILE-SMB-ACCESS |
| SIG-049 | 특정 사용자·그룹만 SMB 거부 | IMPACT_SCOPE | single_user_or_group_denied | 공유·NTFS 유효 권한 또는 Access-Based Enumeration 불일치 가능성 | 다른 사용자 결과와 최소 필요 권한을 확인하기 전 권한 원인을 확정하지 않음 | SMB | RB-FILE-SMB-ACCESS, SCN-FILE-ACCESS-DENIED |
| SIG-050 | SMBServer Event ID 1020 | WINDOWS_EVENT | SMBServer/1020 | 파일 시스템 작업이 경고 임계값보다 오래 걸린 I/O 지연 신호 | 파일 시스템 필터와 하위 저장 계층을 추가 구분 | SMB | RB-FILE-EVENT-1020, SCN-FILE-EVENT-1020 |
| SIG-051 | 파일 서버 상위 계층 작업 상관 | LAYER_CORRELATION | filesystem_filter_or_backup_correlation | Event ID 1020 발생 시각이 백업·바이러스 검사·파일 시스템 필터 작업과 일치하고 하위 저장 계층은 정상인 상태 | 작업 시각과 하위 저장 계층 지연을 함께 비교하기 전 상위 계층 원인으로 확정하지 않음 | SMB | RB-FILE-EVENT-1020 |
| SIG-052 | 하위 저장 계층 지연 | STORAGE_LATENCY | storport_or_storage_latency_high | Storport 또는 하위 저장 계층의 높은 응답시간이 SMB 지연과 같은 시각에 관측됨 | 단일 최대값이 아니라 사용자 증상·Event ID 1020과 시간 상관을 확인 | SMB | RB-FILE-EVENT-1020 |
| SIG-053 | 여러 입력 조건의 동일 API 예외 반복 | IMPACT_SCOPE | same_stack_fingerprint_across_multiple_inputs | 둘 이상의 비식별화된 사용자·데이터 조건에서 같은 API 경로와 예외 지문이 반복됨 | 요청 ID로 각각의 응답과 로그를 연결하고 DB·의존 서비스 공통 장애를 별도 배제 | PORTAL_API | RB-PORTAL-API-500 |
| SIG-054 | 특정 데이터 조건의 동일 API 예외 | DATA_CONDITION | same_stack_fingerprint_single_data_condition | 특정 비식별화 데이터 조건에서만 같은 API 예외 지문이 재현되고 다른 조건은 정상임 | 실제 개인정보나 업무 데이터를 저장하지 않고 재현 조건을 비식별화해 기록 | PORTAL_API | RB-PORTAL-API-500 |
| SIG-055 | VPN 필터 binding·WAN Miniport 이상 후보 | ADAPTER_BINDING | third_party_filter_binding\|wan_miniport_fault_suspected | 단일 단말에서 의심되는 제3자 네트워크 필터 binding이 있거나 정상 binding에도 오류 720이 지속되는 상태 | 드라이버 손상을 직접 확정하지 않고 서버 주소 할당과 영향 범위를 먼저 확인하며 자동 제거·재설치는 수행하지 않음 | VPN | RB-REMOTE-VPN-720 |
