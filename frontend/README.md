# Hops 프론트엔드

Node.js **24.22.0**, React·TypeScript·Vite. `frontend/`에서 실행한다.

```sh
npm ci
npm run dev
```

검증은 `npm run check`(타입·린트·포맷·테스트)와 `npm run build`다. 개발 서버의 주소는 터미널 출력에서 확인한다.

개발 규칙은 [프론트 기준](../docs/frontend/README.md), 전송 타입은 [공통 계약](../specs/contracts/README.md)을 따른다. 생성 타입은 `@contracts/types`, 공용 JSON은 `@fixtures/`로 참조한다.

현재 로그인·문의 생성·대화·스트리밍·결과 카드·정정·부서 티켓 목록/상세를 Mock으로 체험할 수 있다. 전체 구현·검증 범위는 [구현 기록](../docs/frontend/IMPLEMENTATION.md)에 있다.

## 가상 테스트 계정

비밀번호는 모두 `mock-only-password`다. 실제 계정이 아니다.

- `demo.requester`: 문의자. 초기 VPN 복합 문의와 부분 실패 결과가 있다.
- `demo.network`: 네트워크 부서 티켓 조회.
- `demo.backend`: backend 부서 티켓 조회.
- `demo.admin`: 업무 관리자 범위의 전체 티켓 조회.

입력에 `권한`을 포함하면 복합 문제·부분 실패, `날씨`는 범위 밖, `시간 초과`는 실행 시간 초과, `모델 오류`는 분석 실패, `CS 검토`는 CS 배정 예시를 사용한다. 그 외 입력은 추가 질문 뒤 다음 사용자 메시지에서 네트워크 티켓을 생성한다. 이 분기는 가상 시나리오 재현용이며 실제 자연어 분석이 아니다.

상태는 브라우저 메모리에만 둔다. 같은 탭에서 계정을 바꾸면 Mock 서버 대역의 업무 데이터는 유지하고 인증·개인 캐시는 정리한다. 새로고침하면 로그인과 데이터는 초기화된다. 개발 중 HMR로 대역 모듈이 교체되어도 재로그인이 필요할 수 있다. 실제 JWT·DB·Spring·Socket.IO 서버·OpenAI는 연결하지 않는다.
