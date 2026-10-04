export type AuthMode = 'keycloak' | 'mock';

export interface AuthEnv {
  mode: AuthMode;
  secret: string;
  keycloak: { clientId: string; clientSecret: string; issuer: string };
  mock: {
    user: string;
    email: string;
    roles: string[];
    municipalityId: string;
    cnes: string[];
    teams: string[];
    microareas: string[];
  };
  /** Cookies `Secure` (sempre em produção). */
  secureCookies: boolean;
}

export type EnvSource = Record<string, string | undefined>;

function list(value: string): string[] {
  return value
    .split(',')
    .map((v) => v.trim())
    .filter(Boolean);
}

export function readAuthEnv(env: EnvSource = process.env): AuthEnv {
  const mode: AuthMode = env.AUTH_MODE === 'mock' ? 'mock' : 'keycloak';
  const isProd = env.NODE_ENV === 'production';
  if (mode === 'mock' && isProd && env.AUTH_ALLOW_MOCK_IN_PRODUCTION !== 'true') {
    throw new Error('AUTH_MODE=mock não é permitido em produção.');
  }
  const secret = env.AUTH_SECRET ?? (mode === 'mock' ? 'dev-only-insecure-secret-change-me' : '');
  if (mode === 'keycloak' && !secret) {
    throw new Error('AUTH_SECRET é obrigatório.');
  }
  return {
    mode,
    secret,
    keycloak: {
      clientId: env.AUTH_KEYCLOAK_ID ?? '',
      clientSecret: env.AUTH_KEYCLOAK_SECRET ?? '',
      issuer: env.AUTH_KEYCLOAK_ISSUER ?? 'http://localhost:8180/realms/sus-nexus',
    },
    mock: {
      user: env.AUTH_MOCK_USER ?? 'Maria Operadora (mock)',
      email: env.AUTH_MOCK_EMAIL ?? 'maria.operadora@saude.exemplo.gov.br',
      roles: (env.AUTH_MOCK_ROLES ?? 'cadastrador,operador_integracao,enfermagem,gestor')
        .split(',')
        .map((r) => r.trim())
        .filter(Boolean),
      municipalityId: env.AUTH_MOCK_MUNICIPALITY ?? 'ibge_3143302',
      cnes: list(env.AUTH_MOCK_CNES ?? '2126672'),
      teams: list(env.AUTH_MOCK_TEAMS ?? ''),
      microareas: list(env.AUTH_MOCK_MICROAREAS ?? ''),
    },
    secureCookies: isProd || env.AUTH_SECURE_COOKIES === 'true',
  };
}
