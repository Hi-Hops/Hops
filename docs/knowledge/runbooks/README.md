# 담당자 런북 검토 목록

JSON 정본은 `datasets/knowledge/v0/documents/staff_runbooks/`, RAG 청크는
`datasets/knowledge/v0/chunks/`에 있다. 이 폴더의 개별 Markdown은 사람이 읽기 위한
자동 생성본이므로 직접 수정하지 않는다.

| 서비스 | 런북 | 내용 상태 | 팀 상태 | Tool | 시나리오 |
|---|---|---|---|---|---|
| IDENTITY | [MFA 인증 수단 복구](RB-IDENTITY-MFA-RECOVERY.v1.md) | 담당자 확정 | 대기 | 미연결 | 미실행 |
| REMOTE_ACCESS | [VPN 오류 코드](RB-REMOTE-VPN-CODES.v1.md) | 담당자 확정 | 대기 | 미연결 | 미실행 |
| REMOTE_ACCESS | [VPN 오류 720](RB-REMOTE-VPN-720.v1.md) | 담당자 확정 | 대기 | 미연결 | 미실행 |
| FILE_SHARE | [SMB 접근 거부](RB-FILE-SMB-ACCESS.v1.md) | 담당자 확정 | 대기 | 미연결 | 미실행 |
| FILE_SHARE | [파일 서버 이벤트 1020](RB-FILE-EVENT-1020.v1.md) | 담당자 확정 | 대기 | 미연결 | 미실행 |
| FILE_SHARE | [공유 폴더 경로 오류](RB-FILE-PATH.v1.md) | 담당자 확정 | 대기 | 미연결 | 미실행 |
| PORTAL | [API 500](RB-PORTAL-API-500.v1.md) | 담당자 확정 | 대기 | 미연결 | 미실행 |
| PORTAL | [HikariCP 풀 고갈](RB-PORTAL-HIKARI-POOL.v1.md) | 담당자 확정 | 대기 | 미연결 | 미실행 |
| PORTAL | [PostgreSQL 연결 한도](RB-PORTAL-DB-CONNECTION-LIMIT.v1.md) | 담당자 확정 | 대기 | 미연결 | 미실행 |
| PORTAL | [PostgreSQL Lock·Deadlock](RB-PORTAL-DB-LOCK.v1.md) | 담당자 확정 | 대기 | 미연결 | 미실행 |
| PORTAL | [PostgreSQL Slow Query](RB-PORTAL-DB-SLOW-QUERY.v1.md) | 담당자 확정 | 대기 | 미연결 | 미실행 |

Wi-Fi·802.1X 런북은 근거가 `hold`이므로 생성하지 않았다. 일반 영역 6건은 데이터 담당자의
내용 확인을 반영했으며, 팀 리뷰와 RAG 적재 승인은 별도다.
