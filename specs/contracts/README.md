# 공통 데이터 계약

상태: `hops-mock-1` 기반 · 2026-10-25 개발 기준 확정 / 팀 리뷰·서비스 구현 검증 별도. 2026-10-27 칸반 MVP 추가 프로필은 팀 리뷰 대상 계약 확장안이며 구현·검증 별도다.

인증·상태·오류·재시도·AI 초기 예산·MVP 범위의 확정 개발 기준은 [WIRE_CONTRACT 10절](WIRE_CONTRACT.md#10-인증재접속최신-대화분석-재시도-추가-프로필)에 모은다. [11절](WIRE_CONTRACT.md#11-담당별-구현과-검증-상태)은 담당별 구현 작업과 미검증 항목이다. 정책 선택과 실제 서비스 구현·팀 리뷰/병합을 구분한다. 공통 폴더에서 언어별 코드나 DTO를 생성·배포하지 않는다.

프론트는 Mock과 live 모드에서 같은 JSON 계약을 사용한다. 서버 담당자는 [REST·이벤트 연결표와 live 프로필](WIRE_CONTRACT.md#9-프론트-live-연동-프로필), 실행 담당자는 [프론트 환경 설정](../../frontend/README.md)을 확인한다. live 통신 어댑터 제공과 팀원 서버의 구현·통합 검증 완료는 구분한다.

- [데이터 타입과 필드](DATA_TYPES.md): 공통 기본 타입·값 목록·객체 필드·필수/nullable·제약.
- [요청·응답과 전송 규칙](WIRE_CONTRACT.md): REST·Socket·AI 입출력 연결, 권한, 오류, ACK·재전송·버전·순서.
- [Mock 데이터](../mocks/README.md): 화면과 테스트에서 사용하는 JSON 예시. 실행용 로그인 값은 frontend/.env에만 작성한다.

## 확정 기준과 남은 담당 작업

정확한 필드·enum은 DATA_TYPES, 요청/응답·전이·오류 행동은 WIRE_CONTRACT가 원본이다. 로그인·Refresh·구독/ACK·문의 버전·1:N 작업/티켓·멱등 정책은 개발 기준으로 확정했다. 추가 OpenAPI/JSON Schema는 MVP 연결의 필수 선행 조건이 아니다.

백엔드는 REST/DB/인증, Node+백엔드는 내부 검증·소켓/서비스 인증, AI+백엔드는 실행 경계·예산/결과 검증을 구현한다. 프론트의 새 제어 이벤트·일부 상태/429 UI도 남아 있다. 실제 키·주소·호환 버전·모델/SDK·금액 상한·DDL은 담당 설계 대상으로 남기며 11절에서 통합 검증과 구분한다.

질문의 ID와 #22/#38 연결, 후속 상세 설계는 [QUESTIONS](../QUESTIONS.md)를 따른다. 선택한 기준을 같은 질문으로 다시 묻지 않는다.

## 스펙별 사용 타입

- F001: LoginRequest, LoginResponse, CurrentUser, Department, Role, ApiErrorBody.
- F002: SubscriptionTarget, SubscriptionCommand, SubscriptionAck, AgentProgressEvent, AnswerDeltaEvent, MessageStoredEvent, StateChangedEvent, AuthInvalidatedEvent, RelayStatusEvent.
- F003: InquiryCreateCommand, MessageSendCommand, CommandAck, ChatMessage, MessageHistoryQuery, MessageHistoryPage, InquiryResult, RunSummary, RunRetryRequest. 기존 MessagePage는 전방 조회 호환 예제다.
- F004: MessageReviseCommand, CommandAck, ChatMessage, MessageRevision, InquiryResult.
- F005: InquiryResult, Problem, TicketCard, CreationFailure, RunSummary.
- F006 재진입용 최소 목록: InquiryListQuery, InquiryPage, InquirySummary. 독립 목록·상세 모달은 후속이다.
- F009: TicketListQuery, TicketPage, TicketCard, TicketDetail, AssignmentHistory, Department. 칸반 MVP 추가 프로필은 TicketListItem, TicketRequester, TicketPerson, TicketGroupSummary, TicketPriority, PrioritySource와 다중 상태·검색·빠른 필터 쿼리를 확장한다. 팀 리뷰·서비스 구현 검증 별도이며 기존 문의 결과용 TicketCard 기본 계약은 유지한다.
- 서비스 AI 연결: AiRequest, AiAccepted, AiProgress, AiDelta, AiResult와 각 하위 타입. 기존 내부 agent-1 계약을 교체하지 않는다.

F007의 읽기 전용 티켓 조회는 MVP다. 별도 관리자 요약·F008 분리 동의·F010 운영 기술 복구 UI는 후속이며 상세 입출력은 해당 기능 착수 전에 설계한다.

각 타입의 정확한 필드와 상태값은 데이터 타입 문서의 같은 제목에서 확인한다. 각 스펙에서도 사용하는 타입을 연결한다. 조회된 티켓은 서버 저장 결과이고 AI 제안은 업무 저장 성공이 아니다.

## 관리 원칙

필드명은 서비스 간 JSON에서 camelCase로 맞춘다. 필수 여부·null·상태·버전·오류 의미를 각 담당 언어에서 유지하되 내부 클래스·DB 자료형까지 통일하지 않는다. 계약 변경은 문서·영향받는 스펙·Mock 예시와 각 서비스 테스트를 함께 리뷰한다. 언어별 자동 생성/동기화 도구는 사용하지 않는다.

관련 Q-BE·Q-INF·Q-AI·Q-ALL 미정 사항은 [QUESTIONS](../QUESTIONS.md)에 남긴다. 이 문서는 운영 정책 승인이나 실제 서비스 연동 완료를 뜻하지 않는다. 제품 정책은 [PRD](../../docs/PRD.md), 역할 경계는 [API](../../docs/API.md)·[DATA](../../docs/DATA.md)를 따른다.
