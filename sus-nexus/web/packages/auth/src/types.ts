/** Papéis conhecidos (claims `realm_access.roles` do Keycloak). */
export const ROLES = {
  ACS: 'acs',
  ENFERMAGEM: 'enfermagem',
  MEDICO: 'medico',
  CADASTRADOR: 'cadastrador',
  REGULADOR: 'regulador',
  OPERADOR_INTEGRACAO: 'operador_integracao',
  AUDITOR: 'auditor',
  GESTOR: 'gestor',
  DPO: 'dpo',
  ADMIN: 'admin',
  /** Papéis do realm Keycloak (`platform/compose/keycloak`). */
  PROFISSIONAL_APS: 'profissional_aps',
  PROFISSIONAL_HOSPITALAR: 'profissional_hospitalar',
  ADMIN_MUNICIPAL: 'admin_municipal',
  /** Cliente agente de IA (ai-service). Nunca executa ações que exigem humano (ex.: PRO-010). */
  AGENTE_IA: 'agente_ia',
} as const;

export type Role = (typeof ROLES)[keyof typeof ROLES];

export interface SessionUser {
  id: string;
  name: string;
  email?: string;
  /** `municipality_id` do token (tenant). */
  municipalityId?: string;
  /**
   * Lotação do usuário (claims `cnes`, `teams`, `microareas` do token). Usada apenas para
   * filtros padrão na UI (ex.: UBS de referência, microárea do ACS) — o escopo real é do OPA.
   */
  cnes?: string[];
  teams?: string[];
  microareas?: string[];
}

/** Sessão visível ao navegador — nunca contém tokens. */
export interface PublicSession {
  user: SessionUser;
  roles: string[];
  expires: string;
  /** Erro de refresh (ex.: `RefreshTokenError`) — a UI deve pedir novo login. */
  error?: string;
}

/** Sessão completa, somente no servidor. */
export interface ServerSession extends PublicSession {
  accessToken?: string;
}

export function hasRole(
  session: Pick<PublicSession, 'roles'> | null | undefined,
  ...roles: string[]
): boolean {
  if (!session) return false;
  if (session.roles.includes(ROLES.ADMIN)) return true;
  return roles.some((r) => session.roles.includes(r));
}

export function toPublicSession(session: ServerSession | null): PublicSession | null {
  if (!session) return null;
  const { user, roles, expires, error } = session;
  return error ? { user, roles, expires, error } : { user, roles, expires };
}
