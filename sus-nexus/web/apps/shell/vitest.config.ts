import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';
import { fileURLToPath } from 'node:url';

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
      'server-only': fileURLToPath(
        new URL('./src/__tests__/stubs/server-only.ts', import.meta.url),
      ),
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./vitest.setup.ts'],
    include: ['src/**/*.test.{ts,tsx}'],
    css: false,
    // axe + user-event são lentos quando o Turborepo executa várias suítes em paralelo.
    testTimeout: 20_000,
    hookTimeout: 20_000,
    env: {
      AUTH_MODE: 'mock',
      NEXT_PUBLIC_API_MOCK: 'true',
      CORE_API_URL: 'http://core.test',
      AI_SERVICE_URL: 'http://ai.test',
    },
  },
});
