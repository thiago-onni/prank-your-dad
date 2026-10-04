/** Variáveis de ambiente do shell (somente servidor, exceto NEXT_PUBLIC_*). */
export const env = {
  coreApiUrl: process.env.CORE_API_URL ?? 'http://localhost:8080',
  apiMock: process.env.NEXT_PUBLIC_API_MOCK === 'true',
  authMode: process.env.AUTH_MODE === 'mock' ? 'mock' : 'keycloak',
} as const;

export const PUBLIC_ENV = {
  apiMock: process.env.NEXT_PUBLIC_API_MOCK === 'true',
} as const;
