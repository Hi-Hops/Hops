# Hops Knowledge 데이터셋 범위

상태: 데이터 구축 범위 확정 · 파생 데이터 작성 중 · 팀 리뷰 전

## 1. 범위와 두 가지 사용 목적

Knowledge 데이터는 다음 두 범위를 함께 지원한다.

| 범위 | 목적 | 실제 장애 재현 필요 |
|---|---|---|
| Knowledge coverage | 직원 안내, 담당자 런북 검색, 근거 기반 이관 | 아니오 |
| Demo reproducible coverage | Demo Portal 장애를 발생시키고 읽기 Tool로 관측·진단·복구 | 예 |

모든 지식 장애를 Demo Portal에서 구현하지 않는다. 계정·VPN·공유 드라이브는 공개 근거를 한국어 가이드·런북으로 재작성해 답변과 이관에 사용하고, 첫 실제 재현 범위는 PORTAL API·PostgreSQL로 제한한다.

## 2. 서비스 범위

| 사용자 서비스 | 컴포넌트 | 기본 담당 | 현재 근거 | 파생 데이터 |
|---|---|---|---|---|
| `IDENTITY` | 비밀번호·MFA·SSO | 계정 권한, 일부 Backend | 사람 include 6건 | MFA 런북 1건, 가이드 확정 4건 |
| `REMOTE_ACCESS` | VPN·Wi-Fi 802.1X | 네트워크, 일부 계정 권한 | VPN include 2건, Wi-Fi hold 2건 | VPN 런북 2건·가이드 확정 1건, Wi-Fi 보류 |
| `FILE_SHARE` | SMB·파일 서버·네트워크 경로 | 시스템, 일부 네트워크 | 사람 include 4건 | 런북 3건, 가이드 확정 2건 |
| `PORTAL` | `PORTAL_API`, `PORTAL_DB` | Backend, DB | 공식 근거 include 29개·hold 2개 | 런북 5건, 가이드 확정 3건 |

사용자에게는 `PORTAL` 하나로 보이고, API·DB 컴포넌트는 진단과 부서 이관에서만 사용한다.

## 3. 원문 검수 현황

| 검수 묶음 | include | hold | reject | 검수자 |
|---|---:|---:|---:|---|
| 일반 공개 문서 62건 | 12 | 4 | 46 | 데이터 담당자 |
| PORTAL 공식 근거 31개 | 29 | 2 | 0 | 데이터 담당자 |

원문 전체를 바로 RAG에 넣지 않는다. include된 절을 기술적 근거로 사용해 한국어 직원 가이드와 담당자 런북을 작성한다. hold 문서는 해제 조건을 충족하기 전까지 적재하지 않는다.

일반 공개 문서 62건의 사람 판정은
[`general_source_review.csv`](../../datasets/knowledge/v0/manifests/general_source_review.csv),
출처·라이선스는
[`general_sources.yaml`](../../datasets/knowledge/v0/manifests/general_sources.yaml)에 정리했다.
include 문서의 섹션 82건은
[`general_section_access.jsonl`](../../datasets/knowledge/v0/manifests/general_section_access.jsonl)에
기본 거부로 기록했다. 이 중 12개는 직원 공개 **검수 후보**일 뿐이며 아직
`EMPLOYEE/QUOTABLE`로 승인된 섹션은 없다.

## 4. 데이터 종류와 목표

| 종류 | 목적 | 목표 | 현재 | 상태 |
|---|---|---:|---:|---|
| `SOURCE_EVIDENCE` | 기술 사실과 출처 추적 | 선정 근거 전체 | 일반 62건·PORTAL 31개 공유 | 담당자 확정, 팀 리뷰 전·원문 RAG 금지 |
| `EMPLOYEE_GUIDE` | 직원이 안전하게 수행할 확인·안내 | 수정 목표 14건 | 13건 | 담당자 확정·팀 리뷰 전, Wi-Fi 1건 hold |
| `STAFF_RUNBOOK` | 진단·원인 구분·담당자 조치·이관 | 수정 목표 12건 | 11건 | PORTAL 5건·일반 영역 6건 담당자 확정, 팀 리뷰 전 |
| `POLICY` | 문의·CS·재이관·권한·CASE·지식 운영 규칙 | 5건 | 5건 | 담당자 확정·팀 리뷰 전 |
| `CASE` | 재현하고 해결을 검증한 과거 사례 | 반복 축적 | 0건 | 현재는 정상, Schema 필요 |
| `CATALOG` | 서비스·컴포넌트·부서·라우팅·의존성 | 서비스 4종·부서 8개 | 일부 | 상세 라우팅 공유 누락 |
| `DIAGNOSTIC_SIGNAL_REGISTRY` | 런북·Tool·평가에서 쓰는 신호 ID·의미·확정 한계의 단일 정본 | 사용되는 신호만 | [55개](references/ERROR_SIGNALS.md) | 담당자 확정·팀 리뷰 전·RAG 적재 금지 |
| `IT_GLOSSARY` | 직원·담당자가 함께 쓰는 안전한 용어 정의 | 범위 기반 | [50개](references/IT_GLOSSARY.md) | 담당자 확정·팀 리뷰 전, 정의만 직원 공개 후보 |
| `SCENARIO_CATALOG` | 지식·재현·업무 흐름의 숨겨진 평가 정답 | 34건 | 34건 | 담당자 확정, 팀 리뷰 전·RAG 적재 금지 |
| `EVAL` | 경로·근거·부서·안전성 평가 | Hops dev 30 + test 30 | 0건 | 미작성 |
| `MANIFEST` | 출처·라이선스·버전·검수·파생 관계 | 전체 데이터 | 일반·PORTAL 근거 공유 | 파생 문서·CASE 계보는 구축 중 |

진단 신호 레지스트리는 독립 검색 문서로 적재하지 않는다. `STAFF_RUNBOOK`의
`causes` 청크를 생성할 때 참조 신호의 역할·매칭 조건·의미·확정 제한과 레지스트리
해시를 결합해 Knowledge Agent가 검색 결과만으로 Evidence Bundle을 조립할 수 있게 한다.

## 5. 직원 가이드 목표

PORTAL 가이드 한 건을 증상별 3건으로 세분화했으므로 기존 12건 제목안의 수정 목표는 14건이다.

| ID | 제목 | 상태 |
|---|---|---|
| `GUIDE-IDENTITY-PASSWORD-FORGOT` | 비밀번호를 잊었을 때 | 담당자 확정·팀 리뷰 대기 |
| `GUIDE-IDENTITY-PASSWORD-RESET` | 비밀번호 재설정 중 오류가 날 때 | 담당자 확정·팀 리뷰 대기 |
| `GUIDE-IDENTITY-MFA-LIMIT` | MFA 인증 앱을 더 등록할 수 없을 때 | 담당자 확정·팀 리뷰 대기 |
| `GUIDE-IDENTITY-MFA-RECOVERY` | 휴대폰 분실·번호 변경·인증 코드 미수신 | 담당자 확정·팀 리뷰 대기 |
| `GUIDE-REMOTE-VPN` | VPN이 연결되지 않을 때 | 담당자 확정·팀 리뷰 대기 |
| `GUIDE-REMOTE-WIFI` | 사내 Wi-Fi가 연결되지 않을 때 | hold 해제 후 |
| `GUIDE-FILE-DRIVE` | 공유 드라이브에 빨간 X가 보일 때 | 담당자 확정·팀 리뷰 대기 |
| `GUIDE-FILE-ACCESS` | 공유 폴더 액세스가 거부될 때 | 담당자 확정·팀 리뷰 대기 |
| `GUIDE-PORTAL-SERVER-ERROR` | 포털에서 서버 오류가 표시될 때 | 담당자 확정·팀 리뷰 대기 |
| `GUIDE-PORTAL-SLOW` | 포털 조회가 느릴 때 | 담당자 확정·팀 리뷰 대기 |
| `GUIDE-PORTAL-SAVE-WAIT` | 포털 저장이 끝나지 않거나 실패할 때 | 담당자 확정·팀 리뷰 대기 |
| `GUIDE-COMMON-INQUIRY-INFO` | 문의 전에 준비할 정보 | 담당자 확정·팀 리뷰 대기 |
| `GUIDE-COMMON-PROCESS` | IT 문의 처리 절차와 해결 확인 | 담당자 확정·팀 리뷰 대기 |
| `GUIDE-COMMON-SCOPE` | 범위 밖 문의와 CS 이관 기준 | 담당자 확정·팀 리뷰 대기 |

## 6. 담당자 런북 목표

기존 IIS·SQL Server 기반 PORTAL 후보는 확정된 Spring Boot·HikariCP·PostgreSQL 환경과 맞지 않아 현재 PORTAL 런북 5건으로 교체했다. 일반 공개 근거에서 파생한 IDENTITY·REMOTE_ACCESS·FILE_SHARE 런북 6건도 데이터 담당자 검수를 완료했다.

| ID | 제목 | 상태 |
|---|---|---|
| `RB-IDENTITY-MFA-RECOVERY` | MFA 초기화·인증 방법 재설정 | 담당자 확정·팀 리뷰 대기 |
| `RB-REMOTE-VPN-CODES` | VPN 오류 코드별 점검 | 담당자 확정·팀 리뷰 대기 |
| `RB-REMOTE-VPN-720` | VPN 오류 720 점검 | 담당자 확정·팀 리뷰 대기 |
| `RB-REMOTE-WIFI-8021X` | 802.1X·Wi-Fi 인증 문제 | hold 해제 후 |
| `RB-FILE-SMB-ACCESS` | SMB 접근 거부 원인별 점검 | 담당자 확정·팀 리뷰 대기 |
| `RB-FILE-EVENT-1020` | 파일 서버 I/O 지연·이벤트 1020 | 담당자 확정·팀 리뷰 대기 |
| `RB-FILE-PATH` | 공유 폴더 접근 불가·오류 53·0x80004005 | 담당자 확정·팀 리뷰 대기 |
| `RB-PORTAL-API-500` | 포털 API 500 | 작성됨 |
| `RB-PORTAL-HIKARI-POOL` | HikariCP 풀 고갈 | 작성됨 |
| `RB-PORTAL-DB-CONNECTION-LIMIT` | PostgreSQL 연결 한도 | 작성됨 |
| `RB-PORTAL-DB-LOCK` | PostgreSQL Lock·Deadlock | 작성됨 |
| `RB-PORTAL-DB-SLOW-QUERY` | PostgreSQL Slow Query | 작성됨 |

SSO `AADSTS50011`은 현재 라우팅 Reference다. 별도 런북 승격은 추가 결정 사항이다.

## 7. 정책·CASE·평가 데이터

정책 5건은 데이터 담당자의 내용 확인을 마쳤으며 [정책 검토 목록](policies/)에서 확인한다.

1. `POLICY-INQUIRY-FLOW`: 문의 접수·추가 질문·`ANSWER → SUPPORT_DIAGNOSIS` 전환
2. `POLICY-HANDOFF-CS`: 티켓 이관·재이관·CS 검토·후보 소진
3. `POLICY-KNOWLEDGE-ACCESS`: 문서 접근·노출·`NEVER_EXPOSE`·기본 거부
4. `POLICY-CASE-LIFECYCLE`: 해결 확인·CASE 생성·재오픈
5. `POLICY-KNOWLEDGE-GOVERNANCE`: 데이터 버전·폐기·재임베딩·평가 분리

CASE는 [지식 수명주기](KNOWLEDGE_LIFECYCLE.md)의 승격 조건을 모두 통과한 뒤 생성한다. 공개 문서의 증상·원인·해결을 옮긴 자료는 `SOURCE_EVIDENCE` 또는 파생 런북이지 실제 Hops 해결 CASE가 아니다.

평가셋의 질의·정답 문구는 KB에 넣지 않는다. 외부 TechQA는 라이선스를 확인하기 전 Git과 최종 평가에 포함하지 않는다.

시나리오 카탈로그 34건은 평가셋 자체가 아니라 **평가 계약과 데이터 준비 목록**이다.
사용자 질의·Tool fixture·채점 정답을 추가하고 dev/test를 분리한 뒤에만 `EVAL`로
센다. 숨겨진 원인과 기대 부서는 현재도 RAG에 적재하지 않는다.

## 8. 언어와 권한

- 실데이터 원문은 원어를 보존한다.
- Hops 파생 가이드·런북·정책·CASE·평가 질의는 한국어로 작성한다.
- 오류 코드, 제품명, 로그 필드, 명령어는 원문 표기를 유지한다.
- 직원 가이드는 `EMPLOYEE/QUOTABLE`, 런북·CASE·오류 신호는 기본 `STAFF/INTERNAL_ONLY`다.
- 진단 신호 레지스트리는 접근 등급과 관계없이 독립 RAG 검색 대상이 아니다. Knowledge가 런북의 signal ID를 조립할 때만 사용한다.
- v0 용어집은 운영 명령·원시 로그·내부 설정을 포함하지 않는 짧은 정의만 담아 모두 `EMPLOYEE/QUOTABLE` 후보로 둔다. `RAG`, `청크`, `임베딩`도 제품 설명이나 문의 처리 방식 질문에만 답하고 장애 원인 근거로 사용하지 않는다.

## 9. Git 공유 기준

Draft PR과 팀 리뷰를 거쳐 다음만 Git 정본으로 채택한다.

- 설계 문서, JSON Schema, 검수된 파생 문서
- 서비스·라우팅 카탈로그
- 출처 URL·라이선스·commit·hash·사람 판정 manifest
- 렌더링·검증 스크립트와 비식별 fixture

다음은 Git에 넣지 않는다.

- 공개 원문 전체 복사본과 수집 캐시
- 임베딩 벡터·Vector DB 파일
- 실행 로그·실제 런타임 티켓·비밀값
- 정본에서 재생성할 수 있는 청크
- 사람 검수 전 실험 결과

`chunks/`, `manifest.json`, `manifests/chunk_review_summary.json`은 검증 스크립트가
정본에서 다시 만들기 때문에 `.gitignore`로 제외한다. 사람 판정과 출처를 담은 나머지
manifest는 공유한다.

현재 `datasets/knowledge/v0`는 리뷰 후보이며 팀 승인 전 공통 정본으로 간주하지 않는다.
공유 순서와 역할별 사용 경계는 [팀 공유 안내](TEAM_HANDOFF.md)를 따른다.
