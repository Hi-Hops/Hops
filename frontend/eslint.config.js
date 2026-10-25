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
