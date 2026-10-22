# Hops 프론트엔드

Node.js 24.22.0, React·TypeScript·Vite. 설치는 `npm ci`, 개발은 `npm run dev`, 검증은 `npm run check`와 `npm run build`다.

개발 규칙은 [프론트 기준](../docs/frontend/README.md), 전송 타입은 [공통 계약](../specs/contracts/README.md)을 따른다. TypeScript 생성 타입을 `@contracts/types`로 참조하며 별도로 복제하지 않는다. 공용 JSON은 `@fixtures/`에서 참조한다.

화면별 Mock 동작과 인수 조건 검증은 각 기능 구현 시 추가한다. 실제 서비스 인증·DB·LLM 연결은 제공하지 않는다.
