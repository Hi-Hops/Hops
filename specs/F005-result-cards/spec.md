# F005 분석 결과·복수 티켓 정보 카드·부분 실패

상태: 초안 · 범위: MVP · 담당: frontend(화면) · 협의: backend(저장·상태), ai(문제 분리·티켓 제안)
근거: PRD 3.1, 4.4, 7.1, 8.2 · API 2.1(문의 결과 최소 표현) · DATA 1, 4 · ERD `inquiry_problem`, `ticket`, `ticket_assignment`

## 1. 목표

한 문의 안의 여러 문제를 나눠, 문제마다 "추가 질문 중 / 티켓 생성·부서 배정됨 / 생성 실패 / 범위 밖"을 구분해 보여 준다. 일부가 실패해도 성공한 티켓은 그대로 보여 준다. 저장된 티켓만 카드로 보여 준다. (PRD 3.1, 4.4, 7.1)

## 2. 화면

채팅(`/inquiries/:inquiryId/chat`) 안의 결과 영역. 같은 데이터를 F006 문의 상세에서도 쓴다.

## 3. 사용자 시나리오

1. "VPN이 안 되고, 사내 포털 권한도 필요해요"를 보내면 문제 두 개로 나뉜다.
2. VPN 문제는 네트워크 부서 티켓 카드로, 권한 문제는 추가 질문 중으로 보인다.
3. 권한 문제의 티켓 생성이 실패하면 VPN 카드는 그대로 있고, 권한 문제는 "생성 실패"로 보인다.

## 4. 인수 조건

- **F005-AC01 문제별 구분**
  문의 결과의 `problems[]`를 문제마다 한 줄로 보여 주고, 상태를 문구와 색으로 함께 표시한다. 하나의 문의 상태로 문제별 상태를 덮어쓰지 않는다. (API 2.1 · PRD 4.11 · 상태값 Q-ALL-2)
- **F005-AC02 티켓 카드**
  `tickets[]`의 저장된 티켓만 카드로 보여 준다. 카드에는 제목, 처리 부서, 처리 상태, 배정 상태, 생성 시각을 표시한다. 실행이 끝났다는 이벤트만으로 카드를 만들지 않는다. (API 2.1 · DATA 1 · 티켓 번호 표시 Q-BE-12)
- **F005-AC03 처리 상태와 배정 상태 분리**
  `processingStatus`와 `routingStatus`를 따로 표시한다. CS 검토 티켓은 배정 상태로 구분해 보여 준다. (API 2.1 · ERD `ticket.ticket_status`, `routing_status`)
- **F005-AC04 부분 실패**
  `creationFailures[]`가 있으면 해당 문제를 "티켓을 만들지 못했어요"로 표시하고, 성공한 카드는 그대로 둔다. (PRD 3.1, 4.4 · API 2.1)
- **F005-AC05 실패 항목 안내**
  생성 실패 항목은 문의자에게 상태 안내만 제공하며 이번 Mock에는 기술 재시도 버튼을 두지 않는다. 실행 재시도와 작업 저장 재시도를 구분하고 운영 권한은 Q-BE-8 답을 따른다. 관리자 쪽 재시도와 복구 완료 표시는 [F010](../F010-creation-failures/spec.md)에서 다룬다. (API 2.1 "기술 재시도 권한은 운영 관리자" · Q-BE-8)
- **F005-AC06 승인 조작 없음**
  티켓 생성·배정에 사용자 승인 버튼을 두지 않는다. (PRD 3.1, 4.4)
- **F005-AC07 중복 없음**
  같은 요청을 다시 보내거나 이벤트를 두 번 받아도 같은 티켓 카드를 두 번 보여 주지 않는다. 카드는 티켓 `id`로 구분한다. (PRD 3.1 · DATA 5)
- **F005-AC08 갱신 방식**
  `state.changed`를 받거나 재접속하면 `GET /api/inquiries/{id}`로 다시 조회해 카드를 맞춘다. (API 2.4 · PRD 5.1)
- **F005-AC09 현재 실행**
  `currentRun`이 진행 중이면 결과 영역에 "분석 중"을 함께 표시해, 아직 나올 결과가 있음을 알린다. (API 2.1)

## 5. 계약(화면이 기대하는 값)

공통 필드·필수/nullable·wire 표현의 원본은 [JSON Schema와 필드 표](../contracts/README.md)다. 아래는 이번 Mock 프로필의 연결표이며 팀 승인·운영 미정 질문의 확정을 뜻하지 않는다. 기존 API의 최소 의미를 구체화한 제안이다.

| 용도 | 공통 Mock 타입 | 관계·필수 표시 | 미정 추적 |
|---|---|---|---|
| 문의 상세 결과 | InquiryResult | id/version/status/problems/tickets/creationFailures/currentRun(정확한 키는 Schema) | Q-BE-3/4 |
| 문제 | Problem | id, category(nullable), status, questionCount | Q-ALL-2 |
| 저장 티켓 | TicketCard | inquiryId/problemId/workItemId, ticketNumber, department, processingStatus/routingStatus, createdAt | Q-BE-4/12 |
| 생성 실패 | CreationFailure | workItemId/problemId로 성공 티켓과 구분 | Q-BE-4/8 |
| 현재 실행 | RunSummary 또는 null | 실행 성공은 작업 저장 성공이 아님 | Q-BE-8 |

문제 하나에 여러 티켓이 연결될 수 있다. 공통 fixture의 같은 problemId에 서로 다른 부서/작업 ID의 티켓 2개를 포함한다. 기존 ERD의 1:1 제약을 화면에서 강제하지 않는다. 배정·문제·실행 상태의 운영 코드는 미정이며 Mock 대표값과 미등록값 fallback을 사용한다. 생성 실패에 문의자 기술 재시도 버튼을 두지 않는다.

## 6. 문제 상태 (표시 기준, 값 이름은 미정)

```text
분석 중 → 추가 질문 중(최대 3회) → 티켓 생성·배정됨
                                → CS 검토 티켓(3회 후 판별 어려움)
        → 범위 밖(티켓 없음)
        → 티켓 생성 실패(성공한 다른 문제는 유지)
```

## 7. 범위 밖

해결 신고·해결 확인(후속), 담당자용 티켓 상세로 이동(문의자 화면에서 제공하지 않음, PRD 4.5), 무티켓 해결 확인(후속 8.3).

## 8. 막히는 정도

- 목으로 진행 가능: 카드·문제 목록·부분 실패 표시 전부. API 2.1 최소 표현으로 목 데이터를 만든다.
- 결정 전 구현 보류: 상태 코드와 표시명(Q-ALL-2), 문제·티켓 관계(Q-BE-4).

## 공통 Mock 계약 (2026-10-23)

이 기능의 Mock 전송 데이터는 [공통 Schema·생성 DTO](../contracts/README.md)와 [operation 목록](../contracts/operations.json)을 따른다. 공용 입력·응답·이벤트 예시는 [Mock 시나리오](../mocks/README.md)를 사용한다. 이 절은 Mock 개발용 제안이며 기존 계약 표의 미정 항목이나 팀 승인 상태를 확정으로 바꾸지 않는다. 서버 저장·권한·AI 품질 완료를 뜻하지 않는다.
