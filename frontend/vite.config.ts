/// <reference types="vitest/config" />
import { fileURLToPath, URL } from 'node:url';
import react from '@vitejs/plugin-react';
import { defineConfig, loadEnv } from 'vite';

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  return {
    plugins: [react()],
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url)),
        '@fixtures': fileURLToPath(new URL('../specs/mocks', import.meta.url)),
      },
    },
    server: {
      proxy: {
        '/api': { target: env.DEV_API_PROXY_TARGET ?? 'http://localhost:8080', changeOrigin: true },
      },
    },
    test: {
      maxWorkers: 2,
      environment: 'jsdom',
      setupFiles: ['./src/test/setup.ts'],
      include: ['src/**/*.test.{ts,tsx}'],
    },
  };
});
