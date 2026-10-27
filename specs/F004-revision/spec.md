# F004 메시지 정정과 최신 버전 재분석

상태: 개발 기준 확정 / 구현·검증 별도 · 범위: MVP · 담당: frontend(화면) · 협의: backend(버전·이력), ai(재분석)
근거: PRD 4.4, 8.2 · API 2.1, 2.4 · DATA 3, 5 · ERD `message`, `message_revision`, `agent_run.input_version`

## 확정 범위와 남은 작업 (2026-10-27)

본인 사용자 메시지는 기간·횟수 제한 없이 정정한다. 종료/이관 완료 뒤에는 보완 저장만 하며 자동 재오픈·재배정·새 티켓 생성 없음. 버전 충돌 시 최신 내용을 조회해 초안과 비교하고 새 의도로 재제출한다. 실제 DB 동시성·종료 상태 연동은 미검증이다.

정확한 타입·enum은 [DATA_TYPES](../contracts/DATA_TYPES.md), 정책은 [WIRE_CONTRACT 10절](../contracts/WIRE_CONTRACT.md#10-인증재접속최신-대화분석-재시도-추가-프로필), 담당 구현·미검증은 [11절](../contracts/WIRE_CONTRACT.md#11-담당별-구현과-검증-상태)을 따른다. 확정은 개발 기준 선택이며 팀 리뷰/병합·서비스 구현 완료가 아니다.

## 1. 목표

보낸 메시지를 고치면 원문과 수정 이력을 남기고, 아직 분석 중인 문제는 최신 내용으로 다시 분석한다. 이미 만든 티켓은 유지한다. (PRD 4.4 · PRD 8.2 "정정 후 재실행" 검증)

## 2. 화면

채팅(`/inquiries/:inquiryId/chat`) 안의 사용자 메시지 정정.

## 3. 사용자 시나리오

1. 문의자가 "VPN"을 "사내 포털"로 잘못 썼다는 걸 알고 해당 메시지를 고친다.
2. 메시지에 "정정됨"이 붙고, 이전 내용을 펼쳐 볼 수 있다.
3. 그 문제가 아직 분석 중이면 고친 내용으로 다시 분석된다. 이전 분석 결과는 화면에 반영되지 않는다.
4. 티켓이 이미 만들어진 뒤라면 정정은 정보 보완으로만 저장된다.

## 4. 인수 조건

- **F004-AC01 정정 요청**
  When 자기 메시지를 고쳐 저장하면 · Then `POST /api/inquiries/{id}/messages/{messageId}/revisions`에 `Idempotency-Key`와 `{expectedInquiryVersion,content}`를 보내고, 저장 응답 전에는 "정정 중"을 표시한다. 입력 검증은 F003-AC02와 같다. (API 2.4)
- **F004-AC02 수정 이력 표시**
  저장되면 "정정됨"을 표시하고 이전 내용을 볼 수 있게 한다. 원문은 지워지지 않는다. (PRD 4.4 · API 2.1 "대화순 메시지와 정정 이력" · ERD `message_revision`)
- **F004-AC03 분석 중 문제의 재분석**
  Given 정정한 내용이 분석 중인 문제에 속함 · Then 새 문의 버전으로 다시 분석하고, 이전 버전의 진행·답변 조각은 화면에 반영하지 않는다(F002-AC05). 이미 생성된 티켓 카드는 그대로 둔다. (PRD 4.4 · DATA 3)
- **F004-AC04 이관 이후 정정**
  Given 모든 문제의 분석·티켓 생성이 끝남 · Then 정정은 정보 보완으로만 저장하고 재분석·재배정·새 티켓 표시를 하지 않는다. (PRD 4.4 · DATA 3)
- **F004-AC05 충돌**
  다른 탭이나 새 메시지로 버전이 바뀌어 충돌하면 최신 대화를 다시 불러오고, 고치려던 내용은 편집 상태로 남긴다. (API 1)
- **F004-AC06 결과 미확인**
  HTTP 시간 초과·끊김은 F003-AC04와 같이 처리한다. 다시 보낼 때는 같은 `Idempotency-Key`와 본문을 쓰고, 화면에 같은 정정 이력을 두 번 그리지 않는다(중복 저장 방지는 서버 책임). (API 2.4 · DATA 5)
- **F004-AC07 정정할 수 있는 메시지**
  사용자 본인 메시지만 정정할 수 있다. AI 메시지에는 정정 버튼이 없다. (PRD 2 "문의자: 내용 정정" · 횟수·기간 제한 없음, 종료 뒤에는 보완 저장만 허용(Q-BE-11))

## 5. 계약(화면이 기대하는 값)

공통 필드·필수/nullable·wire 표현의 원본은 [데이터 타입·필드 문서](../contracts/DATA_TYPES.md)다. 아래 MVP 연결표는 확정 개발 기준이다. 후속 기능 후보와 실제 담당 구현·검증은 구분한다.

| 용도 | 경로·이벤트 | 공통 계약 타입 | 구현·검증 추적 |
|---|---|---|---|
| 정정 | POST /api/inquiries/{id}/messages/{messageId}/revisions | MessageReviseCommand → MutationResult / ApiErrorBody | Q-INF-6, Q-BE-10 |
| 현재 내용·이력 | GET /api/inquiries/{id}/messages | ChatMessage.revision / revisions(MessageRevision[]) | Q-BE-7 |
| 문의 버전 | REST 요청/응답 | expectedInquiryVersion / inquiryVersion | 물리 저장 Q-BE-3 |

메시지 revision과 문의 version은 다른 값이다. 동일 정정의 재전송에는 같은 requestId를 쓰지만 충돌 후 내용·문의 버전을 고쳐 새로 제출하면 새 키를 쓴다. 원문·기존 티켓은 유지한다.

## 6. 범위 밖

AI 메시지 정정, 메시지 삭제(PRD·API에 삭제 없음), 담당자 화면에서의 정정 이력 표시(후속, 메시지↔티켓 연결 기준 미정).

## 7. 막히는 정도

- 목으로 진행 가능: 편집 UI, "정정됨"·이전 내용 표시, 충돌 처리.
- 구현·검증 상태: 위 확정 범위와 공통 계약 11절을 따른다. 후속 상세 설계는 QUESTIONS의 해당 ID로 추적한다.

## 공통 Mock 계약 (2026-10-23)

이 기능의 Mock 전송 데이터는 [공통 데이터 타입 문서](../contracts/DATA_TYPES.md)와 [요청·응답 연결표](../contracts/WIRE_CONTRACT.md#8-작업별-데이터-타입-연결)을 따른다. 공용 입력·응답·이벤트 예시는 [Mock 시나리오](../mocks/README.md)를 사용한다. Mock은 계약 예시의 일부이며 모든 확정 정책을 구현한 서버가 아니다. 팀 리뷰·서버 저장/권한·AI 품질 검증 완료를 뜻하지 않는다.
