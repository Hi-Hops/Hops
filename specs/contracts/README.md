# 공통 데이터 계약

상태: `hops-mock-1` · Mock 개발용 초안 / 팀 리뷰 전.

2026-10-25 추가 개발 프로필은 [WIRE_CONTRACT 10절](WIRE_CONTRACT.md#10-인증재접속최신-대화분석-재시도-추가-프로필)에 모은다. refresh·최신 30개·분석 재시도의 프론트/Mock과 실제 서버 구현을 구분하며 기존 미결 질문을 팀 승인 없이 완료 처리하지 않는다.

다음 문서는 프론트·백엔드·Node 채팅 서버·AI가 타입과 검증을 협의하기 위한 개발용 계약안이다. 현재 Mock·임시 Live Adapter는 이 표현을 사용하지만 다른 담당자의 최종 구현이나 운영 계약을 확정하지 않는다. 공통 폴더에서 언어별 코드나 DTO를 생성·배포하지 않는다.

프론트는 Mock과 live 모드에서 같은 JSON 계약을 사용한다. 서버 담당자는 [REST·이벤트 연결표와 live 프로필](WIRE_CONTRACT.md#9-프론트-live-연동-프로필), 실행 담당자는 [프론트 환경 설정](../../frontend/README.md)을 확인한다. live 통신 어댑터 제공과 팀원 서버의 구현·통합 검증 완료는 구분한다.

- [데이터 타입과 필드](DATA_TYPES.md): 공통 기본 타입·값 목록·객체 필드·필수/nullable·제약.
- [요청·응답과 전송 규칙](WIRE_CONTRACT.md): REST·Socket·AI 입출력 연결, 권한, 오류, ACK·재전송·버전·순서.
- [Mock 데이터](../mocks/README.md): 화면과 테스트에서 사용하는 JSON 예시. 실행용 로그인 값은 frontend/.env에만 작성한다.

## 팀 합의 전 항목

현재 구현이 값을 선택해 사용한다는 사실과 팀이 계약을 승인했다는 사실을 구분한다. 실제 팀원 서버 통합 전에 아래 항목을 담당자 간 확인하고, 합의한 내용과 근거를 원본 계약·질문 문서에 반영한다. 확인하지 않은 질문을 완료 처리하지 않는다.

- 로그인 응답과 토큰 저장·만료·갱신 정책: Q-BE-1/2.
- Socket.IO 이벤트·명령명, ACK 필드·시간 기준과 구독 대상/권한: Q-INF-4/5/6, Q-BE-10.
- 문의 버전과 정정 충돌 처리: Q-BE-3.
- 상태 enum·표시 의미와 오류 코드: Q-ALL-2/4.
- 문의와 티켓의 1:N 연결 및 조회 응답: Q-BE-4.
- 멱등성 키, 메시지·티켓·저장 결과의 식별자 연결과 재전송: Q-BE-3/10, Q-INF-6.

질문의 현재 판정과 #22·#38 연결은 [합의 질문 목록](../QUESTIONS.md)을 따른다. JWT·Socket.IO 채택처럼 이미 PRD에 반영된 기술 선택과 위 상세 계약의 미합의 상태를 혼동하지 않는다.

## 스펙별 사용 타입

- F001: LoginRequest, LoginResponse, CurrentUser, Department, Role, ApiErrorBody.
- F002: SubscriptionTarget, SubscriptionCommand, SubscriptionAck, AgentProgressEvent, AnswerDeltaEvent, MessageStoredEvent, StateChangedEvent.
- F003: InquiryCreateCommand, MessageSendCommand, CommandAck, ChatMessage, MessageHistoryQuery, MessageHistoryPage, InquiryResult, RunSummary, RunRetryRequest. 기존 MessagePage는 전방 조회 호환 예제다.
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
