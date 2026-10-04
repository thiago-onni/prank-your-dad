'use client';

import { ROLES } from '@sus-nexus/auth';
import { useSession } from '@sus-nexus/auth/client';

/** Papéis que enxergam além da microárea (equipe/UBS inteira). */
const BEYOND_MICROAREA = [
  ROLES.ENFERMAGEM,
  ROLES.MEDICO,
  ROLES.PROFISSIONAL_APS,
  ROLES.GESTOR,
  ROLES.ADMIN,
  ROLES.ADMIN_MUNICIPAL,
];

export interface UserScope {
  /** CNES de lotação (claim `cnes`). O primeiro é a UBS padrão dos filtros. */
  cnes: string[];
  teams: string[];
  microareas: string[];
  /** ACS sem outro papel clínico/gestão: restrito às próprias microáreas. */
  restrictedToMicroareas: boolean;
}

/**
 * Lotação do usuário para filtros padrão. **Somente usabilidade**: o core/OPA aplica o escopo
 * real (`citizen_team`, `citizen_cnes`, `microareas`) independentemente do que a UI envia.
 */
export function useUserScope(): UserScope {
  const { session } = useSession();
  const roles = session?.roles ?? [];
  const isAcs = roles.includes(ROLES.ACS);
  const beyond = roles.some((r) => (BEYOND_MICROAREA as string[]).includes(r));
  return {
    cnes: session?.user.cnes ?? [],
    teams: session?.user.teams ?? [],
    microareas: session?.user.microareas ?? [],
    restrictedToMicroareas: isAcs && !beyond,
  };
}
