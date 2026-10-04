import type {
  ProviderCapacity,
  RegulationIssue,
  RegulationKind,
  RegulationPriority,
  RegulationQueueGroupBy,
  RegulationQueueItem,
  RegulationRequest,
  RegulationStatus,
} from '@sus-nexus/api-client';
import {
  NOW,
  citizens,
  daysAgo,
  healthUnits,
  hoursAgo,
  int,
  iso,
  pick,
  random,
  ulid,
} from './data';

/**
 * Fila regulatória sintética (REG-004/005/006). Coerente com os cidadãos mockados e com as
 * unidades de `healthUnits`. O detalhe da primeira solicitação sempre tem pendências abertas
 * e capacidade do prestador; a segunda está agendada com `appointment_id`.
 */

export interface ServiceDef {
  code: string;
  description: string;
  specialty: string;
  kind: RegulationKind;
}

export const regulationServices: ServiceDef[] = [
  {
    code: '0301010072',
    description: 'Consulta em cardiologia',
    specialty: 'Cardiologia',
    kind: 'consultation',
  },
  {
    code: '0211020036',
    description: 'Ecocardiografia transtorácica',
    specialty: 'Cardiologia',
    kind: 'exam',
  },
  {
    code: '0301010080',
    description: 'Consulta em oftalmologia',
    specialty: 'Oftalmologia',
    kind: 'consultation',
  },
  {
    code: '0405050372',
    description: 'Facectomia com implante de lente',
    specialty: 'Oftalmologia',
    kind: 'surgery',
  },
  {
    code: '0301010099',
    description: 'Consulta em ortopedia',
    specialty: 'Ortopedia',
    kind: 'consultation',
  },
  {
    code: '0206020031',
    description: 'Ressonância magnética de coluna',
    specialty: 'Ortopedia',
    kind: 'exam',
  },
  {
    code: '0301010102',
    description: 'Consulta em neurologia',
    specialty: 'Neurologia',
    kind: 'consultation',
  },
  {
    code: '0301010110',
    description: 'Consulta em endocrinologia',
    specialty: 'Endocrinologia',
    kind: 'consultation',
  },
  {
    code: '0301010129',
    description: 'Consulta em dermatologia',
    specialty: 'Dermatologia',
    kind: 'consultation',
  },
  {
    code: '0303010045',
    description: 'Internação clínica — insuficiência cardíaca',
    specialty: 'Cardiologia',
    kind: 'admission',
  },
];

export interface ProviderDef {
  cnes: string;
  name: string;
  services: string[];
}

export const providers: ProviderDef[] = [
  {
    cnes: '2219522',
    name: 'Policlínica Municipal',
    services: [
      '0301010072',
      '0211020036',
      '0301010080',
      '0301010099',
      '0301010102',
      '0301010110',
      '0301010129',
    ],
  },
  {
    cnes: '2200385',
    name: 'Hospital Municipal Alpheu de Quadros',
    services: ['0405050372', '0206020031', '0303010045', '0211020036'],
  },
  {
    cnes: '3456789',
    name: 'Clínica Conveniada Vida',
    services: ['0301010080', '0405050372', '0301010129'],
  },
];

const ubs = healthUnits.filter((h) => h.kind_code === '02');

const SLA_HOURS: Record<RegulationPriority, number> = {
  emergency: 24,
  urgent: 24 * 7,
  priority: 24 * 30,
  elective: 24 * 90,
};

export const OPEN_REGULATION_STATUSES: RegulationStatus[] = [
  'requested',
  'pending_documents',
  'returned',
  'under_review',
  'authorized',
];

function issue(
  kind: RegulationIssue['kind'],
  description: string,
  createdAt: string,
  opts: { resolved?: boolean; origin?: RegulationIssue['origin'] } = {},
): RegulationIssue {
  return {
    id: `iss_${ulid()}`,
    kind,
    description,
    status: opts.resolved ? 'resolved' : 'open',
    created_at: createdAt,
    resolved_at: opts.resolved ? hoursAgo(int(1, 48)) : undefined,
    origin: opts.origin ?? { kind: 'user', id: 'user_regulador_01' },
  };
}

function history(
  status: RegulationStatus,
  requestedAt: string,
  scheduledAt?: string,
): NonNullable<RegulationRequest['status_history']> {
  const start = new Date(requestedAt).getTime();
  const step = (h: number) => iso(new Date(start + h * 3600_000));
  const h: NonNullable<RegulationRequest['status_history']> = [
    { status: 'requested', occurred_at: requestedAt, actor: 'PEC/UBS' },
  ];
  const push = (s: RegulationStatus, hours: number, reason?: string, actor = 'SISREG') =>
    h.push({ status: s, occurred_at: step(hours), reason, actor });
  switch (status) {
    case 'requested':
      break;
    case 'pending_documents':
      push('under_review', 6);
      push('pending_documents', 30, 'Faltam exames complementares');
      break;
    case 'returned':
      push('under_review', 10);
      push('returned', 40, 'Encaminhamento fora do protocolo');
      break;
    case 'under_review':
      push('under_review', 12);
      break;
    case 'authorized':
      push('under_review', 12);
      push('authorized', 60);
      break;
    case 'denied':
      push('under_review', 12);
      push('denied', 72, 'Não atende critérios do protocolo');
      break;
    case 'scheduled':
      push('under_review', 12);
      push('authorized', 60);
      h.push({ status: 'scheduled', occurred_at: scheduledAt ?? step(100), actor: 'SISREG' });
      break;
    case 'performed':
      push('under_review', 12);
      push('authorized', 48);
      push('scheduled', 72);
      push('performed', 240);
      break;
    case 'no_show':
      push('under_review', 12);
      push('authorized', 48);
      push('scheduled', 72);
      push('no_show', 240, 'Cidadão não compareceu');
      break;
    case 'cancelled':
      push('cancelled', 24, 'Cancelado pela unidade solicitante', 'PEC/UBS');
      break;
    case 'expired':
      push('under_review', 12);
      push('expired', 24 * 120, 'Prazo de validade da solicitação vencido');
      break;
  }
  return h;
}

function makeRequest(i: number, overrides: Partial<RegulationRequest> = {}): RegulationRequest {
  const service = regulationServices[i % regulationServices.length] as ServiceDef;
  const citizen = citizens[(i * 7) % citizens.length]!;
  const requestingUnit = pick(ubs);
  const priority = pick([
    'elective',
    'elective',
    'elective',
    'priority',
    'priority',
    'urgent',
    'emergency',
  ] as const);
  const status = pick([
    'requested',
    'requested',
    'pending_documents',
    'returned',
    'under_review',
    'under_review',
    'authorized',
    'scheduled',
    'scheduled',
    'performed',
    'denied',
    'no_show',
    'expired',
    'cancelled',
  ] as const);
  const maxDays = priority === 'emergency' ? 3 : priority === 'urgent' ? 20 : 180;
  const requestedAt = daysAgo(int(1, maxDays));
  const requestedMs = new Date(requestedAt).getTime();
  const slaDue = iso(new Date(requestedMs + SLA_HOURS[priority] * 3600_000));
  const open = OPEN_REGULATION_STATUSES.includes(status);
  const waitingEnd = open ? NOW.getTime() : requestedMs + int(2, 60) * 24 * 3600_000;
  const waitingDays = Math.max(0, Math.round((waitingEnd - requestedMs) / 86_400_000));
  const provider =
    status === 'requested' || status === 'returned' || random() < 0.2
      ? undefined
      : pick(providers.filter((p) => p.services.includes(service.code)));
  const scheduledAt =
    status === 'scheduled' ? iso(new Date(NOW.getTime() + int(1, 40) * 24 * 3600_000)) : undefined;
  const issues: RegulationIssue[] = [];
  if (status === 'pending_documents' || status === 'returned') {
    issues.push(
      issue(
        pick(['missing_document', 'missing_field', 'clinical_justification'] as const),
        pick([
          'Anexar ECG recente (até 6 meses).',
          'Informar CID principal no encaminhamento.',
          'Justificativa clínica insuficiente para prioridade solicitada.',
        ]),
        hoursAgo(int(4, 72)),
        {
          origin:
            random() > 0.5
              ? { kind: 'agent', id: 'regulation_completeness', version: '1.0.0' }
              : undefined,
        },
      ),
    );
  }
  const slaBreached = open && slaDue < iso(NOW);
  if (slaBreached) {
    issues.push(
      issue('sla_breached', 'Prazo de resposta do protocolo excedido.', hoursAgo(int(1, 24)), {
        origin: { kind: 'rule', id: 'rule_regulation_sla', version: '1.0.0' },
      }),
    );
  }
  return {
    id: `reg_${ulid()}`,
    version: 1,
    citizen_id: citizen.id,
    kind: service.kind,
    requested_service_code: service.code,
    code_system: 'SIGTAP',
    service_description: service.description,
    specialty: service.specialty,
    priority,
    status,
    requested_at: requestedAt,
    requesting_cnes: requestingUnit.cnes,
    requesting_unit_name: requestingUnit.name,
    requesting_professional_id: `prof_${ulid().slice(0, 8)}`,
    provider_cnes: provider?.cnes,
    provider_name: provider?.name,
    regulator_id: status === 'requested' ? undefined : 'reg_user_07',
    scheduled_at: scheduledAt,
    appointment_id:
      status === 'scheduled' || status === 'performed' || status === 'no_show'
        ? `apt_${ulid()}`
        : undefined,
    sla_due_at: slaDue,
    sla_breached: slaBreached,
    waiting_days: waitingDays,
    justification_present: random() > 0.15,
    attached_documents_count: int(0, 3),
    decision_reason:
      status === 'denied' ? 'Não atende critérios do protocolo municipal.' : undefined,
    issues,
    status_history: history(status, requestedAt, scheduledAt),
    source_system: pick(['SISREG', 'SISREG', 'ESUS_REGULACAO']),
    source_record_id: `SR-${100000 + i}`,
    ...overrides,
  };
}

export const regulationRequests: RegulationRequest[] = Array.from({ length: 72 }, (_, i) =>
  makeRequest(i),
);

// Caso 0: pendência aberta + resolvida, prestador com capacidade, SLA próximo.
const first = regulationRequests[0]!;
Object.assign(first, {
  status: 'pending_documents',
  priority: 'priority',
  requested_service_code: '0211020036',
  service_description: 'Ecocardiografia transtorácica',
  specialty: 'Cardiologia',
  kind: 'exam',
  provider_cnes: '2219522',
  provider_name: 'Policlínica Municipal',
  requested_at: daysAgo(12),
  sla_due_at: iso(new Date(NOW.getTime() + 18 * 24 * 3600_000)),
  sla_breached: false,
  waiting_days: 12,
  appointment_id: undefined,
  scheduled_at: undefined,
  issues: [
    issue('missing_document', 'Anexar ECG recente (até 6 meses) ao encaminhamento.', hoursAgo(30), {
      origin: { kind: 'agent', id: 'regulation_completeness', version: '1.0.0' },
    }),
    issue('missing_field', 'CID principal ausente; corrigido pela unidade.', daysAgo(5), {
      resolved: true,
    }),
  ],
  status_history: history('pending_documents', daysAgo(12)),
} satisfies Partial<RegulationRequest>);
// Caso 1: agendado com agendamento vinculado.
const second = regulationRequests[1]!;
Object.assign(second, {
  status: 'scheduled',
  priority: 'urgent',
  provider_cnes: '2200385',
  provider_name: 'Hospital Municipal Alpheu de Quadros',
  requested_service_code: '0206020031',
  service_description: 'Ressonância magnética de coluna',
  specialty: 'Ortopedia',
  kind: 'exam',
  scheduled_at: iso(new Date(NOW.getTime() + 3 * 24 * 3600_000)),
  appointment_id: `apt_${ulid()}`,
  issues: [],
  sla_breached: false,
  status_history: history(
    'scheduled',
    daysAgo(6),
    iso(new Date(NOW.getTime() + 3 * 24 * 3600_000)),
  ),
} satisfies Partial<RegulationRequest>);

export const providerCapacity: ProviderCapacity[] = providers.flatMap((p) =>
  p.services.flatMap((code) =>
    ['2026-09', '2026-10'].map((competence) => {
      const offered = int(20, 120);
      const used = competence === '2026-09' ? offered - int(0, 5) : int(5, offered);
      return {
        provider_cnes: p.cnes,
        provider_name: p.name,
        service_code: code,
        code_system: 'SIGTAP',
        competence,
        offered,
        used,
        available: Math.max(0, offered - used),
        source_system: 'SISREG',
        updated_at: hoursAgo(int(1, 72)),
      };
    }),
  ),
);

function percentile(values: number[], p: number): number | undefined {
  if (values.length === 0) return undefined;
  const sorted = [...values].sort((a, b) => a - b);
  const idx = Math.min(sorted.length - 1, Math.ceil(p * sorted.length) - 1);
  return sorted[Math.max(0, idx)];
}

/** Resumo da fila calculado a partir das solicitações abertas (como faria o core). */
export function queueSummary(groupBy: RegulationQueueGroupBy): RegulationQueueItem[] {
  const open = regulationRequests.filter((r) => OPEN_REGULATION_STATUSES.includes(r.status));
  const keyOf = (r: RegulationRequest): [string, string] => {
    switch (groupBy) {
      case 'specialty':
        return [r.specialty ?? 'sem_especialidade', r.specialty ?? 'Sem especialidade'];
      case 'service_code':
        return [r.requested_service_code, r.service_description ?? r.requested_service_code];
      case 'provider_cnes':
        return [r.provider_cnes ?? 'sem_prestador', r.provider_name ?? 'Sem prestador definido'];
      case 'requesting_cnes':
        return [r.requesting_cnes ?? 'sem_unidade', r.requesting_unit_name ?? 'Sem unidade'];
      case 'priority':
        return [r.priority ?? 'elective', r.priority ?? 'elective'];
    }
  };
  const groups = new Map<string, { label: string; items: RegulationRequest[] }>();
  for (const r of open) {
    const [key, label] = keyOf(r);
    const g = groups.get(key) ?? { label, items: [] };
    g.items.push(r);
    groups.set(key, g);
  }
  return [...groups.entries()]
    .map(([group_key, { label, items }]) => {
      const waits = items.map((r) => r.waiting_days);
      const byPriority: Record<string, number> = {};
      for (const r of items)
        byPriority[r.priority ?? 'elective'] = (byPriority[r.priority ?? 'elective'] ?? 0) + 1;
      const capacity = providerCapacity
        .filter(
          (c) =>
            c.competence === '2026-10' &&
            items.some(
              (r) =>
                r.requested_service_code === c.service_code &&
                (groupBy !== 'provider_cnes' || r.provider_cnes === c.provider_cnes),
            ),
        )
        .reduce((acc, c) => acc + (c.available ?? 0), 0);
      const closed30 = regulationRequests.filter(
        (r) => keyOf(r)[0] === group_key && (r.status === 'scheduled' || r.status === 'no_show'),
      );
      return {
        group_key,
        group_label: label,
        open_requests: items.length,
        by_priority: byPriority,
        avg_waiting_days: Math.round((waits.reduce((a, b) => a + b, 0) / waits.length) * 10) / 10,
        p90_waiting_days: percentile(waits, 0.9),
        sla_breached: items.filter((r) => r.sla_breached).length,
        with_issues: items.filter((r) => r.issues?.some((i) => i.status === 'open')).length,
        capacity_available: capacity,
        scheduled_30d: closed30.filter((r) => r.status === 'scheduled').length,
        no_show_30d: closed30.filter((r) => r.status === 'no_show').length,
      };
    })
    .sort((a, b) => b.open_requests - a.open_requests);
}
