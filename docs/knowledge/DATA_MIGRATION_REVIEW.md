# 기존 지식 자료 전환 목록 — PRD 1.44

상태: 문서 제목과 대상 범위에 따른 1차 분류 **후보**. 실제 기술 내용·출처·공개 범위·가상 시스템 적합성은 데이터 담당자 검수가 필요하다. 기존 v0 문서와 대응 JSON은 변경하지 않았고 어떤 자료도 새 검색 정본으로 승인하지 않았다.

## 직원 가이드 13건

| 기존 문서 | 1차 검토 방향 |
|---|---|
| [GUIDE-COMMON-INQUIRY-INFO.v1.md](guides/GUIDE-COMMON-INQUIRY-INFO.v1.md) | 공통 문의 정책을 새 질문·해결 확인 절차에 맞춰 수정 검토 |
| [GUIDE-COMMON-PROCESS.v1.md](guides/GUIDE-COMMON-PROCESS.v1.md) | 공통 문의 정책을 새 질문·해결 확인 절차에 맞춰 수정 검토 |
| [GUIDE-COMMON-SCOPE.v1.md](guides/GUIDE-COMMON-SCOPE.v1.md) | 공통 문의 정책을 새 질문·해결 확인 절차에 맞춰 수정 검토 |
| [GUIDE-FILE-ACCESS.v1.md](guides/GUIDE-FILE-ACCESS.v1.md) | 파일 공유 후보; 구현 가정·권한·안내 범위를 검토 |
| [GUIDE-FILE-DRIVE.v1.md](guides/GUIDE-FILE-DRIVE.v1.md) | 파일 공유 후보; 구현 가정·권한·안내 범위를 검토 |
| [GUIDE-IDENTITY-MFA-LIMIT.v1.md](guides/GUIDE-IDENTITY-MFA-LIMIT.v1.md) | 공통 로그인 후보; MFA·계정 정책의 가상 환경 적합성 검토 |
| [GUIDE-IDENTITY-MFA-RECOVERY.v1.md](guides/GUIDE-IDENTITY-MFA-RECOVERY.v1.md) | 공통 로그인 후보; MFA·계정 정책의 가상 환경 적합성 검토 |
| [GUIDE-IDENTITY-PASSWORD-FORGOT.v1.md](guides/GUIDE-IDENTITY-PASSWORD-FORGOT.v1.md) | 공통 로그인 후보; MFA·계정 정책의 가상 환경 적합성 검토 |
| [GUIDE-IDENTITY-PASSWORD-RESET.v1.md](guides/GUIDE-IDENTITY-PASSWORD-RESET.v1.md) | 공통 로그인 후보; MFA·계정 정책의 가상 환경 적합성 검토 |
| [GUIDE-PORTAL-SAVE-WAIT.v1.md](guides/GUIDE-PORTAL-SAVE-WAIT.v1.md) | 전자결재에 재사용 가능한지 기능·근거·표현 검토 |
| [GUIDE-PORTAL-SERVER-ERROR.v1.md](guides/GUIDE-PORTAL-SERVER-ERROR.v1.md) | 전자결재에 재사용 가능한지 기능·근거·표현 검토 |
| [GUIDE-PORTAL-SLOW.v1.md](guides/GUIDE-PORTAL-SLOW.v1.md) | 전자결재에 재사용 가능한지 기능·근거·표현 검토 |
| [GUIDE-REMOTE-VPN.v1.md](guides/GUIDE-REMOTE-VPN.v1.md) | VPN은 새 3개 시스템 범위 밖; 기존 자료 보존, MVP 인덱스 제외 검토 |

## 담당자 런북 11건

| 기존 문서 | 1차 검토 방향 |
|---|---|
| [RB-FILE-EVENT-1020.v1.md](runbooks/RB-FILE-EVENT-1020.v1.md) | 파일 공유 후보; SMB 등 기술 가정·직원 비노출·근거 확인 |
| [RB-FILE-PATH.v1.md](runbooks/RB-FILE-PATH.v1.md) | 파일 공유 후보; SMB 등 기술 가정·직원 비노출·근거 확인 |
| [RB-FILE-SMB-ACCESS.v1.md](runbooks/RB-FILE-SMB-ACCESS.v1.md) | 파일 공유 후보; SMB 등 기술 가정·직원 비노출·근거 확인 |
| [RB-IDENTITY-MFA-RECOVERY.v1.md](runbooks/RB-IDENTITY-MFA-RECOVERY.v1.md) | 공통 로그인 후보; 인증 환경·실제 적용 조건 확인 |
| [RB-PORTAL-API-500.v1.md](runbooks/RB-PORTAL-API-500.v1.md) | 전자결재 대상 데모의 실제 기술 구성에 부합할 때만 전환 검토 |
| [RB-PORTAL-DB-CONNECTION-LIMIT.v1.md](runbooks/RB-PORTAL-DB-CONNECTION-LIMIT.v1.md) | 전자결재 대상 데모의 실제 기술 구성에 부합할 때만 전환 검토 |
| [RB-PORTAL-DB-LOCK.v1.md](runbooks/RB-PORTAL-DB-LOCK.v1.md) | 전자결재 대상 데모의 실제 기술 구성에 부합할 때만 전환 검토 |
| [RB-PORTAL-DB-SLOW-QUERY.v1.md](runbooks/RB-PORTAL-DB-SLOW-QUERY.v1.md) | 전자결재 대상 데모의 실제 기술 구성에 부합할 때만 전환 검토 |
| [RB-PORTAL-HIKARI-POOL.v1.md](runbooks/RB-PORTAL-HIKARI-POOL.v1.md) | 전자결재 대상 데모의 실제 기술 구성에 부합할 때만 전환 검토 |
| [RB-REMOTE-VPN-720.v1.md](runbooks/RB-REMOTE-VPN-720.v1.md) | VPN은 현재 3개 시스템 범위 밖; MVP 인덱스 제외 검토 |
| [RB-REMOTE-VPN-CODES.v1.md](runbooks/RB-REMOTE-VPN-CODES.v1.md) | VPN은 현재 3개 시스템 범위 밖; MVP 인덱스 제외 검토 |

## 기존 정책·기계 판독 데이터

- 기존 [정책 5건](policies/README.md)은 예전 규칙을 포함하므로 새 [정책 설명](policies/POLICY-PRD-1.44.md)과 [v1 JSON 초안](../../datasets/knowledge/v1/README.md)으로 전환해 검수한다. 정책 JSON과 v0 렌더링 문서·청크는 자동 동기화되지 않았다.
- 각 가이드·런북의 `datasets/knowledge/v0/documents/` 대응 JSON, catalog·시나리오 참조와 청크·manifest 해시를 함께 갱신해야 한다. 새로운 v1 자료와 생성 검증 경로는 아직 만들어지지 않았다.
- v0 작성 당시 담당자 확인·팀 리뷰 대기 상태를 새 자료의 승인으로 사용하지 않는다. 변경된 문서·JSON은 작성·검수·공개 범위와 팀 공통 리뷰를 따로 기록한다.
