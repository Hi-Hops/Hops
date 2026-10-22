# 개발 환경·도구 설정

상태: 초안 · 범위: `frontend/` 전체 · 관련 이슈: #6

이 문서는 `frontend/` 초기 세팅(#6)에 그대로 옮길 설정 전문이다. 아래 조합은 2026-10-22 임시 프로젝트에서 `npm install`, `tsc -b`, `eslint .`, `prettier --check .`, `vitest run`, `vite build`가 모두 통과했다(로컬 Node 24.20.0, 팀 기준 24.22.0에서 재확인 필요). 브라우저 화면·실제 Spring·Node.js 서버 연결은 확인하지 않았다.

`frontend/`가 생기면 **코드의 설정 파일이 원본**이다. 이 문서는 선택 이유와 변경 기록만 유지한다.

## 1. 버전

모든 버전은 정확한 값으로 고정한다(`^`, `~` 금지). 잠금 파일 `package-lock.json`을 커밋한다.

| 구분 | 패키지 | 버전 | 근거·이유 |
|---|---|---|---|
| 런타임 | Node.js | 24.22.0 | PRD 5.1 확정 |
| 패키지 관리 | npm | Node 24 동봉 버전 | AGENT_DEVELOPMENT 10 "npm ls" 검증 기준 |
| UI | react, react-dom | 19.3.0 | PRD 5.1 확정 |
| 빌드 | vite | 8.3.4 | PRD 5.1 확정 |
| 빌드 | @vitejs/plugin-react | 6.1.2 | PRD 5.1 확정 |
| 언어 | typescript | 5.9.3 | typescript-eslint 8.72가 6.1 미만만 지원(7.x 사용 불가) |
| 라우팅 | react-router | 8.5.0 | React 19.2.7 이상, Node 22.22 이상 요구 충족 |
| 서버 상태 | @tanstack/react-query | 5.104.5 | REST 조회·재조회·캐시 |
| 전역 상태 | zustand | 5.0.15 | 화면 간 공유 상태가 꼭 필요할 때만(STRUCTURE 5절) |
| UI 기본 부품 | radix-ui | 1.8.1 | 모달·팝오버·탭의 포커스·키보드 처리 |
| 실시간 | socket.io-client | 4.8.4 | 서버 4.x 필요. 서버 버전은 Q-INF-1 |
| 테스트 | vitest | 5.0.5 | Vite 8 지원 |
| 테스트 | jsdom | 30.1.3 | |
| 테스트 | @testing-library/react · dom · user-event · jest-dom | 16.3.3 · 10.4.2 · 14.6.8 · 7.0.1 | |
| 린트 | eslint · @eslint/js | 10.13.0 · 10.0.1 | |
| 린트 | typescript-eslint | 8.72.0 | |
| 린트 | eslint-plugin-react-hooks · eslint-plugin-react-refresh | 7.1.1 · 0.5.7 | |
| 린트 | eslint-config-prettier · globals | 10.1.8 · 17.13.0 | |
| 포맷 | prettier | 3.9.10 | |
| 타입 | @types/react · @types/react-dom · @types/node | 19.3.0 · 19.3.0 · 24.19.2 | |

쓰지 않는 것: `eslint-plugin-jsx-a11y`(6.10.2가 ESLint 10을 지원하지 않음 → 접근성은 CONVENTIONS 8절과 Testing Library 역할 기반 쿼리로 보완), CSS-in-JS·UI 키트(디자인 원본 미정), HTTP 클라이언트 라이브러리(`fetch`로 충분), 런타임 스키마 검증 라이브러리(계약 확정 후 재검토).

새 패키지 추가는 PR에서 이유·버전·peer 의존성 확인 결과를 적고 리뷰를 받는다. AI가 임의로 추가하지 않는다.

## 2. 파일 목록

```text
frontend/
├─ .nvmrc                 # 24.22.0
├─ .env.example           # 비밀값 없는 예시만 커밋(.gitignore가 .env 제외)
├─ .prettierrc.json
├─ .prettierignore
├─ eslint.config.js
├─ index.html
├─ package.json
├─ package-lock.json
├─ tsconfig.json          # 참조만
├─ tsconfig.app.json      # src/
├─ tsconfig.node.json     # vite.config.ts, eslint.config.js
├─ vite.config.ts
└─ src/                   # STRUCTURE.md
```

## 3. package.json

```json
{
  "name": "hops-frontend",
  "private": true,
  "type": "module",
  "engines": { "node": "24.22.0" },
  "scripts": {
    "dev": "vite",
    "build": "tsc -b && vite build",
    "preview": "vite preview",
    "typecheck": "tsc -b",
    "lint": "eslint .",
    "lint:fix": "eslint . --fix",
    "format": "prettier --write .",
    "format:check": "prettier --check .",
    "test": "vitest run",
    "test:watch": "vitest",
    "check": "npm run typecheck && npm run lint && npm run format:check && npm run test"
  }
}
```

`dependencies`·`devDependencies`는 1절 표의 버전으로 `npm install --save-exact`한다. **PR 전에는 `npm run check`와 `npm run build`가 통과해야 한다.**

## 4. TypeScript

`tsconfig.json`

```json
{
  "files": [],
  "references": [{ "path": "./tsconfig.app.json" }, { "path": "./tsconfig.node.json" }]
}
```

`tsconfig.app.json`

```json
{
  "compilerOptions": {
    "tsBuildInfoFile": "./node_modules/.tmp/tsconfig.app.tsbuildinfo",
    "target": "ES2022",
    "lib": ["ES2022", "DOM", "DOM.Iterable"],
    "module": "ESNext",
    "moduleResolution": "bundler",
    "jsx": "react-jsx",
    "types": ["vite/client"],
    "paths": { "@/*": ["./src/*"] },

    "strict": true,
    "noUncheckedIndexedAccess": true,
    "noImplicitOverride": true,
    "noUnusedLocals": true,
    "noUnusedParameters": true,
    "noFallthroughCasesInSwitch": true,
    "verbatimModuleSyntax": true,
    "useUnknownInCatchVariables": true,

    "noEmit": true,
    "skipLibCheck": true
  },
  "include": ["src"]
}
```

`tsconfig.node.json`

```json
{
  "compilerOptions": {
    "tsBuildInfoFile": "./node_modules/.tmp/tsconfig.node.tsbuildinfo",
    "target": "ES2023",
    "lib": ["ES2023"],
    "module": "ESNext",
    "moduleResolution": "bundler",
    "types": ["node"],
    "strict": true,
    "verbatimModuleSyntax": true,
    "noEmit": true,
    "skipLibCheck": true
  },
  "include": ["vite.config.ts", "eslint.config.js"]
}
```

`src/vite-env.d.ts`

```ts
/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_API_BASE_URL?: string;
  readonly VITE_SOCKET_URL?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
```

## 5. Vite·Vitest

개발 서버는 `/api`를 Spring으로, `/socket.io`를 Node.js로 프록시해 같은 출처처럼 쓴다. 대상 주소·포트는 **제안값**이다(Q-INF-2).

```ts
/// <reference types="vitest/config" />
import { fileURLToPath, URL } from 'node:url';
import react from '@vitejs/plugin-react';
import { defineConfig, loadEnv } from 'vite';

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  return {
    plugins: [react()],
    resolve: {
      alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
    },
    server: {
      proxy: {
        '/api': { target: env.DEV_API_PROXY_TARGET ?? 'http://localhost:8080', changeOrigin: true },
        '/socket.io': {
          target: env.DEV_SOCKET_PROXY_TARGET ?? 'http://localhost:3001',
          ws: true,
          changeOrigin: true,
        },
      },
    },
    test: {
      environment: 'jsdom',
      setupFiles: ['./src/test/setup.ts'],
      include: ['src/**/*.test.{ts,tsx}'],
    },
  };
});
```

`src/test/setup.ts`

```ts
import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach } from 'vitest';

afterEach(() => {
  cleanup();
});
```

`.env.example` (값은 예시, 비밀값 금지)

```dotenv
# 브라우저가 쓰는 주소. 비우면 같은 출처(개발 프록시) 사용
VITE_API_BASE_URL=
VITE_SOCKET_URL=
# 개발 서버 프록시 대상(제안값, Q-INF-2)
DEV_API_PROXY_TARGET=http://localhost:8080
DEV_SOCKET_PROXY_TARGET=http://localhost:3001
```

`VITE_`로 시작하는 값은 브라우저 번들에 들어간다. 토큰·키를 넣지 않는다(PRD 5.2).

## 6. ESLint

레이어 의존 규칙(STRUCTURE 3절)을 `no-restricted-imports`로 강제한다. 임시 프로젝트에서 `shared`가 `features`를 가져오면 오류가 나는 것을 확인했다.

```js
import js from '@eslint/js';
import prettier from 'eslint-config-prettier';
import reactHooks from 'eslint-plugin-react-hooks';
import reactRefresh from 'eslint-plugin-react-refresh';
import { defineConfig, globalIgnores } from 'eslint/config';
import globals from 'globals';
import tseslint from 'typescript-eslint';

// 레이어 의존 방향: app → pages → features → shared (역방향 금지)
// 다른 레이어·기능은 '@/...' 별칭과 공개 진입점(index.ts)으로만 가져온다.
const noDeepRelative = {
  group: ['../../*'],
  message: '두 단계 이상 상위 경로 대신 @/ 별칭을 쓴다.',
};
const featureInternals = {
  group: ['@/features/*/*'],
  message: '기능은 공개 진입점(@/features/<기능>)으로만 가져온다.',
};

export default defineConfig([
  globalIgnores(['dist', 'coverage']),
  {
    files: ['**/*.{ts,tsx}'],
    extends: [
      js.configs.recommended,
      tseslint.configs.strictTypeChecked,
      tseslint.configs.stylisticTypeChecked,
      reactHooks.configs.flat.recommended,
      reactRefresh.configs.vite,
    ],
    languageOptions: {
      globals: globals.browser,
      parserOptions: { projectService: true, tsconfigRootDir: import.meta.dirname },
    },
    rules: {
      '@typescript-eslint/consistent-type-imports': 'error',
      '@typescript-eslint/consistent-type-definitions': ['error', 'type'],
      '@typescript-eslint/no-explicit-any': 'error',
      '@typescript-eslint/switch-exhaustiveness-check': 'error',
      '@typescript-eslint/restrict-template-expressions': ['error', { allowNumber: true }],
      'no-console': ['error', { allow: ['warn', 'error'] }],
      'no-restricted-imports': ['error', { patterns: [noDeepRelative] }],
    },
  },
  {
    files: ['src/shared/**/*.{ts,tsx}'],
    rules: {
      'no-restricted-imports': [
        'error',
        {
          patterns: [
            noDeepRelative,
            {
              group: ['@/app/*', '@/pages/*', '@/features/*', '@/app', '@/pages', '@/features'],
              message: 'shared는 상위 레이어를 가져오지 않는다.',
            },
          ],
        },
      ],
    },
  },
  {
    files: ['src/features/**/*.{ts,tsx}'],
    rules: {
      'no-restricted-imports': [
        'error',
        {
          patterns: [
            noDeepRelative,
            featureInternals,
            {
              group: ['@/app/*', '@/pages/*', '@/app', '@/pages'],
              message: 'features는 app·pages를 가져오지 않는다.',
            },
          ],
        },
      ],
    },
  },
  {
    files: ['src/pages/**/*.{ts,tsx}'],
    rules: {
      'no-restricted-imports': [
        'error',
        {
          patterns: [
            noDeepRelative,
            featureInternals,
            { group: ['@/app/*', '@/app'], message: 'pages는 app을 가져오지 않는다.' },
          ],
        },
      ],
    },
  },
  {
    // 전역 타입 보강(ImportMetaEnv 등)은 interface 병합이 필요하다.
    files: ['**/*.d.ts'],
    rules: { '@typescript-eslint/consistent-type-definitions': 'off' },
  },
  {
    files: ['*.config.{js,ts}'],
    extends: [tseslint.configs.disableTypeChecked],
    languageOptions: { globals: globals.node },
  },
  prettier,
]);
```

규칙을 끄는 주석(`eslint-disable`)은 줄 단위로만, 이유를 함께 쓴다: `// eslint-disable-next-line <규칙> -- <이유>`. 파일 전체 끄기는 금지한다.

## 7. Prettier

`.prettierrc.json`

```json
{
  "printWidth": 100,
  "tabWidth": 2,
  "useTabs": false,
  "semi": true,
  "singleQuote": true,
  "jsxSingleQuote": false,
  "trailingComma": "all",
  "bracketSpacing": true,
  "arrowParens": "always",
  "endOfLine": "lf"
}
```

`.prettierignore`

```text
dist
coverage
node_modules
package-lock.json
```

포맷은 Prettier에만 맡긴다. ESLint에서 포맷 규칙은 `eslint-config-prettier`로 끈다. 편집기에서 저장 시 Prettier를 실행하도록 설정한다.

## 8. 변경 기록

| 날짜 | 변경 | 검증 |
|---|---|---|
| 2026-10-22 | 최초 작성 | 임시 프로젝트에서 install·typecheck·lint·format·test·build 통과, 레이어 위반 import가 린트 오류로 잡히는 것 확인 |
