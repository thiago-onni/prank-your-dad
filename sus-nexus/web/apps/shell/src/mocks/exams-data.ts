import type { ExamIssue, ExamOrder, ExamOrderStatus, ExamResult } from '@sus-nexus/api-client';
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
 * Pedidos de exame sintéticos (EXA-002/006/007/010). O primeiro pedido tem laudo final marcado
 * como crítico (com documento); há exemplos de "não agendado", "laudo pendente" e "sem retorno".
 */

interface ExamDef {
  code: string;
  description: string;
  category: 'laboratory' | 'imaging' | 'other';
  performer: string;
}

const exams: ExamDef[] = [
  {
    code: '0202020380',
    description: 'Hemograma completo',
    category: 'laboratory',
    performer: '2219522',
  },
  {
    code: '0202010473',
    description: 'Glicemia de jejum',
    category: 'laboratory',
    performer: '2219522',
  },
  {
    code: '0202010503',
    description: 'Hemoglobina glicada (HbA1c)',
    category: 'laboratory',
    performer: '2219522',
  },
  { code: '0202010317', description: 'Creatinina', category: 'laboratory', performer: '2219522' },
  { code: '0202060250', description: 'TSH', category: 'laboratory', performer: '2219522' },
  {
    code: '0211020036',
    description: 'Ecocardiografia transtorácica',
    category: 'imaging',
    performer: '2200385',
  },
  {
    code: '0204030153',
    description: 'Radiografia de tórax',
    category: 'imaging',
    performer: '2200385',
  },
  {
    code: '0205020046',
    description: 'Ultrassonografia de abdome total',
    category: 'imaging',
    performer: '2219522',
  },
  {
    code: '0204030030',
    description: 'Mamografia bilateral',
    category: 'imaging',
    performer: '2219522',
  },
];

const CYCLE: ExamOrderStatus[] = [
  'requested',
  'authorized',
  'scheduled',
  'collected',
  'performed',
  'reported',
];
const ubs = healthUnits.filter((h) => h.kind_code === '02');

function hoursBetween(a: string | undefined, b: string | undefined): number | undefined {
  if (!a || !b) return undefined;
  return Math.round((new Date(b).getTime() - new Date(a).getTime()) / 3600_000);
}

function makeOrder(i: number): ExamOrder {
  const def = exams[i % exams.length] as ExamDef;
  const citizen = citizens[(i * 5 + 3) % citizens.length]!;
  const unit = pick(ubs);
  const status = pick([
    'requested',
    'requested',
    'authorized',
    'scheduled',
    'scheduled',
    'collected',
    'performed',
    'reported',
    'reported',
    'reported',
    'cancelled',
    'not_performed',
  ] as const);
  const requestedAt = daysAgo(int(2, 60));
  const start = new Date(requestedAt).getTime();
  const at = (h: number) => iso(new Date(start + h * 3600_000));
  const stepIdx = CYCLE.indexOf(status);
  const stepHours = [
    0,
    6,
    48 + int(0, 240),
    240 + int(0, 480),
    240 + int(0, 500),
    300 + int(0, 600),
  ];
  const history: NonNullable<ExamOrder['status_history']> = [];
  const reached = (s: ExamOrderStatus) => stepIdx >= CYCLE.indexOf(s);
  if (status === 'cancelled' || status === 'not_performed') {
    history.push({ status: 'requested', occurred_at: requestedAt });
    history.push({
      status,
      occurred_at: at(100),
      reason:
        status === 'cancelled'
          ? 'Pedido cancelado pela unidade'
          : 'Cidadão não compareceu à coleta',
    });
  } else {
    for (let k = 0; k <= stepIdx; k++) {
      history.push({ status: CYCLE[k]!, occurred_at: at(stepHours[k] ?? 0) });
    }
  }
  const scheduledFor = reached('scheduled') ? at(stepHours[2]! + 72) : undefined;
  const performedAt = reached('performed') ? at(stepHours[4]!) : undefined;
  const reportedAt = reached('reported') ? at(stepHours[5]!) : undefined;
  const followupAt = reportedAt && random() > 0.5 ? at(stepHours[5]! + 96) : undefined;
  const results: ExamResult[] = reportedAt
    ? [
        {
          id: `res_${ulid()}`,
          status: pick(['final', 'final', 'final', 'preliminary', 'amended']),
          reported_at: reportedAt,
          performer_cnes: def.performer,
          source_system: def.category === 'laboratory' ? 'LIS' : 'RIS',
          has_document: random() > 0.2,
          observations_count: def.category === 'laboratory' ? int(3, 18) : int(0, 2),
          critical: false,
          followup_task_id: followupAt ? `task_${ulid()}` : undefined,
        },
      ]
    : [];
  const issues: ExamIssue[] = [];
  const ageDays = (NOW.getTime() - start) / 86_400_000;
  if ((status === 'requested' || status === 'authorized') && ageDays > 10)
    issues.push('not_scheduled');
  if (status === 'performed' && ageDays > 7) issues.push('result_pending');
  if (status === 'reported' && !followupAt && ageDays > 20) issues.push('no_result_followup');
  if (results.some((r) => r.status === 'amended') && random() > 0.7) issues.push('inconclusive');
  return {
    id: `exo_${ulid()}`,
    version: 1,
    citizen_id: citizen.id,
    exam_code: def.code,
    code_system: 'SIGTAP',
    exam_description: def.description,
    category: def.category,
    priority: pick(['routine', 'routine', 'routine', 'priority', 'urgent']),
    care_line: pick(['hipertensão', 'diabetes', 'pré-natal', undefined, undefined]),
    status,
    requested_at: requestedAt,
    requesting_cnes: unit.cnes,
    requesting_unit_name: unit.name,
    requesting_professional_id: `prof_${ulid().slice(0, 8)}`,
    performer_cnes: reached('scheduled') ? def.performer : undefined,
    scheduled_at: scheduledFor,
    performed_at: performedAt,
    reported_at: reportedAt,
    regulation_request_id:
      def.category === 'imaging' && random() > 0.5 ? `reg_${ulid()}` : undefined,
    appointment_id: scheduledFor ? `apt_${ulid()}` : undefined,
    results,
    issues,
    status_history: history,
    cycle_times: {
      request_to_schedule: hoursBetween(
        requestedAt,
        history.find((h) => h.status === 'scheduled')?.occurred_at,
      ),
      schedule_to_perform: hoursBetween(
        history.find((h) => h.status === 'scheduled')?.occurred_at,
        performedAt,
      ),
      perform_to_report: hoursBetween(performedAt, reportedAt),
      report_to_followup: hoursBetween(reportedAt, followupAt),
    },
    source_system: def.category === 'laboratory' ? 'LIS' : 'PEC',
    source_record_id: `ORM-${200000 + i}`,
  };
}

export const examOrders: ExamOrder[] = Array.from({ length: 48 }, (_, i) => makeOrder(i));

// Pedido 0: laudo final CRÍTICO com documento (potássio sérico fora da faixa).
const critical = examOrders[0]!;
const reqAt = daysAgo(9);
const r0 = new Date(reqAt).getTime();
const h = (hours: number) => iso(new Date(r0 + hours * 3600_000));
Object.assign(critical, {
  exam_code: '0202010643',
  exam_description: 'Potássio sérico',
  category: 'laboratory',
  priority: 'urgent',
  care_line: 'hipertensão',
  status: 'reported',
  requested_at: reqAt,
  performer_cnes: '2219522',
  scheduled_at: h(20),
  performed_at: h(26),
  reported_at: h(30),
  appointment_id: `apt_${ulid()}`,
  issues: ['critical', 'no_result_followup'],
  status_history: [
    { status: 'requested', occurred_at: reqAt },
    { status: 'authorized', occurred_at: h(2) },
    { status: 'scheduled', occurred_at: h(4) },
    { status: 'collected', occurred_at: h(25) },
    { status: 'performed', occurred_at: h(26) },
    { status: 'reported', occurred_at: h(30), reason: 'Valor crítico sinalizado pelo LIS' },
  ],
  cycle_times: {
    request_to_schedule: 4,
    schedule_to_perform: 22,
    perform_to_report: 4,
    report_to_followup: undefined,
  },
  results: [
    {
      id: `res_${ulid()}`,
      status: 'final',
      reported_at: h(30),
      performer_cnes: '2219522',
      source_system: 'LIS',
      has_document: true,
      observations_count: 1,
      critical: true,
      followup_task_id: undefined,
    },
  ],
  source_system: 'LIS',
  source_record_id: 'ORM-CRIT-001',
} satisfies Partial<ExamOrder>);

// Pedido 1: não agendado há 15 dias.
Object.assign(examOrders[1]!, {
  status: 'requested',
  requested_at: daysAgo(15),
  scheduled_at: undefined,
  performed_at: undefined,
  reported_at: undefined,
  performer_cnes: undefined,
  appointment_id: undefined,
  results: [],
  issues: ['not_scheduled'],
  status_history: [{ status: 'requested', occurred_at: daysAgo(15) }],
  cycle_times: {},
} satisfies Partial<ExamOrder>);

/** URL assinada curta (mock). */
export function signedDocument(resultId: string): {
  url: string;
  expires_at: string;
  content_type: string;
} {
  return {
    url: `https://documents.sus-nexus.invalid/signed/${resultId}?token=${ulid().toLowerCase()}`,
    expires_at: new Date(Date.now() + 5 * 60_000).toISOString(),
    content_type: 'application/pdf',
  };
}
