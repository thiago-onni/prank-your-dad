import type {
  ProductionBatch,
  ProductionDeadline,
  ProductionIssue,
  ProductionKind,
  ProductionRecord,
  ProductionRecordStatus,
  ProductionSummary,
} from '@sus-nexus/api-client';
import { NOW, healthUnits, iso, rng } from './data';

/**
 * Dados sintéticos da Fase 4 (produção e pré-auditoria BPA-C/BPA-I/APAC/AIH). CNS de cidadão e
 * de profissional chegam **apenas mascarados**, como entrega o core (`*_masked`). Usa gerador
 * próprio para não alterar a sequência aleatória dos demais módulos.
 */
const random = rng(20261006);
const int = (min: number, max: number) => min + Math.floor(random() * (max - min + 1));
const ULID_CHARS = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';
function ulid(): string {
  let s = '';
  for (let i = 0; i < 26; i++) s += ULID_CHARS[Math.floor(random() * ULID_CHARS.length)];
  return s;
}
const DAY = 24 * 3600_000;
const at = (isoDate: string, days = 0) => iso(new Date(new Date(isoDate).getTime() + days * DAY));

export const PRODUCTION_RULE_VERSION = 'production-validation/1';
/** Competência corrente em apresentação (setembro/2026, prazo em outubro). */
export const CURRENT_COMPETENCE = '202609';

const unitName = (cnes: string) => healthUnits.find((u) => u.cnes === cnes)?.name;

interface ProcedureDef {
  code: string;
  display: string;
  kind: ProductionKind;
  cbo: string;
  value: number;
  cnes: string;
}

const PROCEDURES: ProcedureDef[] = [
  {
    code: '0301010064',
    display: 'Consulta médica em atenção primária',
    kind: 'bpa_i',
    cbo: '225142',
    value: 10,
    cnes: '2126672',
  },
  {
    code: '0301010030',
    display: 'Consulta de profissional de nível superior na atenção primária (exceto médico)',
    kind: 'bpa_i',
    cbo: '223505',
    value: 6.3,
    cnes: '2126699',
  },
  {
    code: '0214010015',
    display: 'Glicemia capilar',
    kind: 'bpa_c',
    cbo: '322205',
    value: 1.85,
    cnes: '2126699',
  },
  {
    code: '0305010107',
    display: 'Hemodiálise (máximo 3 sessões por semana)',
    kind: 'apac',
    cbo: '225109',
    value: 218.47,
    cnes: '2219522',
  },
  {
    code: '0303140151',
    display: 'Tratamento de pneumonias ou influenza (gripe)',
    kind: 'aih',
    cbo: '225125',
    value: 486.21,
    cnes: '2200385',
  },
];

const maskedCns = () => `*** **** **** ${String(int(0, 9999)).padStart(4, '0')}`;

export function issue(
  record: Pick<ProductionRecord, 'id' | 'competence' | 'cnes' | 'kind' | 'procedure_code'>,
  rule_id: string,
  severity: ProductionIssue['severity'],
  field: string,
  message: string,
  origin: ProductionIssue['origin'] = 'rule',
): ProductionIssue {
  return {
    id: `pis_${ulid()}`,
    production_record_id: record.id,
    rule_id,
    rule_version: PRODUCTION_RULE_VERSION,
    severity,
    field,
    message,
    status: 'open',
    origin,
    task_id: severity === 'error' ? `task_${ulid()}` : undefined,
    created_at: at(iso(NOW), -int(1, 6)),
    competence: record.competence,
    cnes: record.cnes,
    kind: record.kind,
    procedure_code: record.procedure_code,
  };
}

function makeRecord(
  proc: ProcedureDef,
  competence: string,
  status: ProductionRecordStatus,
  overrides: Partial<ProductionRecord> = {},
): ProductionRecord {
  const year = Number(competence.slice(0, 4));
  const month = Number(competence.slice(4));
  const day = int(1, 28);
  const attendance = `${year}-${String(month).padStart(2, '0')}-${String(day).padStart(2, '0')}`;
  const quantity = proc.kind === 'bpa_c' ? int(10, 120) : 1;
  const individual = proc.kind !== 'bpa_c';
  const id = `prod_${ulid()}`;
  const record: ProductionRecord = {
    id,
    kind: proc.kind,
    competence,
    cnes: proc.cnes,
    health_unit_name: unitName(proc.cnes),
    professional_cns_masked: maskedCns(),
    professional_cbo: proc.cbo,
    procedure_code: proc.code,
    procedure_display: proc.display,
    quantity,
    citizen_id: individual ? `cit_${ulid()}` : undefined,
    citizen_identifier_masked: individual ? maskedCns() : undefined,
    cid_code: proc.kind === 'apac' ? 'N18.5' : proc.kind === 'aih' ? 'J18.9' : undefined,
    attendance_date: attendance,
    character_of_care: proc.kind === 'aih' ? 'urgency' : 'elective',
    apac_number: proc.kind === 'apac' ? `31${String(int(10 ** 10, 10 ** 11 - 1))}` : undefined,
    aih_number: proc.kind === 'aih' ? `31${String(int(10 ** 10, 10 ** 11 - 1))}` : undefined,
    status,
    rule_version: PRODUCTION_RULE_VERSION,
    validated_at: status === 'pending' ? undefined : `${attendance}T18:00:00-03:00`,
    unit_value: proc.value,
    estimated_value: Math.round(proc.value * quantity * 100) / 100,
    correction_count: 0,
    issues: [],
    history: [
      {
        action: 'registered',
        to_status: 'generated',
        actor_id: 'connector-esus-aps',
        occurred_at: `${attendance}T17:00:00-03:00`,
      },
      {
        action: 'pre_audit',
        from_status: 'generated',
        to_status: status === 'pending' ? 'pending' : 'validated',
        actor_id: 'system',
        rule_version: PRODUCTION_RULE_VERSION,
        occurred_at: `${attendance}T17:00:05-03:00`,
      },
    ],
    source_system: proc.kind === 'aih' ? 'his-hospital-municipal' : 'esus-aps',
    source_record_id: `src-${ulid().slice(0, 10).toLowerCase()}`,
    version: 1,
    ...overrides,
  };
  return record;
}

const [CONSULTA_MED, CONSULTA_ENF, GLICEMIA, HEMODIALISE, PNEUMONIA] = PROCEDURES as [
  ProcedureDef,
  ProcedureDef,
  ProcedureDef,
  ProcedureDef,
  ProcedureDef,
];

export const productionRecords: ProductionRecord[] = [];
export const productionBatches: ProductionBatch[] = [];

// ---------- 08/2026: lote exportado e retorno oficial ----------
const exportedBatchId = `pbt_${ulid()}`;
const aug: ProductionRecord[] = [];
for (let i = 0; i < 6; i++) {
  const status: ProductionRecordStatus = i < 3 ? 'paid' : i < 5 ? 'exported' : 'rejected';
  const r = makeRecord(CONSULTA_MED, '202608', status, { batch_id: exportedBatchId });
  if (status === 'paid') {
    r.paid_amount = r.estimated_value;
    r.approved_quantity = r.quantity;
  }
  if (status === 'rejected') {
    r.outcome_reason_code = '0105';
    r.outcome_reason = 'CNS do usuário inválido ou inexistente na base nacional';
    r.issues = [
      issue(
        r,
        'official_rejection',
        'error',
        'citizen_ref',
        'Rejeitado no SIA: CNS do usuário inválido ou inexistente na base nacional (motivo 0105).',
        'official_return',
      ),
    ];
  }
  aug.push(r);
}
productionRecords.push(...aug);
productionBatches.push({
  id: exportedBatchId,
  competence: '202608',
  cnes: CONSULTA_MED.cnes,
  kind: 'bpa_i',
  status: 'exported',
  records_count: aug.length,
  total_quantity: aug.reduce((s, r) => s + r.quantity, 0),
  estimated_value: aug.reduce((s, r) => s + (r.estimated_value ?? 0), 0),
  record_ids: aug.map((r) => r.id),
  created_by: 'auditor.ana',
  created_at: '2026-09-03T10:00:00-03:00',
  approved_by: 'gestor.joao',
  approved_at: '2026-09-04T15:20:00-03:00',
  approval_justification: 'Conferido com o relatório do e-SUS APS da competência.',
  export: {
    layout: 'bpa_mag_v202412',
    file_ref: 'exports/ibge_3143302/202608/bpa_i_2126672.txt',
    sha256: 'b5d4045c3f466fa91fe2cc6abe79232a1a57cdf104f7a26e716e0a1e2789df78',
    size_bytes: 2_310,
    lines: 7,
    lines_missing_identifiers: 0,
    exported_by: 'auditor.ana',
    exported_at: '2026-09-05T09:10:00-03:00',
  },
  protocol_number: 'SIA-2026-08-000123',
  version: 3,
});

// ---------- 09/2026: competência em apresentação ----------
const draftBatchId = `pbt_${ulid()}`;
const draftRecords = Array.from({ length: 4 }, () =>
  makeRecord(CONSULTA_MED, CURRENT_COMPETENCE, 'validated', { batch_id: draftBatchId }),
);
productionRecords.push(...draftRecords);
productionBatches.push({
  id: draftBatchId,
  competence: CURRENT_COMPETENCE,
  cnes: CONSULTA_MED.cnes,
  kind: 'bpa_i',
  status: 'draft',
  records_count: draftRecords.length,
  total_quantity: draftRecords.reduce((s, r) => s + r.quantity, 0),
  estimated_value: draftRecords.reduce((s, r) => s + (r.estimated_value ?? 0), 0),
  record_ids: draftRecords.map((r) => r.id),
  created_by: 'auditor.ana',
  created_at: at(iso(NOW), -1),
  version: 1,
});

const approvedBatchId = `pbt_${ulid()}`;
const approvedRecords = Array.from({ length: 3 }, () =>
  makeRecord(GLICEMIA, CURRENT_COMPETENCE, 'validated', { batch_id: approvedBatchId }),
);
productionRecords.push(...approvedRecords);
productionBatches.push({
  id: approvedBatchId,
  competence: CURRENT_COMPETENCE,
  cnes: GLICEMIA.cnes,
  kind: 'bpa_c',
  status: 'approved',
  records_count: approvedRecords.length,
  total_quantity: approvedRecords.reduce((s, r) => s + r.quantity, 0),
  estimated_value: approvedRecords.reduce((s, r) => s + (r.estimated_value ?? 0), 0),
  record_ids: approvedRecords.map((r) => r.id),
  created_by: 'auditor.ana',
  created_at: at(iso(NOW), -2),
  approved_by: 'gestor.joao',
  approved_at: at(iso(NOW), -1),
  approval_justification: 'Totais conferidos com a planilha da unidade.',
  version: 2,
});

// Validados ainda fora de lote (elegíveis para gerar lote BPA-I da UBS Santos Reis).
for (let i = 0; i < 3; i++) {
  productionRecords.push(makeRecord(CONSULTA_ENF, CURRENT_COMPETENCE, 'validated'));
}
productionRecords.push(makeRecord(HEMODIALISE, CURRENT_COMPETENCE, 'validated'));

// Pendências: CBO incompatível (erro) + quantidade acima do usual (aviso).
{
  const r = makeRecord(CONSULTA_MED, CURRENT_COMPETENCE, 'pending', {
    professional_cbo: '223505',
  });
  r.issues = [
    issue(
      r,
      'cbo_incompatible',
      'error',
      'professional_cbo',
      'CBO 223505 (enfermeiro) incompatível com o procedimento 0301010064 na tabela SIGTAP.',
    ),
  ];
  productionRecords.push(r);
}
{
  // Somente aviso: pode ser validado dispensando o aviso com justificativa.
  const r = makeRecord(GLICEMIA, CURRENT_COMPETENCE, 'pending', { quantity: 480 });
  r.estimated_value = Math.round(GLICEMIA.value * 480 * 100) / 100;
  r.issues = [
    issue(
      r,
      'quantity_above_usual',
      'warning',
      'quantity',
      'Quantidade 480 acima do usual para a unidade na competência (média 60).',
    ),
  ];
  productionRecords.push(r);
}
{
  const r = makeRecord(HEMODIALISE, CURRENT_COMPETENCE, 'pending', { cid_code: undefined });
  r.issues = [
    issue(r, 'cid_required', 'error', 'cid_code', 'CID-10 obrigatório para procedimento de APAC.'),
    issue(
      r,
      'attendance_outside_competence',
      'warning',
      'attendance_date',
      'Data do atendimento próxima ao limite da competência; confira a data informada.',
    ),
  ];
  productionRecords.push(r);
}
{
  const r = makeRecord(PNEUMONIA, CURRENT_COMPETENCE, 'pending', {
    citizen_identifier_masked: undefined,
    citizen_id: undefined,
  });
  r.issues = [
    issue(
      r,
      'citizen_unresolved',
      'error',
      'citizen_ref',
      'Cidadão não identificado: CNS/CPF ausente ou inválido para AIH.',
    ),
  ];
  productionRecords.push(r);
}
for (let i = 0; i < 2; i++) {
  productionRecords.push(makeRecord(PNEUMONIA, CURRENT_COMPETENCE, 'validated'));
}

// ---------- 10/2026: competência aberta ----------
for (let i = 0; i < 3; i++) {
  productionRecords.push(
    makeRecord(
      i === 0 ? CONSULTA_ENF : CONSULTA_MED,
      '202610',
      i === 0 ? 'generated' : 'validated',
    ),
  );
}

export const productionDeadlines: ProductionDeadline[] = [
  {
    competence: '202607',
    deadline_at: '2026-08-10T23:59:00-03:00',
    status: 'closed',
    days_remaining: -55,
    alert_days: [5, 1],
    configured_by: 'global',
  },
  {
    competence: '202608',
    deadline_at: '2026-09-10T23:59:00-03:00',
    status: 'closed',
    days_remaining: -24,
    alert_days: [5, 1],
    configured_by: 'global',
  },
  {
    competence: CURRENT_COMPETENCE,
    deadline_at: '2026-10-09T23:59:00-03:00',
    status: 'closing',
    days_remaining: 5,
    alert_days: [5, 1],
    configured_by: 'tenant',
  },
  {
    competence: '202610',
    deadline_at: '2026-11-10T23:59:00-03:00',
    status: 'open',
    days_remaining: 37,
    alert_days: [5, 1],
    configured_by: 'global',
  },
];

/** Recalcula deadlines (pendentes/validados) e devolve a lista. */
export function deadlinesWithCounts(): ProductionDeadline[] {
  return productionDeadlines.map((d) => {
    const recs = productionRecords.filter((r) => r.competence === d.competence);
    return {
      ...d,
      pending_records: recs.filter((r) => r.status === 'pending').length,
      validated_records: recs.filter((r) => r.status === 'validated').length,
    };
  });
}

const round = (n: number) => Math.round(n * 100) / 100;

export function computeSummary(competence: string, cnes?: string | null): ProductionSummary {
  const recs = productionRecords.filter(
    (r) => r.competence === competence && (!cnes || r.cnes === cnes),
  );
  const count = (s: ProductionRecordStatus) => recs.filter((r) => r.status === s).length;
  const sum = (list: ProductionRecord[], f: (r: ProductionRecord) => number | undefined) =>
    round(list.reduce((s, r) => s + (f(r) ?? 0), 0));
  const deadline = productionDeadlines.find((d) => d.competence === competence);
  const pending = recs.filter((r) => r.status === 'pending');
  const rejected = recs.filter((r) => r.status === 'rejected');
  const issuesByRule = new Map<string, { rule_id: string; severity: string; open: number }>();
  for (const r of recs) {
    for (const i of r.issues ?? []) {
      if (i.status !== 'open') continue;
      const cur = issuesByRule.get(i.rule_id) ?? {
        rule_id: i.rule_id,
        severity: i.severity,
        open: 0,
      };
      cur.open += 1;
      issuesByRule.set(i.rule_id, cur);
    }
  }
  const kinds: ProductionKind[] = ['bpa_c', 'bpa_i', 'apac', 'aih'];
  return {
    competence,
    cnes: cnes ?? undefined,
    deadline_at: deadline?.deadline_at,
    days_to_deadline: deadline?.days_remaining,
    totals: {
      records: recs.length,
      generated: count('generated'),
      validated: count('validated'),
      pending: count('pending'),
      exported: count('exported'),
      transmitted: count('transmitted'),
      received: count('received'),
      rejected: count('rejected'),
      corrected: recs.filter((r) => (r.correction_count ?? 0) > 0).length,
      approved: count('approved'),
      paid: count('paid'),
    },
    values: {
      estimated: sum(recs, (r) => r.estimated_value),
      validated: sum(
        recs.filter((r) => r.status === 'validated'),
        (r) => r.estimated_value,
      ),
      paid: sum(recs, (r) => r.paid_amount),
      pending: sum(pending, (r) => r.estimated_value),
      rejected: sum(rejected, (r) => r.estimated_value),
      avoidable_loss_estimated: sum([...pending, ...rejected], (r) => r.estimated_value),
    },
    issues_by_rule: [...issuesByRule.values()].sort((a, b) => b.open - a.open),
    by_kind: kinds
      .map((kind) => {
        const list = recs.filter((r) => r.kind === kind);
        return { kind, records: list.length, estimated: sum(list, (r) => r.estimated_value) };
      })
      .filter((k) => k.records > 0),
  };
}

/** SHA-256 sintético (64 hex) determinístico por lote. */
export function fakeSha256(seed: string): string {
  let h = 2166136261;
  for (const ch of seed) h = Math.imul(h ^ ch.charCodeAt(0), 16777619) >>> 0;
  const gen = rng(h);
  return Array.from({ length: 64 }, () => Math.floor(gen() * 16).toString(16)).join('');
}

export const newBatchId = () => `pbt_${ulid()}`;
export const newIssueId = () => `pis_${ulid()}`;
