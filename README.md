# Hops — 사내 IT 장애·문의 대응 에이전트

사내 IT 문의를 대화로 분석하고, 해결을 안내하거나 진단 근거를 담은 티켓으로 만들어 담당 부서에 연결하는 프로젝트입니다.

4명이 MVP 1주와 후속 2주를 계획합니다. MVP는 실제 단일 에이전트 기반 서비스 연결·복수 티켓 생성·부서 배정·관리자 조회와 기준선 평가입니다. 최종 구조는 Supervisor와 4개 전문 에이전트이며, 동일 조건의 평가 수치를 비교해 개선 성과를 설명합니다.

## 읽는 순서

1. [AGENTS.md](AGENTS.md): 팀과 AI의 공통 작업 규칙.
2. [PRD](docs/PRD.md): 제품 목적·사용자·기능·범위·성공 기준.
3. [Git 컨벤션](GIT_CONVENTION.md): 브랜치·커밋·PR·리뷰 방법.

Claude Code의 진입점은 [CLAUDE.md](CLAUDE.md)이며 AGENTS.md를 먼저 읽도록 안내합니다.

## 첫 작업

팀이 PRD와 MVP 범위를 함께 검토하고 역할을 나눕니다. 전체 데이터 관계는 [데이터 설계](docs/DATA.md), React·Node.js·Spring·Python 연결은 [API 설계](docs/API.md)를 읽고 기능별 계약을 리뷰합니다. 두 문서는 구현 전 검토할 설계안입니다. 합의한 API는 OpenAPI와 JSON Schema로 구체화합니다.

목표 기술 구성은 React·Node.js + Socket.IO 채팅 서버·Java 21 기반 Spring·Python 3.13 + LangGraph + FastAPI·PostgreSQL입니다. React와 Spring은 각각 Node.js 서버에 Socket.IO 클라이언트로 연결합니다. Spring은 JWT 발급·인증·권한과 메시지·업무 저장을 담당하고, Node.js는 연결·구독·실시간 이벤트를 중계합니다. JWT에는 사번과 소속 부서명을 포함하며 상세 클레임·인증·이벤트 계약은 API 설계에서 정리합니다. 로그인·조회·티켓 업무는 React–Spring REST, 채팅은 React–Node.js–Spring Socket.IO, AI 진행 전달은 Python–Spring SSE를 사용합니다.

프론트 빌드용 Node.js 24.22.0, React 19.3.0, Spring Boot 4.1.1, Gradle Wrapper 9.8.1, LangGraph 1.2.16, FastAPI 0.143.5, Uvicorn 0.53.0을 개발 기준으로 사용합니다. 채팅 서버 런타임과 Socket.IO 서버·JavaScript/Java 클라이언트의 호환 버전은 통합 전에 선정합니다. 전체 버전과 검증 범위는 [개발 안내](docs/AGENT_DEVELOPMENT.md)의 9~11장을 따릅니다. OpenAI API 기반 LLM과 비용 최소화를 적용하며, 정확한 모델·공용 환경·실행 명령은 구현과 실측을 통해 정합니다. 현재 Spring 백엔드의 초기 실행 기반과 패키지 구성을 준비했으며, JWT 로그인·Socket.IO 채팅·실제 업무 API와 AI·DB 연동은 구현 전입니다.

에이전트별 개발을 시작할 때는 [공통 입출력 계약](contracts/agents/README.md)을 읽고, [개발·통합 준비 안내](docs/AGENT_DEVELOPMENT.md)에서 본인의 준비물과 통합 완료 조건을 확인합니다. Python 내부 역할 계약과 기존 MVP 서비스 API를 구분하며, MVP에서는 실제 단일 에이전트를 연결해 평가하고, 후속 단계에서 전문 에이전트들을 통합합니다. Fake는 개발 중 연결 테스트에만 사용합니다.

## 구성별 개발 안내

- [Spring 백엔드](backend/spring/hops/README.md): 패키지 구성, DB 설정, 테스트·빌드·실행 방법.

## 개인 자료와 공통 변경

개인 AI 기록·실험·임시 자료는 `.local/`에 두고 공유하지 않습니다. 담당자는 다른 데이터셋을 자유롭게 탐색할 수 있으며 공통 자료와 설정은 팀 검토 후 반영합니다. 문서와 코드 변경은 PR 리뷰를 거칩니다. 초기 문서의 존재가 팀 승인이나 구현 완료를 뜻하지는 않습니다.
