import { ROLES } from '@sus-nexus/auth';
import { t } from '@/i18n';

export interface NavItem {
  href: string;
  label: string;
  /** Papéis que enxergam o item (vazio = todos). Apenas usabilidade. */
  roles: string[];
  description: string;
}

export const NAV_ITEMS: NavItem[] = [
  { href: '/', label: t.nav.home, roles: [], description: 'Atalhos e visão geral' },
  {
    href: '/integracoes',
    label: t.nav.integrations,
    roles: [ROLES.OPERADOR_INTEGRACAO, ROLES.GESTOR],
    description: 'Conectores, mensagens, DLQ e reconciliação',
  },
  {
    href: '/cadastro',
    label: t.nav.registry,
    roles: [ROLES.CADASTRADOR, ROLES.ENFERMAGEM, ROLES.GESTOR],
    description: 'Busca de cidadãos e revisão de duplicidades',
  },
  {
    href: '/tarefas',
    label: t.nav.tasks,
    roles: [
      ROLES.ACS,
      ROLES.ENFERMAGEM,
      ROLES.MEDICO,
      ROLES.REGULADOR,
      ROLES.OPERADOR_INTEGRACAO,
      ROLES.GESTOR,
    ],
    description: 'Fila operacional com SLA',
  },
  {
    href: '/regulacao',
    label: t.nav.regulation,
    roles: [ROLES.REGULADOR, ROLES.GESTOR],
    description: 'Cockpit de regulação (F2)',
  },
  {
    href: '/cuidado',
    label: t.nav.care,
    roles: [ROLES.ACS, ROLES.ENFERMAGEM, ROLES.MEDICO, ROLES.GESTOR],
    description: 'Workbench de cuidado (F2/F3)',
  },
  {
    href: '/producao',
    label: t.nav.production,
    roles: [ROLES.AUDITOR, ROLES.GESTOR],
    description: 'Auditoria de produção (F4)',
  },
  {
    href: '/agentes',
    label: t.nav.agents,
    roles: [ROLES.GESTOR, ROLES.ADMIN],
    description: 'Cockpit de agentes (F2)',
  },
  {
    href: '/situacao',
    label: t.nav.situation,
    roles: [ROLES.GESTOR, ROLES.DPO],
    description: 'Sala de situação (F4)',
  },
];

export function visibleNavItems(roles: string[]): NavItem[] {
  const isAdmin = roles.includes(ROLES.ADMIN);
  return NAV_ITEMS.filter(
    (item) => item.roles.length === 0 || isAdmin || item.roles.some((r) => roles.includes(r)),
  );
}
