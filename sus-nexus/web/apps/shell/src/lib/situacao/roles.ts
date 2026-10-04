import { ROLES } from '@sus-nexus/auth';

/** Papéis que acessam a Sala de Situação (BFF exige; a navegação só esconde). `admin` sempre. */
export const SITUATION_ROLES: string[] = [ROLES.GESTOR, ROLES.AUDITOR, ROLES.ADMIN_MUNICIPAL];

/**
 * Papéis que veem a aba FHIR em `/cidadaos/[id]`: clínicos e gestão (nunca cadastrador/ACS/auditor).
 * A autorização real é do FHIR Gateway (escopos SMART + OPA).
 */
export const FHIR_VIEWER_ROLES: string[] = [
  ROLES.MEDICO,
  ROLES.ENFERMAGEM,
  ROLES.PROFISSIONAL_APS,
  ROLES.PROFISSIONAL_HOSPITALAR,
  ROLES.GESTOR,
  ROLES.ADMIN_MUNICIPAL,
];

const CORRELATION_RE = /^[A-Za-z0-9._-]{1,64}$/;

/** Aceita o X-Correlation-Id do navegador apenas se bem-formado. */
export function safeCorrelationId(value: string | null, fallback: () => string): string {
  return value && CORRELATION_RE.test(value) ? value : fallback();
}
