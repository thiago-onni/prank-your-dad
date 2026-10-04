import { ROLES } from '@sus-nexus/auth';
import { t } from '@/i18n';

export interface NavItem {
  href: string;
  label: string;
  /** Papéis que enxergam o item (vazio = todos). Apenas usabilidade. */
  roles: string[];
  description: string;
}

/** Papéis clínicos da APS ("profissional_aps" no realm = enfermagem + médico no shell). */
export const APS_ROLES = [ROLES.ACS, ROLES.ENFERMAGEM, ROLES.MEDICO, ROLES.PROFISSIONAL_APS];
/** Gestão (admin municipal incluso). */
export const MANAGER_ROLES = [ROLES.GESTOR, ROLES.ADMIN_MUNICIPAL];

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
    roles: [...APS_ROLES, ROLES.REGULADOR, ROLES.OPERADOR_INTEGRACAO, ROLES.GESTOR],
    description: 'Fila operacional com SLA',
  },
  {
    href: '/cuidado',
    label: t.nav.care,
    roles: [...APS_ROLES, ROLES.GESTOR],
    description: 'Workbench de cuidado da equipe/UBS',
  },
  {
    href: '/hospital',
    label: t.nav.hospital,
    roles: [ROLES.PROFISSIONAL_HOSPITALAR, ...APS_ROLES, ...MANAGER_ROLES],
    description: 'Internações, altas, risco e contato pós-alta da população da UBS',
  },
  {
    href: '/regulacao',
    label: t.nav.regulation,
    roles: [ROLES.REGULADOR, ROLES.GESTOR],
    description: 'Cockpit de regulação: fila, pendências e capacidade',
  },
  {
    href: '/exames',
    label: t.nav.exams,
    roles: [ROLES.ENFERMAGEM, ROLES.MEDICO, ROLES.REGULADOR, ROLES.GESTOR],
    description: 'Ciclo dos exames: pedido, agendamento, laudo e retorno',
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
    roles: [ROLES.GESTOR, ROLES.DPO, ROLES.ADMIN],
    description: 'Cockpit de agentes: execuções, aprovações e kill switch',
  },
  {
    href: '/admin/protocolos',
    label: t.nav.protocols,
    roles: MANAGER_ROLES,
    description: 'Protocolos de linha de cuidado: versões, casos de teste e aprovação',
  },
  {
    href: '/situacao',
    label: t.nav.situation,
    roles: [ROLES.GESTOR, ROLES.DPO],
    description: 'Sala de situação (F4)',
  },
];

/** Atalhos adicionais da Home (subrotas relevantes por papel). */
export const HOME_SHORTCUTS: NavItem[] = [
  {
    href: '/regulacao/capacidade',
    label: 'Capacidade dos prestadores',
    roles: [ROLES.REGULADOR, ROLES.GESTOR],
    description: 'Oferta por prestador, serviço e competência',
  },
  {
    href: '/agentes/kill-switch',
    label: 'Kill switch dos agentes',
    roles: [ROLES.DPO, ROLES.ADMIN],
    description: 'Bloqueio global, por agente, ferramenta ou tenant',
  },
];

/**
 * Atalho principal por papel ("Para o seu papel" na Home). A ordem define a prioridade quando
 * o usuário tem mais de um papel. Apenas usabilidade.
 */
export const FEATURED_SHORTCUTS: NavItem[] = [
  {
    href: '/hospital',
    label: 'Internações e pós-alta',
    roles: [ROLES.PROFISSIONAL_HOSPITALAR],
    description: 'Episódios, risco de reinternação e contrarreferência',
  },
  {
    href: '/cuidado?aba=busca-ativa',
    label: 'Busca ativa',
    roles: APS_ROLES,
    description: 'Lacunas de cuidado abertas da sua microárea/equipe, por dias de atraso',
  },
  {
    href: '/admin/protocolos',
    label: 'Protocolos de cuidado',
    roles: MANAGER_ROLES,
    description: 'Novas versões, casos de teste e fluxo de aprovação',
  },
];

function visible(items: NavItem[], roles: string[]): NavItem[] {
  const isAdmin = roles.includes(ROLES.ADMIN);
  return items.filter(
    (item) => item.roles.length === 0 || isAdmin || item.roles.some((r) => roles.includes(r)),
  );
}

export function visibleNavItems(roles: string[]): NavItem[] {
  return visible(NAV_ITEMS, roles);
}

/** Atalhos da Home por papel: itens de navegação + subrotas, sem a própria Home. */
export function homeShortcuts(roles: string[]): NavItem[] {
  return [
    ...visible(NAV_ITEMS, roles).filter((i) => i.href !== '/'),
    ...visible(HOME_SHORTCUTS, roles),
  ];
}

/** Atalhos em destaque para os papéis do usuário (admin vê todos). */
export function featuredShortcuts(roles: string[]): NavItem[] {
  return visible(FEATURED_SHORTCUTS, roles);
}
