# Hops — 사내 IT 장애·문의 대응 에이전트

사내 IT 문의를 대화로 분석하고, 해결을 안내하거나 진단 근거를 담은 티켓으로 만들어 담당 부서에 연결하는 프로젝트입니다.

4명이 MVP 1주와 후속 2주를 계획합니다. MVP는 단일 LLM 기반 문의 분석·복수 티켓 생성·부서 배정·관리자 조회이며, 최종 구조는 Supervisor와 4개 전문 에이전트입니다.

## 읽는 순서

1. [AGENTS.md](AGENTS.md): 팀과 AI의 공통 작업 규칙.
2. [PRD](docs/PRD.md): 제품 목적·사용자·기능·범위·성공 기준.
3. [Git 컨벤션](GIT_CONVENTION.md): 브랜치·커밋·PR·리뷰 방법.

Claude Code의 진입점은 [CLAUDE.md](CLAUDE.md)이며 AGENTS.md를 먼저 읽도록 안내합니다.

## 첫 작업

팀이 PRD와 MVP 범위를 함께 검토하고 역할을 나눕니다. ERD는 [MVP 데이터 설계](docs/MVP_DATA.md), 프론트·Spring·Python 연결은 [MVP API](docs/MVP_API.md)를 읽고 최소 계약을 리뷰합니다. 두 문서는 첫 E2E 범위의 설계안입니다. 합의한 API는 OpenAPI와 JSON Schema로 구체화합니다.

기술 구성은 React·Java Spring·Python·PostgreSQL입니다. 직접 실행 LLM과 비용 최소화를 우선하며, 정확한 모델·공용 환경·실행 명령은 구현과 실측을 통해 정합니다. 현재는 초기 문서를 준비한 단계입니다.

에이전트별 개발을 시작할 때는 [공통 입출력 계약](contracts/agents/README.md)을 읽고, [개발·통합 준비 안내](docs/AGENT_DEVELOPMENT.md)에서 본인의 준비물과 통합 완료 조건을 확인합니다. Python 내부 역할 계약과 기존 MVP 서비스 API를 구분하며, 공통 기반과 Fake를 먼저 연결합니다.

## 개인 자료와 공통 변경

개인 AI 기록·실험·임시 자료는 `.local/`에 두고 공유하지 않습니다. 담당자는 다른 데이터셋을 자유롭게 탐색할 수 있으며 공통 자료와 설정은 팀 검토 후 반영합니다. 문서와 코드 변경은 PR 리뷰를 거칩니다. 초기 문서의 존재가 팀 승인이나 구현 완료를 뜻하지는 않습니다.
