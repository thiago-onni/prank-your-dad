import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./vitest.setup.ts'],
    include: ['src/**/*.test.{ts,tsx}'],
    css: false,
    // axe + user-event são lentos quando o Turborepo executa várias suítes em paralelo.
    testTimeout: 20_000,
    hookTimeout: 20_000,
  },
});
