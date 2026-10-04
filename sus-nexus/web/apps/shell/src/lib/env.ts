/** Variáveis de ambiente do shell (somente servidor, exceto NEXT_PUBLIC_*). */
export const env = {
  coreApiUrl: process.env.CORE_API_URL ?? 'http://localhost:8080',
  /** Base do ai-service (FastAPI). Somente servidor: o navegador usa o proxy `/api/ai`. */
  aiServiceUrl: process.env.AI_SERVICE_URL ?? 'http://localhost:8090',
  /** Base do FHIR Gateway (sem `/fhir/r4`). Somente servidor: o navegador usa `/api/fhir/r4`. */
  get fhirGatewayUrl() {
    return process.env.FHIR_GATEWAY_URL ?? 'http://localhost:8081';
  },
  apiMock: process.env.NEXT_PUBLIC_API_MOCK === 'true',
  authMode: process.env.AUTH_MODE === 'mock' ? 'mock' : 'keycloak',
} as const;

/**
 * Trino (Sala de Situação). Usuário de serviço do grupo `bi` — lê apenas `marts_aggregated`,
 * `reference` e `marts.dim_*`. Lido a cada chamada (getter) para os testes poderem trocar o env.
 */
export function trinoEnv() {
  return {
    url: process.env.TRINO_URL ?? 'http://localhost:8088',
    user: process.env.TRINO_USER ?? 'sus-nexus-web',
    catalog: process.env.TRINO_CATALOG ?? 'iceberg',
    password: process.env.TRINO_PASSWORD || undefined,
  };
}

/** URL do painel "Sala de Situação" no Metabase (opcional; só é exibida como link). */
export function metabaseUrl(): string | undefined {
  const raw = process.env.METABASE_URL;
  if (!raw) return undefined;
  try {
    const u = new URL(raw);
    return u.protocol === 'https:' || u.protocol === 'http:' ? u.toString() : undefined;
  } catch {
    return undefined;
  }
}

export const PUBLIC_ENV = {
  apiMock: process.env.NEXT_PUBLIC_API_MOCK === 'true',
} as const;
