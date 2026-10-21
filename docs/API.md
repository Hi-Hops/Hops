# API 설계 — PRD 전체 흐름

기준: [PRD](PRD.md) v1.41 · 2026-10-21, [데이터 설계](DATA.md), [ERD 설명](ERD_설명.md). 이 문서는 MVP 연결부터 최종 목표까지의 **팀 리뷰용 계약안**이다. 경로·필드·상태 코드는 구현 전 해당 기능의 담당자가 합의하고 OpenAPI/JSON Schema와 계약 테스트로 고정한다. 표의 `MVP`는 PRD 8.2, `후속`은 8.3의 필수 범위다. API가 이미 구현되었다는 뜻은 아니다.

## 1. 공통 계약과 책임

- React는 로그인·조회·티켓 업무 REST를 Spring에 요청하고 채팅 명령·실시간 수신은 Node.js Socket.IO 서버에 연결한다. Spring도 Socket.IO 클라이언트로 연결해 명령을 수신하고 결과를 발행한다. Node.js는 연결·구독·중계, Spring은 JWT 발급·인증·권한·문의 버전·업무 저장·배정·상태 확정을 담당한다. Python은 Spring이 제공한 권한 범위에서 분석·검색·읽기 전용 진단을 수행하고 제안과 근거를 반환한다. Node.js와 Python은 업무 DB에 직접 쓰지 않는다.
- JSON은 UTF-8, API 필드는 camelCase, 식별자는 문자열 UUID, 시각은 UTC RFC 3339 문자열로 표현한다. 목록은 `{items, page, size, totalElements}`를 제안한다. 페이지 0부터, 기본 20건·최대 100건은 구현 전 확정한다.
- 자체 로그인은 Spring이 발급하는 JWT로 인증한다. REST Bearer 전달과 Socket.IO 연결의 `auth.token` 전달을 계약안으로 두며, 브라우저 보관·쿠키 사용·갱신 방식과 그에 따른 CSRF 보호 범위는 인증 구현 전에 확정한다. 서버는 서명·허용 알고리즘·발급자·대상·만료를 검증하고 역할·소유자·현재 처리 부서도 검사한다. 로그인 인증 수명과 문의의 대화 세션은 별개다. JWT 원문·비밀번호·서명키는 로그·이벤트 본문에 남기지 않는다.
- REST 업무 변경은 `Idempotency-Key`, Socket.IO 명령은 `requestId`와 업무 식별자로 중복 반영을 막는다. 같은 행위자·동작·키·같은 요청의 재전송은 기존 결과를 반환하고 같은 키·다른 요청은 충돌이다. REST 충돌은 409, Socket.IO 충돌은 명시적 오류 ACK로 반환한다. 낙관적 버전 충돌도 같은 원칙을 따른다. `202 Accepted`나 실행 접수 ACK는 접수만 뜻하며 업무 성공 상태는 저장 결과·조회로 확인한다.
- 사용자 메시지는 공백을 포함해 1~4,000 Unicode 코드 포인트이며 공백만 있는 입력은 거부한다. 초과 입력과 공백 입력은 저장하지 않고 원문을 유지해 수정할 수 있게 한다. 범위 밖 질문·분류 실패·모델/통신 장애·업무 저장 실패는 서로 다른 결과로 표현한다.
- 오류 외피 제안: `{ "code": "INQUIRY_VERSION_CONFLICT", "message": "최신 내용을 다시 불러와 주세요.", "requestId": "<UUID>", "retryable": false }`. 400(입력), 401(로그인), 403(권한), 404(없음), 409(충돌), 429(제한), 503(일시 장애)를 구분한다. 내부 예외·비밀값·프롬프트·모델 내부 사고 과정은 응답하지 않는다. 비동기 실행 실패는 원래 HTTP 응답을 수정하지 않고 실행·복구 상태에 기록한다.

## 2. 화면 → Spring

아래 REST 경로는 논의용 초안이다. 채팅 명령은 2.4절의 Socket.IO로 전달하고 목록·상세의 영속 상태와 티켓 업무는 REST로 제공한다. 각 변경 요청의 필수 식별자·버전·멱등 범위·권한·오류와 성공/부분 성공 응답을 기능 구현 전에 고정한다. `GET`은 조회 권한과 데이터 범위를 서버가 강제한다.

### 2.1 MVP: 로그인·문의·분석·복수 티켓

| 경로 | 요청·응답의 최소 의미 | 권한·완료 조건 |
|---|---|---|
| `POST /api/auth/login` | `{loginId,password}` → 액세스 JWT·만료 정보·사용자 정보 | Spring이 계정 정보에서 사번·소속 부서를 읽어 발급. 요청자가 제출한 부서·역할로 발급하지 않음. 응답 필드·갱신 토큰 도입 여부는 인증 계약에서 확정 |
| `GET /api/auth/me`, `POST /api/auth/logout` | 검증된 사용자 정보 조회, 로그아웃 | 토큰 만료/무효는 401, 권한 부족은 403. 로그아웃은 브라우저 인증 정보 정리와 소켓 종료를 포함. 서버 측 폐기 범위·반영 시점·갱신 API·쿠키 사용 시 CSRF 경로는 구현 전 확정 |
| `GET /api/departments` | 활성 부서의 `{id,code,name}` | 인증된 사용자에게 기준 목록 제공. 부서 코드는 [DATA](DATA.md)의 카탈로그와 일치 |
| `GET /api/inquiries`, `GET /api/inquiries/{id}` | 본인 문의 목록·종합 상태, 상세의 문제별 상태·실제 티켓·생성 실패·현재 실행 | 타인 문의 거부. 상세 모달에 채팅 로그를 포함하지 않음. 숨김 여부는 개인 표시 상태 |
| `GET /api/inquiries/{id}/messages` | 대화순 메시지와 정정 이력 | 문의자는 자신의 대화 조회. 페이지가 바뀌어도 순서 안정적 |
| `GET /api/runs/{id}`, `POST /api/runs/{id}/retries` | 실행 상태·오류·작업별 생성 결과 조회, 실패/시간 초과 실행 재시도 | 실행 성공과 티켓 저장 성공 분리. 최신 문의 버전과 기존 성공 작업을 재검증 |
| `GET /api/tickets`, `GET /api/tickets/{id}` | 현재 배정 부서·개인 및 업무 관리자 범위의 목록과 티켓 상세 | 목록 필터가 권한을 넓히지 않음. `processingStatus`와 `routingStatus`를 분리. 저장된 티켓만 카드에 표시 |
| `GET /api/creation-failures` | 아직 티켓이 없는 생성 실패 항목과 기존 작업 ID | 업무 관리자·운영 관리자에게 각 역할에 맞는 정보만 제공. 기술 재시도 권한은 운영 관리자 |

문의 생성·메시지 추가·정정·새 문의 분리 동의는 2.4절의 이벤트를 사용한다. 서버가 후보 문맥과 출처를 제시하고 사용자 동의를 확인한 경우에만 새 문의·세션을 한 번 생성한다. 거절·무응답은 생성하지 않는다. 이 동의는 기존 문의의 티켓 자동 생성에 적용되지 않는다. 동의 대상 ID·버전·상태는 구현 전 확정한다.

문의 결과의 최소 표현은 `id, version, status, problems[], tickets[], creationFailures[], currentRun`이다. 문제는 `id, summary, category, status, questionCount`, 티켓 카드는 `id, inquiryId, title, department, processingStatus, routingStatus, createdAt`, 실패 항목은 `workItemId, problemId, status, errorCode`를 제안한다. 일부 문제는 추가 질문 중이고 다른 티켓은 성공/실패일 수 있다. 단일 문의 상태로 이를 덮어쓰지 않는다.

### 2.2 후속: 담당자 처리·관리자·그룹·알림

| 경로 초안 | 요청·결과 | 서버 검사 및 상태 의미 |
|---|---|---|
| `GET /api/tickets/{id}/messages` | 해당 티켓에 관련된 대화·보완 정보·정정 이력 | 현재 처리 부서 구성원은 가져오기 전에도 조회 가능. 다른 문제의 대화와 운영 로그 제외. 메시지↔문제/티켓 연결 기준은 구현 전 확정 |
| `POST /api/tickets/{id}/claim`, `POST /api/tickets/{id}/return` | 개인 담당자 가져오기·반환 | 현재 부서 구성원만. 동시 가져오기는 한 명만 성공. 반환 전 다른 담당자 가져오기 거부 |
| `POST /api/tickets/{id}/handler-completions`, `POST /api/tickets/{id}/resolution-reports`, `POST /api/tickets/{id}/confirmations` | 담당자 처리 완료, 처리 중 사용자 해결 신고, 사용자 최종 해결/미해결 응답 | 처리 완료는 사용자 확인 대기. 선행 해결 신고는 담당자 작업 확인 필요. 미해결은 해당 티켓 재처리. 완료 응답은 다른 티켓을 일괄 완료하지 않음 |
| `POST /api/tickets/{id}/handoff-requests`, `GET /api/handoff-requests/{id}` | 이유를 붙인 재이관 요청·진행 조회 | 현재 담당자 권한, 제외 부서 이력, 관리자 수동 배정 여부 확인. 후보 소진은 배정 예외, 기술 실패는 복구 필요로 구분 |
| `POST /api/tickets/{id}/corrections`, `POST /api/tickets/{id}/manual-assignments` | AI 요약·분류·진단 정정, 업무 관리자 수동 배정 | 정정 전후·주체·사유 기록. 정정만으로 완료/재배정 불가. 수동 배정은 업무 관리자만 확정 |
| `GET /api/admin/routing-exceptions`, `POST /api/admin/routing-exceptions/{id}/decisions` | 배정 예외·관리자 재검토와 결정 | 업무 관리자만 업무 판단 변경. 생성 실패는 별도 항목으로 노출 |
| `GET /api/incident-groups/{id}`, `POST /api/incident-groups/{id}/completions`, `POST /api/incident-groups/{id}/rejoin-reviews/{reviewId}/decisions` | 그룹 근거·구성 조회, 공통 장애 일괄 완료, 분리 티켓 재결합 검토 | 같은 부서·미완료·공통 해결 근거를 검증. 일괄 반영은 티켓별 성공/실패 표시. 미해결 재문의는 해당 티켓만 분리·재오픈. 재결합은 업무 관리자 확인 |
| `GET /api/notifications`, `GET /api/statistics` | 본인 알림함, 허용 부서의 이관·상태·AI 해결·무티켓 해결 집계 | 알림 읽음은 해결 확인이 아님. 통계 범위가 원문 조회 권한을 넓히지 않음. 빈 결과·조회 실패 구분 |
| `GET /api/operator/runs`, `GET /api/operator/runs/{id}`, `GET /api/operator/recovery-cases`, `POST /api/operator/recovery-cases/{id}/retries` | 실행·단계·검증 결과, 기술 복구 이력, 기존 작업 재시도 | 운영 관리자만. 모델 제안과 Spring 확정 결과 분리. 재시도 접수는 복구 완료가 아님. 기술 복구 권한만으로 업무 결론 변경 불가 |

숨김·복원, 처리 상태 필터·정렬, 문제별 해결 확인, 종료 후 재오픈의 구체 경로와 상태 전이는 구현 전 합의한다. 삭제 API는 제안하지 않는다. 문의·티켓·그룹의 버전과 대상 ID를 변경 요청에 포함해 오래된 화면에서 덮어쓰지 않도록 한다.

### 2.3 무응답 종료와 결과 표시

담당자 처리 완료로 확인 대기에 들어간 뒤 24시간 무응답이면 해결 확인 알림을 저장한다. 그 저장 시각에서 48시간 추가 무응답일 때만 미확인 종료한다. 알림 저장 실패는 자동 종료를 보류한다. 미확인 종료는 해결 완료·AI 해결로 집계하지 않는다. 무티켓 안내도 사용자 해결 확인 전에는 AI 해결로 확정하지 않는다. 이 전이는 서버 작업이며 클라이언트 타이머나 에이전트 문구가 확정하지 않는다.

### 2.4 React ↔ Node.js ↔ Spring: Socket.IO

React와 Spring은 Node.js Socket.IO 서버의 클라이언트다. 서버·JavaScript 클라이언트·Java 클라이언트의 호환 버전을 통합 전에 선정한다. 일반 WebSocket/STOMP 클라이언트로 대체하지 않는다. 아래 이벤트명과 외피는 검토할 계약안이며 이벤트 JSON Schema로 고정한다.

| 방향·이벤트 후보 | 최소 데이터와 의미 |
|---|---|
| React → Node.js → Spring: `inquiry.create` | `{requestId,content}`. 검증된 사용자로 문의·첫 메시지·실행 접수를 영속화 |
| React → Node.js → Spring: `message.send`, `message.revise` | `{requestId,inquiryId,expectedInquiryVersion,content}`. 정정은 `messageId`도 필수. 분석 중이면 최신 버전으로 실행하고 기존 티켓 유지. 모든 문제의 이관 뒤에는 정보 보완 저장만 수행하며 `runId`는 없을 수 있음 |
| React → Node.js → Spring: `inquiry.split.respond` | `{requestId,inquiryId,expectedInquiryVersion,consentId,accepted}`. 동의한 경우에만 원본·새 문의·문맥 출처를 연결하고 한 번 생성 |
| React → Node.js: `subscription.request`, `subscription.release` | 서버가 확인할 대상 종류·ID. Spring의 현재 권한 확인 후 Node.js가 room 입장/해제를 수행. 이름을 아는 것만으로 구독 불가 |
| Spring → Node.js → React: `agent.progress`, `answer.delta` | `{eventId,inquiryId,inquiryVersion,runId,payload}`. 답변 조각에는 메시지 식별자·순번 포함. 진행 표시이며 저장/해결 성공이 아님 |
| Spring → Node.js → React: `message.stored`, `state.changed` | Spring 저장 확정 뒤 발행. 메시지·버전·업무 대상 식별자로 임시 화면을 갱신하거나 REST 재조회. 대시보드·티켓·알림도 허용된 대상에게만 전달 |

명령 ACK는 `requestId`와 Spring의 검증/저장 결과 또는 안전한 오류를 연결한다. Node.js 수신만으로 저장 성공 ACK를 반환하지 않는다. 저장 결과는 해당 동작에 따라 `inquiryId, inquiryVersion, messageId, revision, runId, effect` 등을 포함하며, 분석 실행과 정보 보완 저장을 구분한다. Node.js–Spring 단절이나 ACK 시간 초과는 실패 확정 대신 결과 미확인으로 표시하고 같은 `requestId`로 결과를 확인·재전송한다. 오류 코드는 REST와 의미를 맞추되 소켓 ACK에 HTTP 상태를 적용한 것으로 해석하지 않는다.

Spring은 연결에서 확인한 사용자 JWT 또는 검증 가능한 위임 문맥과 별도의 Node.js 서비스 인증을 검사한다. 임의의 `userId`·부서·역할을 신뢰하지 않는다. 사용자 연결이 Spring 서비스 연결로 위장하거나 임의로 `state.changed`를 발행하지 못하게 이벤트 방향과 발행 주체를 검증한다. 문의자용 전체 대화·담당자용 관련 티켓 대화·업무 관리자용 현황·운영 로그의 구독 범위를 분리한다. 재이관·소속 변경 시 이전 구독 권한을 회수한다.

Spring은 DB 커밋 뒤 확정 이벤트를 발행한다. UI는 답변 조각을 임시 표시하고 최종 메시지와 업무 상태는 저장 결과로 동기화한다. 재접속 시 인증과 구독 권한을 다시 확인하고 REST로 메시지·문의·티켓·실행 상태를 조회한다. 중복 이벤트 ID는 한 번만 적용하고 메시지별 순번과 입력 버전으로 뒤늦은 조각을 구분한다. 저장·정정 이력은 유지하며 옛 버전의 미반영 분석 결과만 차단한다. 자동 재연결이나 연결 상태 복구 기능만으로 누락이 모두 복구되었다고 가정하지 않는다. Spring 여러 인스턴스 중 명령 처리자 선택·ACK 라우팅, 전송 제한·재시도·이벤트 보존 방식은 통합 전에 합의한다.

### 2.5 JWT 클레임과 인증 수명

| 클레임 | 의미와 원본 |
|---|---|
| `sub` | 내부 회원 UUID. 사번이나 소켓 ID와 구분 |
| `employeeNo` | 문자열 사번. Spring이 관리하는 회원 정보에서 발급 |
| `departmentId`, `departmentCode`, `departmentName` | 사용자 소속 부서의 ID·코드·표시명. 티켓의 배정 대상과 별개 |
| `roles` | 부서와 별개인 역할 목록. 서버의 역할 카탈로그와 매핑을 확정 |
| `iss`, `aud`, `iat`, `exp` | 발급자·허용 대상 서버·발급 및 만료 시각. 허용 값과 수명은 인증 구현 전에 확정 |

JWT는 Spring이 발급하고 Node.js와 Spring은 각자의 대상·서명·만료 등을 검증한 뒤 클레임을 사용한다. 서명 방식·키 배포/교체·허용 알고리즘을 고정하고 토큰의 단순 디코딩을 인증으로 인정하지 않는다. 부서명은 표시용으로 사용하고 현재 접근 권한은 부서 ID와 역할·업무 상태로 검사한다. 서명된 JWT의 내용은 보유자가 읽을 수 있으므로 불필요한 개인정보를 추가하지 않는다.

Socket.IO 최초 연결의 JWT 검증만으로 연결 수명 전체의 권한을 인정하지 않는다. 연결 중 토큰 만료는 인증 갱신 또는 재연결로 처리하고 갱신 불가 시 전송·구독을 중지한다. 로그아웃·계정 차단·부서/역할 변경 시 기존 토큰의 거부 시점과 구독 해제 전달을 정해야 한다. 현재 부서·업무 권한은 Spring이 검사하며 발급 당시 클레임만으로 이전 부서 정보를 계속 노출하지 않는다. 리프레시 토큰 사용 여부·회전/폐기·재사용 대응·저장 방법, 브라우저 토큰 보관·CSRF, 연결 중 갱신 및 재접속 순서는 구현 전 합의한다. Python에는 사용자 JWT 대신 Spring이 검증한 최소 사용자·부서·권한 문맥을 전달한다.

## 3. Spring ↔ Python 에이전트 계약

Spring이 `runId`, `inquiryId`, 입력 버전, 허용된 메시지·문제·기존 작업 식별자, 활성 부서·서비스 카탈로그, 제한 시간과 최소 사용자 권한 문맥을 전달한다. Python은 `schemaVersion`, 실행/문의 식별자와 버전, 단계 이벤트, 근거가 연결된 구조화 결과 또는 안전한 실패 코드를 반환한다. 새 문제/작업 임시 참조는 해당 실행 안에서 고유해야 하며 Spring이 실제 ID를 발급한다. Spring은 소유 관계·현재 버전·허용 부서·근거 출처·질문 3회 제한을 재검증하고 각 티켓의 저장·배정을 확정한다. 일부 실패 시 성공한 티켓은 유지하고 실패 작업은 같은 ID로 복구한다.

MVP의 최소 결과는 `answer`, `problems[]`(질문/티켓 제안/범위 밖), `ticketProposals[]`(문제 참조·작업 범위·부서·근거·불확실성)이다. 후속 결과에는 Knowledge 답변의 근거와 사용자 해결 확인, Diagnosis의 읽기 전용 도구 결과, 재이관 후보와 제외 이력, 동일 장애 그룹의 공통 해결 근거, 우선순위 제안을 구분해 추가한다. 특정 제안 필드·enum은 기능별 스키마로 확정한다. Fake는 연결 검사에만 사용하며 실제 단일 에이전트 평가를 대신하지 않는다.

Spring→Python은 HTTP로 실행 요청, Python→Spring 진행 전달은 **SSE**다. Spring은 권한·실행 버전을 확인해 공개 가능한 진행·답변 조각·상태 변화를 **Node.js Socket.IO 서버를 통해 React에 전달**한다. REST 상세가 영속 상태의 원본이다. 기존 callback 설계와 SSE를 동시에 요구하지 않는다. 최종 AI 결과 경로, SSE URL·이벤트 스키마·재연결·중복 수신·서비스 인증은 통합 전에 합의해야 한다. SSE 완료·Socket.IO 수신이나 모델 출력은 업무 저장 성공을 뜻하지 않는다.

## 4. 검증할 계약과 미정 항목

- **MVP 인수:** JWT의 사번·소속 부서·역할 검증, 만료·로그아웃·부서 변경 시 전송/구독 권한 처리, Socket.IO 양쪽 클라이언트 연결·재연결, 타인 문의 구독 차단, 저장 ACK 유실 후 중복 재전송·REST 동기화. 문의 정정 후 최신 버전만 반영, 문제별 추가 질문 최대 3회, 복합 문의의 복수 티켓·부서 배정, 부분 실패 보존, 관리자 조회, 실제 모델 실행과 기준선 기록.
- **후속 인수:** 무티켓 해결 확인, 처리·재이관·관리자 예외, 동일 장애 그룹의 공통 해결과 개별 재오픈, 알림 기준의 미확인 종료, 운영 관리자 복구, 역할별 통계와 안전 회귀.
- **구현 전 확정:** REST·Socket.IO 스키마·ACK·오류·room 권한과 재시도/중복 방지, 메시지와 티켓 관련 범위, 새 문의 동의와 정보 보완 응답, 멱등키/이벤트 저장 기간, SSE 최종 AI 결과·재연결, Socket.IO 버전 호환·사용자/서비스 인증·연결 중 JWT 갱신·부서 변경·Spring 처리자 라우팅, 토큰 수명·키 관리·보관/전달·갱신/폐기·CSRF, 보존·알림 작업의 재시도, 평가 자료/수치. 현재 문서의 초안 값을 승인된 구현 계약이나 완료된 기능으로 해석하지 않는다.

기술 참조: [Socket.IO 소개·클라이언트](https://socket.io/docs/v4/), [Java 클라이언트 호환성](https://github.com/socketio/socket.io-client-java#compatibility), [전달 보장](https://socket.io/docs/v4/delivery-guarantees/), [연결 미들웨어](https://socket.io/docs/v4/middlewares/), [JWT 검증 지침](https://www.rfc-editor.org/rfc/rfc8725.html). 버전·설정 확정과 실제 연동 검증은 별도 작업이다.
