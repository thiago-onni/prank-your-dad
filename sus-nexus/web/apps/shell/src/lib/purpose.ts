import { isPurpose, type Purpose } from '@sus-nexus/api-client';

/** Cookie httpOnly com a finalidade de acesso selecionada (persistida na sessão). */
export const PURPOSE_COOKIE = 'sus-nexus.purpose';

export function parsePurposeCookie(value: string | undefined): Purpose | undefined {
  return isPurpose(value) ? value : undefined;
}

/** Finalidades sugeridas por papel (usabilidade; o backend valida via OPA). */
export const PURPOSES_BY_ROLE: Record<string, Purpose[]> = {
  acs: ['care_coordination'],
  enfermagem: ['care_coordination', 'scheduling'],
  medico: ['care_coordination', 'scheduling'],
  cadastrador: ['identity_management', 'scheduling'],
  regulador: ['regulation', 'scheduling'],
  operador_integracao: ['integration_operations'],
  auditor: ['production_audit', 'security_audit'],
  gestor: ['management_analytics', 'public_health_surveillance'],
  dpo: ['security_audit'],
};

export function purposesForRoles(roles: string[]): Purpose[] {
  if (roles.includes('admin')) {
    return [
      'care_coordination',
      'regulation',
      'scheduling',
      'identity_management',
      'production_audit',
      'public_health_surveillance',
      'management_analytics',
      'integration_operations',
      'security_audit',
    ];
  }
  const set = new Set<Purpose>();
  for (const r of roles) for (const p of PURPOSES_BY_ROLE[r] ?? []) set.add(p);
  return [...set];
}
