import { defineConfig, devices } from '@playwright/test';

/**
 * E2E com dados sintéticos (MSW no servidor) e autenticação mock.
 * Não faz parte de `pnpm test`; execute com `pnpm --filter @sus-nexus/shell test:e2e`.
 */
export default defineConfig({
  testDir: './e2e',
  timeout: 60_000,
  fullyParallel: true,
  reporter: process.env.CI ? 'github' : 'list',
  use: {
    baseURL: process.env.PLAYWRIGHT_BASE_URL ?? 'http://localhost:3000',
    locale: 'pt-BR',
    timezoneId: 'America/Sao_Paulo',
    trace: 'on-first-retry',
  },
  webServer: process.env.PLAYWRIGHT_BASE_URL
    ? undefined
    : {
        command: 'pnpm dev',
        url: 'http://localhost:3000',
        reuseExistingServer: true,
        env: { AUTH_MODE: 'mock', NEXT_PUBLIC_API_MOCK: 'true' },
        timeout: 120_000,
      },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
});
