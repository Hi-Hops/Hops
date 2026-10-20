# Spring 패키지 경계

이슈 #4의 초기 구조 결정이며 실제 API 구현이나 팀 PR 승인 완료를 뜻하지 않는다.

| 패키지 | 책임 |
|---|---|
| agent | Spring → Python 실행 요청, SSE 진행 수신, 결과 형식 검증 |
| investigation | Python이 요청한 읽기 전용 진단 조회의 검증과 실행 |
| department | 부서·소속·활성 여부·부서 R&R 제공 |
| ticket | 제안 부서·업무 정책 검증, 실제 티켓 저장·배정·이력 확정 |

## 진단 조회

목표 흐름은 Python Diagnosis → Spring investigation → 가상 기업 Mock API → 관측 결과 반환이다. Python은 조사 계획과 원인 후보 분석을 맡는다. Spring은 허용 도구·typed 입력·실행 문맥·권한·조회 범위·예산을 검사한다. 서비스 인증과 사용자 권한은 별도로 검증한다. timeout·거부·실패를 정상 결과와 구분하고 조회 시각과 근거 참조를 반환한다. 임의 SQL·명령 실행·데이터 변경은 제공하지 않는다.

controller/dto/service/client 패키지만 준비한다. 현재 Entity·repository는 필요하지 않다. HTTP 경로·스키마·인증·예산·timeout·재시도·근거 저장 방식은 상세 계약에서 합의한 뒤 구현한다. 기존 MVP API의 callback/SSE 미결정 사항은 그대로 유지한다.

## ownership 보류

현재 부서 R&R·활성 카탈로그와 AI 부서 제안, Spring 배정 검증으로 구성하므로 독립 ownership 도메인은 추가하지 않는다. 서비스별 주관·협업 부서, 관리 API, 책임 변경 이력이 독립적으로 필요해지면 재검토한다. 부서 카탈로그와 실제 티켓 배정을 혼동하지 않는다.

## 후속 구현

도구별 입출력·실행 권한 계약, Mock API 연결, Python Fake/Diagnosis 연동과 정상·거부·timeout·실패 사례를 별도로 검증한다. 진단 API·Mock 통신·권한 검사·실제 티켓 저장·배정은 아직 미구현이다. 모델 제안이나 실행 완료를 저장 성공으로 간주하지 않는다.
