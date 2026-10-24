# 공통 데이터 계약

상태: `hops-mock-1` · Mock 개발용 초안 / 팀 리뷰 전.

프론트·백엔드·Node 채팅 서버·AI 담당자는 다음 문서를 기준으로 자기 서비스의 타입과 검증을 구현한다. 공통 폴더에서 언어별 코드나 DTO를 생성·배포하지 않는다.

- [데이터 타입과 필드](DATA_TYPES.md): 공통 기본 타입·값 목록·객체 필드·필수/nullable·제약.
- [요청·응답과 전송 규칙](WIRE_CONTRACT.md): REST·Socket·AI 입출력 연결, 권한, 오류, ACK·재전송·버전·순서.
- [Mock 데이터](../mocks/README.md): 화면과 테스트에서 사용하는 JSON 예시. 실행용 로그인 값은 frontend/.env에만 작성한다.

## 스펙별 사용 타입

- F001: LoginRequest, LoginResponse, CurrentUser, Department, Role, ApiErrorBody.
- F002: SubscriptionTarget, SubscriptionCommand, SubscriptionAck, AgentProgressEvent, AnswerDeltaEvent, MessageStoredEvent, StateChangedEvent.
- F003: InquiryCreateCommand, MessageSendCommand, CommandAck, ChatMessage, MessagePage, InquiryResult, RunSummary.
- F004: MessageReviseCommand, CommandAck, ChatMessage, MessageRevision, InquiryResult.
- F005: InquiryResult, Problem, TicketCard, CreationFailure, RunSummary.
- F006 재진입용 최소 목록: InquiryListQuery, InquiryPage, InquirySummary. 전체 F006 계약 확정을 뜻하지 않는다.
- F009: TicketListQuery, TicketPage, TicketCard, TicketDetail, AssignmentHistory, Department.
- 서비스 AI 연결: AiRequest, AiAccepted, AiProgress, AiDelta, AiResult와 각 하위 타입. 기존 내부 agent-1 계약을 교체하지 않는다.

F007 관리자 전용 요약, F008 새 문의 분리 동의, F010 기술 복구의 미정 입출력은 이번 문서에서 확정하지 않는다. 해당 스펙의 합의 질문을 따른다.

각 타입의 정확한 필드와 상태값은 데이터 타입 문서의 같은 제목에서 확인한다. 각 스펙에서도 사용하는 타입을 연결한다. 조회된 티켓은 서버 저장 결과이고 AI 제안은 업무 저장 성공이 아니다.

## 관리 원칙

필드명은 서비스 간 JSON에서 camelCase로 맞춘다. 필수 여부·null·상태·버전·오류 의미를 각 담당 언어에서 유지하되 내부 클래스·DB 자료형까지 통일하지 않는다. 계약 변경은 문서·영향받는 스펙·Mock 예시와 각 서비스 테스트를 함께 리뷰한다. 언어별 자동 생성/동기화 도구는 사용하지 않는다.

관련 Q-BE·Q-INF·Q-AI·Q-ALL 미정 사항은 [QUESTIONS](../QUESTIONS.md)에 남긴다. 이 문서는 운영 정책 승인이나 실제 서비스 연동 완료를 뜻하지 않는다. 제품 정책은 [PRD](../../docs/PRD.md), 역할 경계는 [API](../../docs/API.md)·[DATA](../../docs/DATA.md)를 따른다.
