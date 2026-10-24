import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { beforeEach, afterEach } from 'vitest';
import { setupMockCredentials } from './mockCredentials';
beforeEach(setupMockCredentials);

afterEach(() => {
  cleanup();
});
