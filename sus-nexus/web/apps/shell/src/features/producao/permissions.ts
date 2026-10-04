'use client';

import { ROLES } from '@sus-nexus/auth';
import { useSession } from '@sus-nexus/auth/client';

export interface ProductionPermissions {
  /** Corrigir registro e dispensar avisos (PRO-006): auditor humano. */
  canCorrect: boolean;
  /** Gerar lote rascunho (PRO-005): auditor. */
  canCreateBatch: boolean;
  /** Aprovar lote com justificativa (PRO-010): auditor ou gestor. */
  canApprove: boolean;
  /** Exportar lote aprovado: auditor. */
  canExport: boolean;
  /** Registrar retorno oficial (PRO-008): operador de integração ou auditor (mesmo papel do core). */
  canRegisterOutcome: boolean;
  /** Sessão de agente de IA: nunca vê ações humanas. */
  isAgent: boolean;
}

/**
 * Permissões de **usabilidade** da tela de produção: escondem ações que o papel não pode
 * executar, espelhando os @RolesAllowed do core (sem atalho para admin). A autorização real é sempre do core (OPA) — agente de IA recebe 403 mesmo que o
 * token carregue outro papel, por isso a UI também esconde as ações para ele.
 */
export function productionPermissions(roles: readonly string[]): ProductionPermissions {
  const isAgent = roles.includes(ROLES.AGENTE_IA);
  const has = (...wanted: string[]) => !isAgent && wanted.some((r) => roles.includes(r));
  return {
    canCorrect: has(ROLES.AUDITOR),
    canCreateBatch: has(ROLES.AUDITOR),
    canApprove: has(ROLES.AUDITOR, ROLES.GESTOR),
    canExport: has(ROLES.AUDITOR),
    canRegisterOutcome: has(ROLES.AUDITOR, ROLES.OPERADOR_INTEGRACAO),
    isAgent,
  };
}

export function useProductionPermissions(): ProductionPermissions {
  const { session } = useSession();
  return productionPermissions(session?.roles ?? []);
}

export function hasAnyProductionAction(p: ProductionPermissions): boolean {
  return p.canCorrect || p.canCreateBatch || p.canApprove || p.canExport || p.canRegisterOutcome;
}
