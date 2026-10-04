import type {
  CitizenDetail,
  CitizenOperationalSummary,
  CitizenSummary,
  ConnectorStatus,
  DeadLetter,
  HealthUnit,
  IntegrationMessage,
  IntegrationMessageStatus,
  MergeCase,
  ReconciliationEntry,
  RuleSet,
  Task,
  TimelineEvent,
} from '@sus-nexus/api-client';

/**
 * Dados sintéticos brasileiros para desenvolvimento sem backend e testes.
 * Nenhum dado real: nomes, CNS e CPF são fictícios e já chegam mascarados,
 * como faria o core (`value_masked`).
 */

// Gerador determinístico (mulberry32) para dados estáveis entre execuções.
function rng(seed: number) {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}
const random = rng(20261004);
const pick = <T>(arr: readonly T[]): T => arr[Math.floor(random() * arr.length)] as T;
const int = (min: number, max: number) => min + Math.floor(random() * (max - min + 1));

const ULID_CHARS = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';
function ulid(): string {
  let s = '';
  for (let i = 0; i < 26; i++) s += ULID_CHARS[Math.floor(random() * ULID_CHARS.length)];
  return s;
}
const id = (prefix: string) => `${prefix}_${ulid()}`;

const NOW = new Date('2026-10-04T09:30:00-03:00');
const iso = (d: Date) => d.toISOString().replace('Z', '+00:00');
const hoursAgo = (h: number) => iso(new Date(NOW.getTime() - h * 3600_000));
const daysAgo = (d: number) => hoursAgo(d * 24);
const daysAhead = (d: number) => iso(new Date(NOW.getTime() + d * 24 * 3600_000));

export const healthUnits: HealthUnit[] = [
  {
    id: id('hu'),
    cnes: '2126672',
    name: 'UBS Vila Oliveira',
    kind_code: '02',
    kind_description: 'Centro de Saúde/Unidade Básica',
    address: 'Rua das Acácias, 120 — Vila Oliveira',
    active: true,
  },
  {
    id: id('hu'),
    cnes: '2126699',
    name: 'UBS Santos Reis',
    kind_code: '02',
    kind_description: 'Centro de Saúde/Unidade Básica',
    address: 'Av. Dulce Sarmento, 850 — Santos Reis',
    active: true,
  },
  {
    id: id('hu'),
    cnes: '2126710',
    name: 'UBS Major Prates',
    kind_code: '02',
    kind_description: 'Centro de Saúde/Unidade Básica',
    address: 'Rua Coronel Prates, 45 — Major Prates',
    active: true,
  },
  {
    id: id('hu'),
    cnes: '2219522',
    name: 'Policlínica Municipal',
    kind_code: '36',
    kind_description: 'Clínica/Centro de Especialidade',
    address: 'Av. Mestra Fininha, 1500 — Centro',
    active: true,
  },
  {
    id: id('hu'),
    cnes: '2200385',
    name: 'Hospital Municipal Alpheu de Quadros',
    kind_code: '05',
    kind_description: 'Hospital Geral',
    address: 'Rua Coronel Spyer, 100 — Centro',
    active: true,
  },
  {
    id: id('hu'),
    cnes: '6412345',
    name: 'UPA Norte',
    kind_code: '73',
    kind_description: 'Pronto Atendimento',
    address: 'Av. Deputado Esteves Rodrigues, 2000 — Independência',
    active: true,
  },
];
const ubs = healthUnits.filter((h) => h.kind_code === '02');
export const unitByCnes = (cnes: string | undefined) => healthUnits.find((h) => h.cnes === cnes);

const femaleNames = [
  'Maria Aparecida',
  'Ana Clara',
  'Francisca',
  'Antônia',
  'Juliana',
  'Luciana',
  'Patrícia',
  'Rosângela',
  'Terezinha',
  'Vanessa',
  'Josefa',
  'Camila',
];
const maleNames = [
  'José Carlos',
  'João Batista',
  'Antônio',
  'Francisco',
  'Carlos Eduardo',
  'Paulo Roberto',
  'Pedro Henrique',
  'Luiz Fernando',
  'Marcos Vinícius',
  'Raimundo',
  'Sebastião',
  'Geraldo',
];
const surnames = [
  'da Silva',
  'dos Santos',
  'Oliveira',
  'Souza',
  'Pereira',
  'Ferreira',
  'Rodrigues',
  'Alves',
  'Gomes',
  'Ribeiro',
  'Martins',
  'Carvalho',
  'Lima',
  'Araújo',
  'Nascimento',
];
const motherNames = [
  'Maria das Dores',
  'Josefa Maria',
  'Antônia Rosa',
  'Francisca de Jesus',
  'Raimunda Nonata',
  'Terezinha de Jesus',
  'Sebastiana Alves',
];

function maskName(name: string): string {
  return name
    .split(' ')
    .map((p) => (p.length <= 2 ? p : `${p[0]}${'*'.repeat(Math.min(p.length - 1, 4))}`))
    .join(' ');
}
const maskedCpf = () => `***.***.***-${String(int(0, 99)).padStart(2, '0')}`;
const maskedCns = () => `*** **** **** ${String(int(0, 9999)).padStart(4, '0')}`;

function makeCitizen(i: number, overrides: Partial<CitizenDetail> = {}): CitizenDetail {
  const female = random() > 0.5;
  const first = female ? pick(femaleNames) : pick(maleNames);
  const s1 = pick(surnames);
  let s2 = pick(surnames);
  while (s2 === s1) s2 = pick(surnames);
  const legal = `${first} ${s1} ${s2}`;
  const year = int(1940, 2020);
  const birthdate = `${year}-${String(int(1, 12)).padStart(2, '0')}-${String(int(1, 28)).padStart(2, '0')}`;
  const unit = pick(ubs);
  const mother = pick(motherNames);
  const citizenId = `cit_${ulid()}`;
  const social = i % 7 === 3 ? (female ? 'Bia' : 'Alex') + ' ' + legal.split(' ')[1] : undefined;
  return {
    id: citizenId,
    version: int(1, 5),
    display_name: social ?? legal,
    legal_name: legal,
    social_name: social,
    birthdate,
    sex: female ? 'female' : 'male',
    mother_name: mother,
    mother_name_masked: maskName(mother),
    registration_state: pick([
      'validated',
      'validated',
      'validated',
      'divergent',
      'incomplete',
      'pending',
    ] as const),
    identity_confidence: pick([
      'confirmed',
      'confirmed',
      'probable',
      'pending',
      'divergent',
    ] as const),
    identifiers: [
      {
        id: id('cid'),
        system: 'CNS',
        value_masked: maskedCns(),
        status: 'active',
        source_system: 'CADSUS',
        valid_from: daysAgo(int(400, 3000)),
      },
      {
        id: id('cid'),
        system: 'CPF',
        value_masked: maskedCpf(),
        status: 'active',
        source_system: 'PEC',
        valid_from: daysAgo(int(400, 3000)),
      },
      {
        id: id('cid'),
        system: 'PEC',
        value_masked: String(int(100000, 999999)),
        status: 'active',
        source_system: 'PEC',
      },
    ],
    health_unit_cnes: unit.cnes,
    team_ine: `000${int(1000000, 9999999)}`,
    microarea: String(int(1, 8)).padStart(2, '0'),
    address: {
      street: `Rua ${pick(['das Flores', 'Sete de Setembro', 'Tiradentes', 'Santa Rita', 'Padre Chiquinho'])}`,
      number: String(int(10, 999)),
      district: unit.name.replace('UBS ', ''),
      city_ibge: '3143302',
      postal_code: '39400-***',
    },
    contacts: [
      { kind: 'mobile', value_masked: `(38) 9****-${int(1000, 9999)}`, preferred: true },
      ...(random() > 0.6
        ? [
            {
              kind: 'email' as const,
              value_masked: `${first.split(' ')[0]?.toLowerCase()}***@***.com`,
              preferred: false,
            },
          ]
        : []),
    ],
    attribute_provenance: {
      legal_name: { source_system: 'CADSUS', received_at: daysAgo(int(30, 400)), confidence: 0.98 },
      birthdate: { source_system: 'CADSUS', received_at: daysAgo(int(30, 400)), confidence: 0.95 },
      mother_name: {
        source_system: 'PEC',
        source_record_id: `pec-${int(10000, 99999)}`,
        received_at: daysAgo(int(5, 90)),
        confidence: 0.9,
      },
      address: {
        source_system: 'PEC',
        source_record_id: `pec-${int(10000, 99999)}`,
        received_at: daysAgo(int(1, 30)),
        confidence: 0.8,
      },
      contacts: { source_system: 'SISREG', received_at: daysAgo(int(1, 60)), confidence: 0.6 },
      health_unit_cnes: {
        source_system: 'PEC',
        received_at: daysAgo(int(1, 30)),
        confidence: 0.92,
      },
    },
    data_quality_issues:
      random() > 0.7
        ? [
            {
              rule: 'DQ-CONTACT-001',
              field: 'contacts',
              message: 'Telefone sem DDD válido na origem SISREG',
            },
          ]
        : [],
    ...overrides,
  };
}

export const citizens: CitizenDetail[] = Array.from({ length: 24 }, (_, i) => makeCitizen(i));
// Cidadãos de duplicidade: pares com nomes semelhantes e mesma mãe.
const dupBase = citizens[0]!;
const dupBaseName = dupBase.legal_name ?? dupBase.display_name;
const dup1 = makeCitizen(100, {
  legal_name: dupBaseName.replace('da Silva', 'Silva'),
  display_name: dupBaseName.replace('da Silva', 'Silva'),
  social_name: undefined,
  birthdate: dupBase.birthdate,
  mother_name: dupBase.mother_name,
  mother_name_masked: dupBase.mother_name_masked,
  registration_state: 'duplicate',
  identity_confidence: 'pending',
});
const dup2Base = citizens[1]!;
const dup2 = makeCitizen(101, {
  legal_name: dup2Base.legal_name,
  display_name: dup2Base.legal_name,
  social_name: undefined,
  birthdate: dup2Base.birthdate?.replace(/-\d{2}$/, '-15'),
  mother_name: dup2Base.mother_name,
  mother_name_masked: dup2Base.mother_name_masked,
  registration_state: 'divergent',
  identity_confidence: 'divergent',
});
citizens.push(dup1, dup2);

export const toSummary = (c: CitizenDetail): CitizenSummary => ({
  id: c.id,
  display_name: c.display_name,
  birthdate: c.birthdate,
  sex: c.sex,
  mother_name_masked: c.mother_name_masked,
  registration_state: c.registration_state,
  identifiers: c.identifiers,
  health_unit_cnes: c.health_unit_cnes,
  team_ine: c.team_ine,
  microarea: c.microarea,
  identity_confidence: c.identity_confidence,
});

export const mergeCases: MergeCase[] = [
  {
    id: id('case'),
    status: 'open',
    reason: 'Nome e nome da mãe semelhantes; CNS distintos',
    score: 0.91,
    rule_version: 'mpi-rules-v3.2',
    candidates: [toSummary(dupBase), toSummary(dup1)],
    evidence: [
      {
        attribute: 'legal_name',
        comparison: 'Jaro-Winkler 0,97',
        agreement: 'agree',
        weight: 0.35,
      },
      { attribute: 'birthdate', comparison: 'igual', agreement: 'agree', weight: 0.3 },
      { attribute: 'mother_name', comparison: 'igual', agreement: 'agree', weight: 0.25 },
      { attribute: 'CNS', comparison: 'diferente', agreement: 'disagree', weight: 0.1 },
    ],
    conflicts: ['CNS'],
    opened_at: daysAgo(2),
  },
  {
    id: id('case'),
    status: 'open',
    reason: 'Mesmo CPF; data de nascimento divergente',
    score: 0.78,
    rule_version: 'mpi-rules-v3.2',
    candidates: [toSummary(dup2Base), toSummary(dup2)],
    evidence: [
      { attribute: 'CPF', comparison: 'igual', agreement: 'agree', weight: 0.5 },
      { attribute: 'legal_name', comparison: 'igual', agreement: 'agree', weight: 0.3 },
      {
        attribute: 'birthdate',
        comparison: 'dia diferente (12 vs 15)',
        agreement: 'disagree',
        weight: 0.2,
      },
    ],
    conflicts: ['birthdate'],
    opened_at: daysAgo(5),
  },
  {
    id: id('case'),
    status: 'in_review',
    reason: 'Possível homônimo na mesma microárea',
    score: 0.66,
    rule_version: 'mpi-rules-v3.1',
    candidates: [toSummary(citizens[2]!), toSummary(citizens[3]!)],
    evidence: [
      { attribute: 'legal_name', comparison: 'Jaro-Winkler 0,88', agreement: 'agree', weight: 0.4 },
      {
        attribute: 'mother_name',
        comparison: 'ausente em uma fonte',
        agreement: 'missing',
        weight: 0.2,
      },
    ],
    conflicts: [],
    opened_at: daysAgo(9),
  },
  {
    id: id('case'),
    status: 'merged',
    reason: 'Duplicidade confirmada presencialmente',
    score: 0.97,
    rule_version: 'mpi-rules-v3.1',
    candidates: [toSummary(citizens[4]!), toSummary(citizens[5]!)],
    evidence: [],
    conflicts: [],
    opened_at: daysAgo(20),
    decided_at: daysAgo(18),
    decided_by: 'user_7f3a',
    decision_reason: 'Documentos conferidos na UBS; cidadão confirmou cadastro duplicado.',
    merge_id: id('merge'),
  },
];

export const connectors: ConnectorStatus[] = [
  {
    connector_id: 'pec-esus',
    connector_version: '1.4.2',
    source_system: 'e-SUS PEC',
    health: 'healthy',
    last_message_at: hoursAgo(0.1),
    received_24h: 18420,
    failed_24h: 12,
    dlq_open: 2,
    reconciliation_gap: 0,
  },
  {
    connector_id: 'sisreg',
    connector_version: '1.2.0',
    source_system: 'SISREG',
    health: 'degraded',
    last_message_at: hoursAgo(1.5),
    received_24h: 2310,
    failed_24h: 184,
    dlq_open: 31,
    reconciliation_gap: 57,
  },
  {
    connector_id: 'his-hospital',
    connector_version: '0.9.7',
    source_system: 'HIS Hospital Municipal',
    health: 'down',
    last_message_at: hoursAgo(14),
    received_24h: 0,
    failed_24h: 0,
    dlq_open: 0,
    reconciliation_gap: 412,
  },
  {
    connector_id: 'cadsus',
    connector_version: '2.0.1',
    source_system: 'CADSUS',
    health: 'healthy',
    last_message_at: hoursAgo(0.5),
    received_24h: 920,
    failed_24h: 1,
    dlq_open: 0,
    reconciliation_gap: 0,
  },
  {
    connector_id: 'lab-lis',
    connector_version: '1.0.3',
    source_system: 'LIS Laboratório',
    health: 'healthy',
    last_message_at: hoursAgo(0.3),
    received_24h: 4015,
    failed_24h: 3,
    dlq_open: 0,
    reconciliation_gap: 2,
  },
  {
    connector_id: 'esus-regulacao',
    connector_version: '0.5.0',
    source_system: 'e-SUS Regulação',
    health: 'unknown',
    received_24h: 0,
    failed_24h: 0,
    dlq_open: 0,
    reconciliation_gap: 0,
  },
];

const entityTypes = [
  'Patient',
  'Encounter',
  'Appointment',
  'ServiceRequest',
  'Observation',
  'Procedure',
];
const stages = ['receive', 'transform', 'validate', 'publish', 'process'];
const errorCodes: { code: string; message: string }[] = [
  { code: 'FHIR_VALIDATION', message: 'Patient.identifier[0].value não corresponde ao padrão CNS' },
  { code: 'IDENTITY_UNRESOLVED', message: 'Nenhum cidadão correspondente; caso de revisão aberto' },
  { code: 'SCHEMA_MISMATCH', message: 'Campo obrigatório ausente: Encounter.period.start' },
  { code: 'UPSTREAM_TIMEOUT', message: 'Tempo limite ao consultar o sistema de origem (30s)' },
  { code: 'CNES_UNKNOWN', message: 'CNES 9999999 não existe na base de referência' },
];

export const integrationMessages: IntegrationMessage[] = Array.from({ length: 240 }, (_, i) => {
  const connector = pick(connectors);
  const status: IntegrationMessageStatus = pick([
    'processed',
    'processed',
    'processed',
    'processed',
    'processed',
    'published',
    'validated',
    'failed',
    'failed',
    'dead_lettered',
    'reprocessing',
    'received',
  ] as const);
  const receivedAt = hoursAgo(random() * 72);
  const failed = status === 'failed' || status === 'dead_lettered';
  const err = pick(errorCodes);
  return {
    id: `msg_${ulid()}`,
    connector_id: connector.connector_id,
    source_system: connector.source_system,
    source_record_id: `${connector.connector_id.toUpperCase()}-${100000 + i}`,
    entity_type: pick(entityTypes),
    status,
    raw_ref: `s3://sus-nexus-raw/${connector.connector_id}/2026/10/${ulid().toLowerCase()}.json`,
    raw_sha256: Array.from({ length: 64 }, () => '0123456789abcdef'[int(0, 15)]).join(''),
    correlation_id: `corr-${ulid().toLowerCase()}`,
    received_at: receivedAt,
    processed_at:
      status === 'received'
        ? undefined
        : iso(new Date(new Date(receivedAt).getTime() + int(200, 90000))),
    attempts: failed ? int(1, 5) : 1,
    last_error: failed ? { ...err, stage: pick(stages), occurred_at: receivedAt } : undefined,
  };
}).sort((a, b) => (a.received_at < b.received_at ? 1 : -1));

export const deadLetters: DeadLetter[] = integrationMessages
  .filter((m) => m.status === 'dead_lettered')
  .map((m) => ({
    id: `dlq_${ulid()}`,
    message_id: m.id,
    topic: `sus.${m.entity_type === 'Patient' ? 'identity.citizen' : m.entity_type === 'Appointment' ? 'schedule.appointment' : 'aps.encounter'}.v1`,
    reason: m.last_error?.message ?? 'Erro desconhecido',
    stage: m.last_error?.stage,
    attempts: m.attempts ?? 3,
    owner: pick(['equipe-integracao', 'equipe-cadastro', undefined]),
    payload_ref: m.raw_ref,
    created_at: m.received_at,
    triaged_at: random() > 0.5 ? hoursAgo(random() * 10) : undefined,
  }));

export const reconciliation: ReconciliationEntry[] = connectors.flatMap((c) =>
  [1, 2, 3].map((d) => {
    const source = int(500, 20000);
    const gap = c.reconciliation_gap && d === 1 ? c.reconciliation_gap : int(0, 3);
    return {
      connector_id: c.connector_id,
      entity_type: pick(entityTypes),
      period_start: daysAgo(d),
      period_end: daysAgo(d - 1),
      source_count: source,
      bus_count: source - gap,
      gap,
      checked_at: hoursAgo(d * 24 - 23),
    };
  }),
);

const taskTypes = [
  'busca_ativa',
  'confirmar_agendamento',
  'revisar_duplicidade',
  'pos_alta',
  'triar_dlq',
  'validar_cadastro',
];
const taskTitles: Record<string, string> = {
  busca_ativa: 'Busca ativa — gestante sem consulta há 45 dias',
  confirmar_agendamento: 'Confirmar presença em consulta especializada',
  revisar_duplicidade: 'Revisar possível duplicidade de cadastro',
  pos_alta: 'Contato pós-alta hospitalar em 72h',
  triar_dlq: 'Triar mensagem em DLQ (SISREG)',
  validar_cadastro: 'Validar cadastro incompleto',
};

export const tasks: Task[] = Array.from({ length: 36 }, () => {
  const type = pick(taskTypes);
  const status = pick([
    'open',
    'open',
    'assigned',
    'in_progress',
    'completed',
    'escalated',
    'cancelled',
  ] as const);
  const dueOffset = int(-48, 96);
  const due = iso(new Date(NOW.getTime() + dueOffset * 3600_000));
  const closed = status === 'completed' || status === 'cancelled';
  return {
    id: `task_${ulid()}`,
    task_type: type,
    status,
    priority: pick(['low', 'medium', 'medium', 'high', 'urgent'] as const),
    title: taskTitles[type] ?? type,
    description:
      'Tarefa gerada automaticamente pelo motor de regras a partir de eventos do barramento.',
    citizen_id: type === 'triar_dlq' ? undefined : pick(citizens).id,
    assignee:
      status === 'open'
        ? { kind: 'team', id: 'team_esf_01' }
        : {
            kind: pick(['user', 'team', 'health_unit', 'queue'] as const),
            id: pick(['user_ana', 'team_esf_02', 'hu_2126672', 'queue_regulacao']),
          },
    due_at: due,
    sla_policy_id: 'sla_v2',
    overdue: !closed && dueOffset < 0,
    origin: {
      kind: pick(['rule', 'workflow', 'agent', 'user', 'connector'] as const),
      id: 'rule_pos_alta_72h',
      version: '1.3.0',
    },
    outcome: status === 'completed' ? 'Contato realizado' : undefined,
    created_at: hoursAgo(int(1, 200)),
    updated_at: hoursAgo(int(0, 1)),
    version: 1,
  };
});

const eventTemplates: {
  domain: TimelineEvent['domain'];
  event_type: string;
  summary: string;
  status: string;
  source: string;
  cnesPool: HealthUnit[];
}[] = [
  {
    domain: 'identity',
    event_type: 'sus.identity.citizen.updated',
    summary: 'Cadastro atualizado (endereço)',
    status: 'applied',
    source: 'PEC',
    cnesPool: ubs,
  },
  {
    domain: 'aps',
    event_type: 'sus.aps.encounter.recorded',
    summary: 'Consulta de enfermagem — hipertensão',
    status: 'finished',
    source: 'PEC',
    cnesPool: ubs,
  },
  {
    domain: 'aps',
    event_type: 'sus.aps.encounter.recorded',
    summary: 'Consulta médica — renovação de receita',
    status: 'finished',
    source: 'PEC',
    cnesPool: ubs,
  },
  {
    domain: 'aps',
    event_type: 'sus.aps.visit.recorded',
    summary: 'Visita domiciliar do ACS',
    status: 'finished',
    source: 'PEC',
    cnesPool: ubs,
  },
  {
    domain: 'schedule',
    event_type: 'sus.schedule.appointment.booked',
    summary: 'Agendamento — cardiologia',
    status: 'booked',
    source: 'SISREG',
    cnesPool: [healthUnits[3]!],
  },
  {
    domain: 'schedule',
    event_type: 'sus.schedule.appointment.noshow',
    summary: 'Falta em consulta — oftalmologia',
    status: 'noshow',
    source: 'SISREG',
    cnesPool: [healthUnits[3]!],
  },
  {
    domain: 'regulation',
    event_type: 'sus.regulation.request.created',
    summary: 'Pedido de regulação — ecocardiograma',
    status: 'pending',
    source: 'SISREG',
    cnesPool: ubs,
  },
  {
    domain: 'regulation',
    event_type: 'sus.regulation.request.authorized',
    summary: 'Pedido autorizado — ecocardiograma',
    status: 'authorized',
    source: 'SISREG',
    cnesPool: [healthUnits[3]!],
  },
  {
    domain: 'exam',
    event_type: 'sus.exam.result.available',
    summary: 'Resultado disponível — hemograma',
    status: 'final',
    source: 'LIS',
    cnesPool: [healthUnits[3]!],
  },
  {
    domain: 'hospital',
    event_type: 'sus.hospital.admission.recorded',
    summary: 'Internação — clínica médica',
    status: 'in-progress',
    source: 'HIS',
    cnesPool: [healthUnits[4]!],
  },
  {
    domain: 'hospital',
    event_type: 'sus.hospital.discharge.recorded',
    summary: 'Alta hospitalar',
    status: 'finished',
    source: 'HIS',
    cnesPool: [healthUnits[4]!],
  },
  {
    domain: 'careplan',
    event_type: 'sus.careplan.gap.detected',
    summary: 'Lacuna de cuidado — HbA1c vencida',
    status: 'open',
    source: 'core',
    cnesPool: ubs,
  },
  {
    domain: 'task',
    event_type: 'sus.task.created',
    summary: 'Tarefa criada — contato pós-alta',
    status: 'open',
    source: 'core',
    cnesPool: ubs,
  },
  {
    domain: 'communication',
    event_type: 'sus.communication.sms.sent',
    summary: 'SMS de lembrete enviado',
    status: 'sent',
    source: 'core',
    cnesPool: ubs,
  },
  {
    domain: 'production',
    event_type: 'sus.production.bpa.exported',
    summary: 'Produção exportada (BPA-I)',
    status: 'exported',
    source: 'core',
    cnesPool: ubs,
  },
];

export const timelineByCitizen = new Map<string, TimelineEvent[]>();
for (const c of citizens) {
  const events: TimelineEvent[] = [];
  const count = int(12, 28);
  for (let i = 0; i < count; i++) {
    const tpl = pick(eventTemplates);
    const unit = pick(tpl.cnesPool);
    const occurred = new Date(NOW.getTime() - random() * 400 * 24 * 3600_000);
    const lagHours = random() > 0.85 ? int(30, 200) : int(0, 6);
    const confidence = pick([
      'confirmed',
      'confirmed',
      'confirmed',
      'confirmed',
      'pending',
      'divergent',
      'unsynced',
    ] as const);
    events.push({
      id: `evt_${ulid()}`,
      citizen_id: c.id,
      domain: tpl.domain,
      event_type: tpl.event_type,
      occurred_at: iso(occurred),
      recorded_at: iso(new Date(occurred.getTime() + lagHours * 3600_000)),
      source_system: tpl.source,
      cnes: unit.cnes,
      health_unit_name: unit.name,
      professional_ref: random() > 0.5 ? `prof_${ulid()}` : undefined,
      status: tpl.status,
      confidence,
      sensitivity: tpl.domain === 'hospital' || tpl.domain === 'exam' ? 'restricted' : 'internal',
      summary: tpl.summary,
      detail_ref: `fhir://${tpl.domain}/${ulid()}`,
    });
  }
  events.sort((a, b) => (a.occurred_at < b.occurred_at ? 1 : -1));
  // Cadeias causa-efeito: regulação autorizada ← pedido criado ← consulta.
  for (let i = 0; i < events.length - 2; i++) {
    const e = events[i]!;
    if (e.domain === 'regulation' || e.domain === 'schedule') {
      e.correlation_chain = [events[i + 1]!.id, events[i + 2]!.id];
    }
  }
  timelineByCitizen.set(c.id, events);
}

export const summaries = new Map<string, CitizenOperationalSummary>(
  citizens.map((c) => {
    const events = timelineByCitizen.get(c.id) ?? [];
    const lastAps = events.find((e) => e.domain === 'aps');
    const lastDischarge = events.find((e) => e.event_type.endsWith('discharge.recorded'));
    return [
      c.id,
      {
        citizen_id: c.id,
        last_aps_encounter_at: lastAps?.occurred_at,
        next_appointment_at: random() > 0.4 ? daysAhead(int(1, 30)) : undefined,
        open_tasks: tasks.filter(
          (t) => t.citizen_id === c.id && t.status !== 'completed' && t.status !== 'cancelled',
        ).length,
        open_regulation_requests: events.filter(
          (e) => e.domain === 'regulation' && e.status === 'pending',
        ).length,
        pending_exams: int(0, 3),
        last_hospital_discharge_at: lastDischarge?.occurred_at,
        care_lines: pick([
          ['hipertensão'],
          ['diabetes', 'hipertensão'],
          ['pré-natal'],
          [],
          ['saúde mental'],
        ]),
        care_gaps: int(0, 3),
        contact_valid: random() > 0.2,
      },
    ];
  }),
);

export const ruleSets: RuleSet[] = [
  {
    id: 'rule_mpi',
    name: 'Regras de correspondência MPI',
    current_version: '3.2.0',
    status: 'active',
    effective_from: daysAgo(40),
    approved_by: 'user_gestora',
  },
  {
    id: 'rule_sla',
    name: 'Políticas de SLA de tarefas',
    current_version: '2.0.0',
    status: 'active',
    effective_from: daysAgo(90),
    approved_by: 'user_gestora',
  },
  {
    id: 'rule_pos_alta',
    name: 'Protocolo pós-alta 72h',
    current_version: '1.3.0',
    status: 'in_review',
    effective_from: daysAhead(10),
  },
];

/** Valor "em claro" fictício devolvido pelo endpoint de reveal (mock). */
export function fakeRevealValue(system: string, masked: string): string {
  const tail = masked.replace(/\D/g, '');
  if (system === 'CPF')
    return `${int(100, 999)}${int(100, 999)}${int(100, 999)}${tail.padStart(2, '0')}`;
  if (system === 'CNS')
    return `7${int(10000000000, 99999999999)}${tail.padStart(4, '0')}`.slice(0, 15);
  return masked;
}
