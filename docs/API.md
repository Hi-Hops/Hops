# API 설계 — PRD 1.44 공통 계약

상태: PR #68 제출 결정안 · 필드/경로/상태/예산 값 작성 완료 · 팀 승인/코드 적용/통합 검증은 별도. 사용자 명령·조회는 React↔Spring REST, 공개 진행·변화는 Spring→React SSE, AI 실행/색인/검색 권한은 서비스 전용 HTTP를 사용한다. [공통 진입점](../specs/contracts/README.md), [결정 대장](../specs/QUESTIONS.md)을 따른다.

## 1. 정본과 책임

| 내용 | 정본 |
|---|---|
| 필드·자료형·필수/null·enum·요청 본문 | [DATA_TYPES 신규 절](../specs/contracts/DATA_TYPES.md#신규-계약-hops-system-1) |
| /api/v2 REST·오류·SSE·멱등/저장 확인 | [WIRE_CONTRACT12절](../specs/contracts/WIRE_CONTRACT.md#12-신규-계약-hops-system-1) |
| /internal/v2 실행·반영·권한 검증·State | [에이전트 계약](../contracts/agents/README.md#hops-system-1-실행-계약안)·[wire JSON Schema](../contracts/agents/hops-system-1.schema.json) |
| 업무 전이·접근·공개 | [TARGET_CONTRACT](../specs/contracts/TARGET_CONTRACT.md) |
| 외부 ID와 물리 저장의 매핑 | [DATA](DATA.md) |

Spring이 현재 인증·권한·문의 버전·업무 저장·배정·해결 확인·승인·검색 공개를 확정한다. Python은 허용된 자료로 제안하고 업무 DB에 직접 쓰지 않는다. AI State와 Entity와 외부 DTO는 별도 객체다. 모델 응답·202 접수·진행 이벤트만으로 업무 성공을 표시하지 않는다.

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

## 3. Spring↔AI

상담/Curator는 `/internal/v2/runs`, 사례 저장소 반영은 `/internal/v2/index-jobs`, AI 검색 후보의 최신 권한 검증은 Spring의 `/internal/v2/knowledge/authorizations`로 구분한다. 모든 내부 호출은 서비스 Bearer·고정 origin과 정해진 타입을 사용하고 사용자 JWT/쿠키를 전달하지 않는다.

Spring은 기존 저장 결과·원문·현재 입력/정책 버전·모델 profile과 누적 예산을 제공한다. AI는 구조화된 제안을 반환하며 Spring이 현재 버전·실제 출처·지원 시스템·현재 PRIMARY를 검증해 적용한다. 같은 runId·다른 결과는 거부한다. 문서/CASE 후보는 승인·공개·ACL 검증 전에 모델 입력으로 넣지 않는다.

승인 후 반영은 같은 caseId/caseVersion/store/jobId/attempt/digest를 대조한다. 다른 버전·오래된 attempt·결과 미확인으로 공개 gate를 열지 않는다. 두 저장소 성공을 DB에서 확인한 뒤 검색 반환 시에도 현재 권한을 다시 검사한다.

## 4. 값과 완료 상태

모델·임베딩·Vector·기한/토큰/비용·RunLedger·개발 origin·평가 게이트는 [결정 대장](../specs/QUESTIONS.md#실행검색-기본값)에 값이 기입돼 있다. 공용 도메인·키·비밀번호는 배포 환경 입력이며 계약을 비워 둔 항목이 아니다. 새 공급자·유료 관리형 인프라로 자동 대체하지 않는다.

현재 /api·hops-mock-1·agent-1의 코드/Mock은 그대로 보존한다. 신규 경로와 DTO가 실제 서버에 등록됐다는 뜻은 아니다. 문서/JSON 검사·Mock·실제 저장/권한·실제 모델/검색·브라우저 E2E를 분리해 기록한다.
