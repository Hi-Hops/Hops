# API 설계 — PRD 1.44 공통 계약

상태: hops-system-1 목표 계약 · 코드 적용/통합 검증은 별도. 사용자 명령·조회는 React↔Spring REST, 공개 진행·변화는 Spring→React SSE, AI 실행/색인/검색 권한은 서비스 전용 HTTP를 사용한다. [공통 진입점](../specs/contracts/README.md), [결정 대장](../specs/QUESTIONS.md)을 따른다. [사례 복구·공개 중단 API 변경안](../specs/F011-case-lifecycle/RECOVERY_PROPOSAL.md)을 참고한다.

## 1. 정본과 책임

AI→Spring 내부 티켓 생성·조회·제한 재시도는 [Ticket 도구 REST 계약](../contracts/agents/TICKET_TOOLS.md)을 따른다. 공개 work-item API와 인증·호출 주체를 구분한다.

| 내용 | 정본 |
|---|---|
| 필드·자료형·필수/null·enum·요청 본문 | [DATA_TYPES 신규 절](../specs/contracts/DATA_TYPES.md#신규-계약-hops-system-1) |
| /api/v2 REST·오류·SSE·멱등/저장 확인 | [WIRE_CONTRACT12절](../specs/contracts/WIRE_CONTRACT.md#12-신규-계약-hops-system-1) |
| /internal/v2 실행·반영·권한 검증·State | [에이전트 계약](../contracts/agents/README.md#hops-system-1-실행-계약안) |
| 업무 전이·접근·공개 | [TARGET_CONTRACT](../specs/contracts/TARGET_CONTRACT.md) |
| 외부 ID와 물리 저장의 매핑 | [DATA](DATA.md) |

Spring이 현재 인증·권한·문의 버전·업무 저장·배정·해결 확인·승인·검색 공개를 확정한다. Python은 허용된 자료로 제안하고 업무 DB에 직접 쓰지 않는다. AI State와 Entity와 외부 DTO는 별도 객체다. 모델 응답·202 접수·진행 이벤트만으로 업무 성공을 표시하지 않는다.

인증·권한 문맥·모델 출력·검색·도구의 공통 요구사항은 [보안 개발 명세](SECURITY.md)를 따른다. 해당 SEC 요구사항의 구체 입출력·상태는 아래 연결 계약으로 정의하며 실제 구현·검증과 구분한다.

## 2. Frontend↔Spring

| 기능 | 결정된 동작 | 명세 |
|---|---|---|
| auth·시스템 | Bearer+Refresh cookie, 현재 사용자·허용 시스템·PRIMARY, 현재 권한 회수 | F001/F002 |
| 문의·답변·정정·문제 연결·시스템 선택 | 저장 ack·입력 버전·원문/revision, 5회 질문·대기 보존, 문제별 보완 대상 연결 | F003/F004/F008 |
| 문제별 접수 결과·복구 | 결과 revision 모달, STORED/NOT_CREATED/UNKNOWN 구분, 같은 논리 작업 선확인 | F005/F010 |
| 목록·상세·관련 대화·처리·정정 | 현재 PRIMARY 범위, 관리자 제한 DTO, 실제 조치/검증, ticketRevision 충돌 | F007/F009 |
| 문의자 해결 확인 | 원 처리 기록/가이드별 확인 요청·한 응답, 문제별 미해결/대기/최종 완료 | F003/F006 |
| 사례·승인·반영 상태 | 불변 사례 버전·해결 확인·현재 담당자 승인, 실패 반영 수동 실행 | F011 |

공통 receipt·작업/키 결과 조회·페이지·SSE 재동기화도 WIRE12절에 정의한다. 관리자는 메타데이터/정정만, 문의자는 본인 결과만, 현재 담당자는 관련 업무와 허용 원문 구간만 받는다. 조회 실패를 빈 결과로, timeout을 DB 미저장으로 바꾸지 않는다.

채팅은 [WIRE12.8절](../specs/contracts/WIRE_CONTRACT.md#128-채팅-송수신-완료-규칙)의 입력 저장→실행 접수→임시 SSE→최종 저장→REST 병합 순서를 사용한다. 상담 run당 Spring이 예약한 answerMessageId의 AI 메시지 하나를 저장하며 공개 Run 성공은 답변·질문/가이드 상태 commit 뒤 반환한다. SSE 유실이나 조각 없는 성공도 같은 REST 조회로 완료한다.

## 3. Spring↔AI

상담/Curator는 `/internal/v2/runs`, 사례 저장소 반영은 `/internal/v2/index-jobs`, AI 검색 후보의 최신 권한 검증은 Spring의 `/internal/v2/knowledge/authorizations`로 구분한다. 모든 내부 호출은 서비스 Bearer·고정 origin과 정해진 타입을 사용하고 사용자 JWT/쿠키를 전달하지 않는다.

Spring은 기존 저장 결과·원문·현재 입력/정책 버전·실행 기한과 누적 한도를 제공한다. AI는 구조화된 제안을 반환하며 Spring이 현재 버전·실제 출처·지원 시스템·현재 PRIMARY를 검증해 적용한다. 같은 runId·다른 결과는 거부한다. 문서/CASE 후보는 승인·공개·ACL 검증 전에 모델 입력으로 넣지 않는다.

승인 후 반영은 같은 caseId/caseVersion/store/jobId/attempt/digest를 대조한다. 다른 버전·오래된 attempt·결과 미확인으로 공개 gate를 열지 않는다. 두 저장소 성공을 DB에서 확인한 뒤 검색 반환 시에도 현재 권한을 다시 검사한다.

## 4. 값과 완료 상태

Vector 저장·RunLedger·개발 origin·평가 게이트는 [결정 대장](../specs/QUESTIONS.md#실행검색-기본값)을 따른다. 모델 선정·단가·예상 비용·금액 예산과 구체 실행 설정값은 이 계약에서 제외한다. 공용 도메인·키·비밀번호는 배포 환경 입력이며 계약을 비워 둔 항목이 아니다. 새 공급자·유료 관리형 인프라로 자동 대체하지 않는다.

현재 프론트는 `/api/v2`·`hops-system-1` DTO·Mock을 사용한다. Spring도 인증·부서 SSE의 공개 경로는 `/api/v2`이며, 시스템 권한 DTO·업무 API·시스템/사례 SSE는 구현·통합 검증이 남아 있다. Python은 실행 wire 검증·서비스 인증·저장된 실행 조회와 파이프라인 주입 시 접수·백그라운드 실행을 제공한다. 실제 역할·모델·검색 파이프라인과 실행 SSE는 미연결이며 옛 agent-1 실행 API는 제거됐다. 문서/JSON 검사·Mock·실제 저장/권한·실제 모델/검색·브라우저 E2E를 분리해 기록한다.


## 5. 병합 ERD 반영

[PR #69](https://github.com/Hi-Hops/Hops/pull/69)의 물리 ERD를 기준으로 [DATA11절](DATA.md#11-완성-erd-대조-결과와-병합-기준)에 상태/버전·원문/표준 요청·CASE/색인·실행/명령 매핑을 확정했다. 현재 PRIMARY DTO는 관계 ID 없이 반환하고 처리 기록의 FAILED_ATTEMPT·확인된 원인/적용 버전·CASE 문제 설명을 포함한다. 저장 보완은 ERD6절의 공통 계약 설계다. 프론트는 신규 계약으로 전환됐으며 서버의 업무 DTO·저장·권한 연결은 별도다. 전환 전 계약은 기록으로 보존한다.

담당자의 실제 장애 확정은 POST /api/v2/tickets/{ticketId}/processing-type-corrections로 INVESTIGATION↔INCIDENT만 변경한다. 자동 AI 확정과 관리자 역할만으로의 변경은 허용하지 않으며 상태·확인 요청과 분리한다.
