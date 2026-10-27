# 직원 가이드 검토 목록

JSON 정본은 `datasets/knowledge/v0/documents/employee_guides/`, RAG 청크는
`datasets/knowledge/v0/chunks/`에 있다. 이 폴더의 개별 Markdown은 팀 검토를 위한
자동 생성본이므로 직접 수정하지 않는다.

| 범위 | 가이드 | 내용 상태 | RAG 적재 |
|---|---|---|---|
| 공통 | [IT 문의 전에 준비할 정보](GUIDE-COMMON-INQUIRY-INFO.v1.md) | 담당자 확정 | 대기 |
| 공통 | [문의 처리와 해결 확인 절차](GUIDE-COMMON-PROCESS.v1.md) | 담당자 확정 | 대기 |
| 공통 | [Hops가 처리하는 IT 문의 범위](GUIDE-COMMON-SCOPE.v1.md) | 담당자 확정 | 대기 |
| IDENTITY | [비밀번호를 잊었을 때](GUIDE-IDENTITY-PASSWORD-FORGOT.v1.md) | 담당자 확정 | 대기 |
| IDENTITY | [비밀번호 재설정 오류](GUIDE-IDENTITY-PASSWORD-RESET.v1.md) | 담당자 확정 | 대기 |
| IDENTITY | [MFA 등록 한도](GUIDE-IDENTITY-MFA-LIMIT.v1.md) | 담당자 확정 | 대기 |
| IDENTITY | [MFA 복구](GUIDE-IDENTITY-MFA-RECOVERY.v1.md) | 담당자 확정 | 대기 |
| REMOTE_ACCESS | [VPN 연결 오류](GUIDE-REMOTE-VPN.v1.md) | 담당자 확정 | 대기 |
| FILE_SHARE | [공유 드라이브 연결 오류](GUIDE-FILE-DRIVE.v1.md) | 담당자 확정 | 대기 |
| FILE_SHARE | [공유 폴더 액세스 거부](GUIDE-FILE-ACCESS.v1.md) | 담당자 확정 | 대기 |
| PORTAL | [포털 서버 오류](GUIDE-PORTAL-SERVER-ERROR.v1.md) | 담당자 확정 | 대기 |
| PORTAL | [포털 조회 지연](GUIDE-PORTAL-SLOW.v1.md) | 담당자 확정 | 대기 |
| PORTAL | [포털 저장 대기·실패](GUIDE-PORTAL-SAVE-WAIT.v1.md) | 담당자 확정 | 대기 |

Wi-Fi·802.1X 가이드는 근거 `GE-014`, `GE-055`가 `hold`이므로 생성하지 않았다.
13건은 데이터 담당자의 내용 확인을 마쳤다. 팀 리뷰를 거친 뒤에만 RAG 적재 상태를 변경한다.
