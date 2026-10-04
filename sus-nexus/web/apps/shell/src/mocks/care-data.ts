import type {
  CareGap,
  CareGapKind,
  CarePlan,
  CarePlanItem,
  CitizenDetail,
  HospitalEpisode,
  Protocol,
  ProtocolItemRule,
  Task,
} from '@sus-nexus/api-client';
import { NOW, citizens, daysAgo, hoursAgo, iso, rng, summaries, tasks } from './data';

/**
 * Dados sintéticos da Fase 3: episódios hospitalares (visão da APS), protocolos de linha de
 * cuidado, planos de cuidado e lacunas (busca ativa). Coerentes com os cidadãos de `data.ts`:
 * os planos respeitam sexo/idade, as lacunas herdam UBS/equipe/microárea do cidadão e o resumo
 * operacional (`summaries`) é recalculado a partir destes dados.
 *
 * Usa um gerador próprio para não alterar a sequência aleatória dos demais módulos.
 */
const random = rng(20261005);
const int = (min: number, max: number) => min + Math.floor(random() * (max - min + 1));
const ULID_CHARS = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';
function ulid(): string {
  let s = '';
  for (let i = 0; i < 26; i++) s += ULID_CHARS[Math.floor(random() * ULID_CHARS.length)];
  return s;
}
const DAY = 24 * 3600_000;
const addDays = (isoDate: string, days: number) =>
  iso(new Date(new Date(isoDate).getTime() + days * DAY));

export const RISK_RULE_VERSION = 'hos-risk-1.2.0';
export const POST_DISCHARGE_SLA_HOURS = 72;

function ageOf(c: CitizenDetail): number {
  const b = new Date(`${c.birthdate ?? '1970-01-01'}T12:00:00`);
  let age = NOW.getFullYear() - b.getFullYear();
  const m = NOW.getMonth() - b.getMonth();
  if (m < 0 || (m === 0 && NOW.getDate() < b.getDate())) age -= 1;
  return age;
}

/** Contato válido = sem apontamento de qualidade no contato e com celular cadastrado. */
export function contactValid(c: CitizenDetail): boolean {
  const hasMobile = (c.contacts ?? []).some((x) => x.kind === 'mobile');
  const dq = (c.data_quality_issues ?? []).some((i) => i.field === 'contacts');
  return hasMobile && !dq;
}

/** Avaliação restrita de elegibilidade (sexo, faixa etária) — o core real usa a mesma semântica. */
export function isEligible(c: CitizenDetail, eligibility: Protocol['eligibility']): boolean {
  if (!eligibility) return true;
  const age = ageOf(c);
  const sex = eligibility.sex;
  if (typeof sex === 'string' && c.sex !== sex) return false;
  const min = eligibility.min_age;
  if (typeof min === 'number' && age < min) return false;
  const max = eligibility.max_age;
  if (typeof max === 'number' && age > max) return false;
  return true;
}

// ---------- protocolos ----------

const prenatalItems: ProtocolItemRule[] = [
  {
    kind: 'consultation',
    title: '1ª consulta de pré-natal',
    code: '0301010110',
    code_system: 'SIGTAP',
    due_in_days: 0,
    gap_after_days: 7,
    priority: 'high',
  },
  {
    kind: 'exam',
    title: 'Testes rápidos (HIV, sífilis, hepatites)',
    code: '0214010040',
    code_system: 'SIGTAP',
    due_in_days: 7,
    gap_after_days: 14,
    priority: 'high',
  },
  {
    kind: 'exam',
    title: 'Ultrassonografia obstétrica',
    code: '0205020143',
    code_system: 'SIGTAP',
    due_in_days: 30,
    gap_after_days: 14,
    priority: 'medium',
  },
  {
    kind: 'consultation',
    title: 'Consulta de pré-natal (mensal)',
    due_in_days: 30,
    periodicity_days: 30,
    gap_after_days: 10,
    priority: 'high',
  },
  {
    kind: 'vaccine',
    title: 'Vacina dTpa',
    code: 'dTpa',
    code_system: 'PNI',
    due_in_days: 140,
    gap_after_days: 30,
    priority: 'medium',
  },
  {
    kind: 'education',
    title: 'Avaliação odontológica na gestação',
    due_in_days: 60,
    gap_after_days: 30,
    priority: 'low',
  },
];

const hasItems: ProtocolItemRule[] = [
  {
    kind: 'consultation',
    title: 'Consulta de acompanhamento (HAS)',
    code: '0301010064',
    code_system: 'SIGTAP',
    due_in_days: 0,
    periodicity_days: 180,
    gap_after_days: 30,
    priority: 'medium',
  },
  {
    kind: 'exam',
    title: 'Creatinina sérica',
    code: '0202010317',
    code_system: 'SIGTAP',
    due_in_days: 30,
    periodicity_days: 365,
    gap_after_days: 30,
    priority: 'medium',
  },
  {
    kind: 'exam',
    title: 'Eletrocardiograma',
    code: '0211020036',
    code_system: 'SIGTAP',
    due_in_days: 60,
    periodicity_days: 365,
    gap_after_days: 30,
    priority: 'low',
  },
  {
    kind: 'home_visit',
    title: 'Visita domiciliar do ACS (aferição de PA)',
    due_in_days: 30,
    periodicity_days: 30,
    gap_after_days: 15,
    priority: 'low',
  },
];

const dmItems: ProtocolItemRule[] = [
  {
    kind: 'consultation',
    title: 'Consulta de acompanhamento (DM)',
    code: '0301010064',
    code_system: 'SIGTAP',
    due_in_days: 0,
    periodicity_days: 90,
    gap_after_days: 30,
    priority: 'medium',
  },
  {
    kind: 'exam',
    title: 'Hemoglobina glicada (HbA1c)',
    code: '0202010503',
    code_system: 'SIGTAP',
    due_in_days: 30,
    periodicity_days: 180,
    gap_after_days: 30,
    priority: 'high',
  },
  {
    kind: 'procedure',
    title: 'Avaliação do pé diabético',
    due_in_days: 90,
    periodicity_days: 365,
    gap_after_days: 30,
    priority: 'medium',
  },
  {
    kind: 'exam',
    title: 'Fundoscopia (retinografia)',
    code: '0405010010',
    code_system: 'SIGTAP',
    due_in_days: 120,
    periodicity_days: 365,
    gap_after_days: 60,
    priority: 'medium',
  },
];

export const protocols: Protocol[] = [
  {
    id: 'prot_prenatal',
    care_line: 'pre_natal',
    name: 'Pré-natal de risco habitual',
    version: '2.1.0',
    status: 'active',
    description: 'Consultas, exames e vacinas da gestação de risco habitual (Rede Cegonha).',
    eligibility: { sex: 'female', min_age: 10, max_age: 55 },
    items: prenatalItems,
    lost_to_followup_days: 45,
    test_cases_count: 12,
    approved_by: 'user_gestora',
    effective_from: daysAgo(120),
    created_at: daysAgo(150),
  },
  {
    id: 'prot_prenatal',
    care_line: 'pre_natal',
    name: 'Pré-natal de risco habitual',
    version: '2.2.0',
    status: 'approved',
    description: 'Inclui avaliação odontológica até a 20ª semana.',
    eligibility: { sex: 'female', min_age: 10, max_age: 55 },
    items: prenatalItems.map((i) =>
      i.kind === 'education' ? { ...i, due_in_days: 45, priority: 'medium' as const } : i,
    ),
    lost_to_followup_days: 45,
    test_cases_count: 14,
    approved_by: 'user_gestora',
    created_at: daysAgo(10),
  },
  {
    id: 'prot_has',
    care_line: 'hipertensao',
    name: 'Hipertensão arterial sistêmica',
    version: '1.3.0',
    status: 'revoked',
    eligibility: { min_age: 18 },
    items: hasItems.slice(0, 2),
    lost_to_followup_days: 365,
    test_cases_count: 6,
    approved_by: 'user_gestora',
    effective_from: daysAgo(500),
    created_at: daysAgo(520),
  },
  {
    id: 'prot_has',
    care_line: 'hipertensao',
    name: 'Hipertensão arterial sistêmica',
    version: '1.4.0',
    status: 'active',
    description: 'Acompanhamento semestral, exames anuais e visita mensal do ACS.',
    eligibility: { min_age: 18 },
    items: hasItems,
    lost_to_followup_days: 365,
    test_cases_count: 8,
    approved_by: 'user_gestora',
    effective_from: daysAgo(200),
    created_at: daysAgo(210),
  },
  {
    id: 'prot_has',
    care_line: 'hipertensao',
    name: 'Hipertensão arterial sistêmica',
    version: '1.5.0',
    status: 'in_review',
    description: 'Proposta: incluir potássio sérico anual.',
    eligibility: { min_age: 18 },
    items: [
      ...hasItems,
      {
        kind: 'exam',
        title: 'Potássio sérico',
        code: '0202010600',
        code_system: 'SIGTAP',
        due_in_days: 30,
        periodicity_days: 365,
        gap_after_days: 30,
        priority: 'low',
      },
    ],
    lost_to_followup_days: 365,
    // Sem casos de teste: a aprovação deve ser bloqueada (plano 8.3).
    test_cases_count: 0,
    created_at: daysAgo(5),
  },
  {
    id: 'prot_dm',
    care_line: 'diabetes',
    name: 'Diabetes mellitus tipo 2',
    version: '1.2.0',
    status: 'active',
    description: 'Consultas trimestrais, HbA1c semestral, pé diabético e fundoscopia anuais.',
    eligibility: { min_age: 18 },
    items: dmItems,
    lost_to_followup_days: 180,
    test_cases_count: 10,
    approved_by: 'user_gestora',
    effective_from: daysAgo(90),
    created_at: daysAgo(100),
  },
  {
    id: 'prot_dm',
    care_line: 'diabetes',
    name: 'Diabetes mellitus tipo 2',
    version: '1.3.0',
    status: 'draft',
    description: 'Rascunho: microalbuminúria anual.',
    eligibility: { min_age: 18 },
    items: dmItems,
    lost_to_followup_days: 180,
    test_cases_count: 3,
    created_at: daysAgo(2),
  },
];

export const activeProtocol = (careLine: string) =>
  protocols.find((p) => p.care_line === careLine && p.status === 'active');

// ---------- planos de cuidado + lacunas ----------

const KIND_TO_GAP: Record<CarePlanItem['kind'], CareGapKind> = {
  consultation: 'consultation_overdue',
  exam: 'exam_overdue',
  vaccine: 'vaccine_overdue',
  return: 'return_overdue',
  home_visit: 'no_contact',
  procedure: 'consultation_overdue',
  education: 'consultation_overdue',
  other: 'consultation_overdue',
};

/** Tipo da lacuna: perda de seguimento quando o atraso passa do limite do protocolo. */
export function gapKindFor(
  kind: CarePlanItem['kind'],
  late: number,
  protocol?: Protocol,
): CareGapKind {
  if (protocol?.lost_to_followup_days && late > protocol.lost_to_followup_days)
    return 'lost_to_followup';
  return KIND_TO_GAP[kind];
}

export const carePlans: CarePlan[] = [];
export const careGaps: CareGap[] = [];
/** Vínculo lacuna → item do plano (detalhe interno do mock; não está no contrato). */
export const gapItemLink = new Map<string, { planId: string; itemId: string }>();

const baseCitizens = citizens.filter(
  (c) => c.registration_state !== 'duplicate' && c.registration_state !== 'divergent',
);

export function buildPlanItems(protocol: Protocol, startAt: string, simulate: boolean) {
  return protocol.items.map((rule): CarePlanItem & { gap_after_days: number } => {
    const expected = addDays(startAt, rule.due_in_days);
    const past = new Date(expected).getTime() < NOW.getTime();
    let status: CarePlanItem['status'] = 'planned';
    let performedAt: string | undefined;
    if (simulate && past) {
      const roll = random();
      if (roll < 0.5) {
        status = 'done';
        performedAt = addDays(expected, -int(0, 3));
      } else if (roll < 0.6) status = 'scheduled';
    }
    return {
      id: `cpi_${ulid()}`,
      kind: rule.kind,
      title: rule.title,
      code: rule.code,
      code_system: rule.code_system,
      expected_by: expected,
      periodicity_days: rule.periodicity_days,
      status,
      performed_at: performedAt,
      evidence_ref: performedAt ? `fhir://aps/Encounter/${ulid()}` : undefined,
      overdue: status !== 'done' && past,
      gap_after_days: rule.gap_after_days ?? 30,
    };
  });
}

function addPlan(c: CitizenDetail, careLine: string, startDaysAgo: number) {
  const protocol = activeProtocol(careLine);
  if (!protocol) return;
  const start = daysAgo(startDaysAgo);
  const built = buildPlanItems(protocol, start, true);
  const plan: CarePlan = {
    id: `cp_${ulid()}`,
    citizen_id: c.id,
    care_line: careLine,
    status: 'active',
    protocol_id: protocol.id,
    protocol_version: protocol.version,
    health_unit_cnes: c.health_unit_cnes,
    team_ine: c.team_ine,
    responsible_professional_id: 'prof_enf_ana',
    origin: { kind: 'professional', id: 'prof_enf_ana' },
    items: built.map(({ gap_after_days: _g, ...item }) => item),
    open_gaps: 0,
    created_at: start,
    updated_at: hoursAgo(int(1, 72)),
    version: 1,
  };
  for (const item of built) {
    if (!item.overdue || !item.expected_by) continue;
    const late = Math.floor((NOW.getTime() - new Date(item.expected_by).getTime()) / DAY);
    if (late < item.gap_after_days) continue;
    const gap: CareGap = {
      id: `gap_${ulid()}`,
      citizen_id: c.id,
      citizen_display_name: c.display_name,
      care_plan_id: plan.id,
      care_line: careLine,
      gap_kind: gapKindFor(item.kind, late, protocol),
      status: 'open',
      expected_by: item.expected_by,
      days_overdue: late,
      protocol_id: protocol.id,
      protocol_version: protocol.version,
      health_unit_cnes: c.health_unit_cnes,
      team_ine: c.team_ine,
      microarea: c.microarea,
      detected_at: addDays(item.expected_by, item.gap_after_days),
      contact_valid: contactValid(c),
    };
    careGaps.push(gap);
    gapItemLink.set(gap.id, { planId: plan.id, itemId: item.id });
  }
  plan.open_gaps = careGaps.filter((g) => g.care_plan_id === plan.id).length;
  carePlans.push(plan);
}

const pregnant = baseCitizens.filter((c) =>
  isEligible(c, { sex: 'female', min_age: 15, max_age: 42 }),
);
pregnant.slice(0, 3).forEach((c, i) => addPlan(c, 'pre_natal', 40 + i * 35));
const adults = baseCitizens.filter((c) => ageOf(c) >= 40 && !pregnant.slice(0, 3).includes(c));
adults.slice(0, 7).forEach((c, i) => addPlan(c, 'hipertensao', 120 + i * 40));
adults
  .filter((c) => ageOf(c) >= 45)
  .filter((_, i) => i % 2 === 0)
  .slice(0, 4)
  .forEach((c, i) => addPlan(c, 'diabetes', 90 + i * 50));

// Perda de seguimento (sem plano ativo vinculado) para cobrir o tipo na busca ativa.
const lost = adults[7] ?? adults[0];
if (lost) {
  careGaps.push({
    id: `gap_${ulid()}`,
    citizen_id: lost.id,
    citizen_display_name: lost.display_name,
    care_line: 'hipertensao',
    gap_kind: 'lost_to_followup',
    status: 'open',
    expected_by: daysAgo(400),
    days_overdue: 400,
    protocol_id: 'prot_has',
    protocol_version: '1.4.0',
    health_unit_cnes: lost.health_unit_cnes,
    team_ine: lost.team_ine,
    microarea: lost.microarea,
    detected_at: daysAgo(35),
    contact_valid: contactValid(lost),
  });
}

// ---------- episódios hospitalares ----------

const HOSPITAL = { cnes: '2200385', name: 'Hospital Municipal Alpheu de Quadros' };
const UPA = { cnes: '6412345', name: 'UPA Norte' };

interface EpisodeSpec {
  risk?: HospitalEpisode['risk_level'];
  status: HospitalEpisode['status'];
  episodeClass: HospitalEpisode['episode_class'];
  /** Horas desde a alta (ausente = ainda internado). */
  dischargedHoursAgo?: number;
  los: number;
  cid?: string;
  followup?: NonNullable<HospitalEpisode['followup']>['status'];
  outcome?: string;
  counterReferral?: boolean;
  readmissionOf?: number;
  careLine?: string;
}

/**
 * Episódio 0: alto risco, alta há 4 dias, **sem contato no SLA de 72 h** (tarefa vencida).
 * Episódio 1: reinternação em 30 dias (do episódio 8), **sem CID no payload** (política).
 */
const specs: EpisodeSpec[] = [
  {
    risk: 'high',
    status: 'discharged',
    episodeClass: 'inpatient',
    dischargedHoursAgo: 96,
    los: 8,
    cid: 'I50.0',
    followup: 'pending',
    careLine: 'hipertensao',
  },
  {
    risk: 'high',
    status: 'discharged',
    episodeClass: 'inpatient',
    dischargedHoursAgo: 20,
    los: 5,
    followup: 'pending',
    readmissionOf: 8,
  },
  {
    risk: 'medium',
    status: 'discharged',
    episodeClass: 'inpatient',
    dischargedHoursAgo: 50,
    los: 4,
    cid: 'J18.9',
    followup: 'contacted',
    outcome: 'contact_made',
    counterReferral: true,
  },
  {
    risk: 'medium',
    status: 'discharged',
    episodeClass: 'inpatient',
    dischargedHoursAgo: 140,
    los: 6,
    cid: 'E11.9',
    followup: 'scheduled',
    outcome: 'appointment_scheduled',
    counterReferral: true,
    careLine: 'diabetes',
  },
  {
    risk: 'low',
    status: 'discharged',
    episodeClass: 'emergency',
    dischargedHoursAgo: 10,
    los: 0,
    cid: 'R10.4',
    followup: 'pending',
  },
  {
    risk: 'low',
    status: 'discharged',
    episodeClass: 'inpatient',
    dischargedHoursAgo: 360,
    los: 3,
    cid: 'N39.0',
    followup: 'closed',
    outcome: 'moved',
  },
  { status: 'admitted', episodeClass: 'inpatient', los: 2, cid: 'I63.9' },
  {
    risk: 'high',
    status: 'discharged',
    episodeClass: 'inpatient',
    dischargedHoursAgo: 80,
    los: 12,
    cid: 'I21.9',
    followup: 'escalated',
    outcome: 'not_found',
  },
  {
    risk: 'medium',
    status: 'discharged',
    episodeClass: 'inpatient',
    dischargedHoursAgo: 480,
    los: 7,
    cid: 'J44.1',
    followup: 'closed',
    outcome: 'contact_made',
  },
  {
    risk: 'low',
    status: 'discharged',
    episodeClass: 'observation',
    dischargedHoursAgo: 26,
    los: 1,
    followup: 'pending',
    counterReferral: true,
  },
];

// Prioriza cidadãos da UBS Vila Oliveira (lotação padrão do usuário fake) e de planos ativos.
const byUnitFirst = [...baseCitizens].sort((a, b) => {
  const score = (c: CitizenDetail) =>
    (c.health_unit_cnes === '2126672' ? 0 : 2) +
    (carePlans.some((p) => p.citizen_id === c.id) ? 0 : 1);
  return score(a) - score(b);
});

export const hospitalEpisodes: HospitalEpisode[] = [];
const episodeCitizens: CitizenDetail[] = [];
specs.forEach((spec, i) => {
  // Reinternação: mesmo cidadão do episódio anterior.
  const c = byUnitFirst[i] as CitizenDetail;
  episodeCitizens.push(c);
  const unit = spec.episodeClass === 'emergency' ? UPA : HOSPITAL;
  const dischargedAt =
    spec.dischargedHoursAgo !== undefined ? hoursAgo(spec.dischargedHoursAgo) : undefined;
  const admittedAt = dischargedAt
    ? addDays(dischargedAt, -Math.max(spec.los, 0.3))
    : daysAgo(spec.los);
  const ward = spec.episodeClass === 'emergency' ? 'Sala amarela' : 'Clínica médica';
  const bed = `${int(1, 4)}0${int(1, 9)}`;
  const movements: NonNullable<HospitalEpisode['movements']> = [
    { movement: 'admit', occurred_at: admittedAt, ward, bed },
  ];
  if (spec.los >= 4)
    movements.push({
      movement: 'bed_change',
      occurred_at: addDays(admittedAt, 1),
      ward: 'Unidade de cuidados intermediários',
      bed: `UCI-${int(1, 8)}`,
    });
  if (dischargedAt) movements.push({ movement: 'discharge', occurred_at: dischargedAt, ward, bed });
  const due = dischargedAt
    ? iso(new Date(new Date(dischargedAt).getTime() + POST_DISCHARGE_SLA_HOURS * 3600_000))
    : undefined;
  const episode: HospitalEpisode = {
    id: `hep_${ulid()}`,
    citizen_id: c.id,
    hospital_cnes: unit.cnes,
    hospital_name: unit.name,
    episode_class: spec.episodeClass,
    status: spec.status,
    admitted_at: admittedAt,
    discharged_at: dischargedAt,
    length_of_stay_days: dischargedAt ? spec.los : undefined,
    disposition: dischargedAt
      ? spec.followup === 'closed' && i === 5
        ? 'home'
        : 'home_with_care'
      : undefined,
    ward,
    bed,
    admission_source:
      spec.episodeClass === 'emergency' ? 'emergency' : i % 3 === 0 ? 'regulation' : 'emergency',
    principal_diagnosis_cid: spec.cid,
    aih_number: spec.episodeClass === 'inpatient' ? `3126${int(100000000, 999999999)}` : undefined,
    readmission_within_30d: spec.readmissionOf !== undefined,
    reference_health_unit_cnes: c.health_unit_cnes,
    reference_team_ine: c.team_ine,
    risk_level: spec.risk,
    risk_rule_version: spec.risk ? RISK_RULE_VERSION : undefined,
    followup:
      spec.followup && due
        ? {
            status: spec.followup,
            due_at: due,
            outcome: spec.outcome,
            contacted_at: spec.outcome && dischargedAt ? addDays(dischargedAt, 1) : undefined,
          }
        : undefined,
    counter_referral: spec.counterReferral
      ? {
          received_at: dischargedAt ? addDays(dischargedAt, 0.2) : undefined,
          has_document: true,
          recommendations_count: int(2, 5),
        }
      : undefined,
    has_summary_document: Boolean(dischargedAt) && spec.episodeClass !== 'emergency',
    movements,
    source_system: 'HIS',
    source_record_id: `his-${int(100000, 999999)}`,
    version: int(1, 4),
  };
  hospitalEpisodes.push(episode);
});

// Reinternação: o episódio 1 é do mesmo cidadão do episódio 8 (alta há 20 dias).
const ep1 = hospitalEpisodes[1];
const ep8 = hospitalEpisodes[8];
if (ep1 && ep8) {
  ep8.citizen_id = ep1.citizen_id;
  ep8.reference_health_unit_cnes = ep1.reference_health_unit_cnes;
  ep8.reference_team_ine = ep1.reference_team_ine;
  ep1.previous_episode_id = ep8.id;
}

// Tarefas pós-alta vinculadas (fila operacional + Workbench) e lacuna "pós-alta sem contato".
const followupTaskStatus: Record<string, Task['status']> = {
  pending: 'open',
  contacted: 'completed',
  scheduled: 'completed',
  closed: 'completed',
  escalated: 'escalated',
};
for (const ep of hospitalEpisodes) {
  if (!ep.followup?.status || !ep.followup.due_at) continue;
  const c = citizens.find((x) => x.id === ep.citizen_id) as CitizenDetail;
  const status = followupTaskStatus[ep.followup.status] ?? 'open';
  const overdue = status === 'open' && new Date(ep.followup.due_at).getTime() < NOW.getTime();
  const task: Task = {
    id: `task_${ulid()}`,
    task_type: 'post_discharge_followup',
    status,
    priority: ep.risk_level === 'high' ? 'high' : 'medium',
    title: 'Contato pós-alta hospitalar em 72h',
    description: `Alta em ${ep.hospital_name ?? ep.hospital_cnes}. Contatar o cidadão e registrar o desfecho.`,
    citizen_id: ep.citizen_id,
    assignee: { kind: 'team', id: `team_${ep.reference_team_ine ?? c.team_ine}` },
    due_at: ep.followup.due_at,
    sla_policy_id: 'sla_pos_alta_72h',
    overdue,
    origin: { kind: 'rule', id: 'rule_pos_alta_72h', version: '1.3.0' },
    outcome: ep.followup.outcome,
    created_at: ep.discharged_at ?? ep.admitted_at,
    updated_at: hoursAgo(int(0, 6)),
    version: 1,
  };
  tasks.push(task);
  ep.followup.task_id = task.id;
  const plan = carePlans.find((p) => p.citizen_id === ep.citizen_id && p.status === 'active');
  if (plan) ep.followup.care_plan_id = plan.id;
  if (overdue) {
    careGaps.push({
      id: `gap_${ulid()}`,
      citizen_id: c.id,
      citizen_display_name: c.display_name,
      care_plan_id: plan?.id,
      care_line: 'pos_alta',
      gap_kind: 'post_discharge_no_contact',
      status: 'open',
      expected_by: ep.followup.due_at,
      days_overdue: Math.max(
        1,
        Math.floor((NOW.getTime() - new Date(ep.followup.due_at).getTime()) / DAY),
      ),
      protocol_id: 'rule_pos_alta_72h',
      protocol_version: '1.3.0',
      health_unit_cnes: c.health_unit_cnes,
      team_ine: c.team_ine,
      microarea: c.microarea,
      task_id: task.id,
      detected_at: ep.followup.due_at,
      contact_valid: contactValid(c),
    });
  }
}

/** Recalcula o resumo operacional (Fase 3) a partir dos episódios, planos e lacunas. */
export function refreshSummary(citizenId: string) {
  const s = summaries.get(citizenId);
  const c = citizens.find((x) => x.id === citizenId);
  if (!s || !c) return;
  const discharges = hospitalEpisodes
    .filter((e) => e.citizen_id === citizenId && e.discharged_at)
    .map((e) => e.discharged_at as string)
    .sort();
  s.last_hospital_discharge_at = discharges.at(-1);
  s.care_lines = [
    ...new Set(
      carePlans
        .filter((p) => p.citizen_id === citizenId && p.status === 'active')
        .map((p) => p.care_line),
    ),
  ];
  s.care_gaps = careGaps.filter((g) => g.citizen_id === citizenId && g.status === 'open').length;
  s.contact_valid = contactValid(c);
  s.open_tasks = tasks.filter(
    (t) => t.citizen_id === citizenId && t.status !== 'completed' && t.status !== 'cancelled',
  ).length;
}
for (const c of citizens) refreshSummary(c.id);

/** Dias de atraso relativos ao "agora" do mock. */
export function daysOverdue(expectedBy: string | undefined): number {
  if (!expectedBy) return 0;
  return Math.max(0, Math.floor((NOW.getTime() - new Date(expectedBy).getTime()) / DAY));
}

export { ageOf, episodeCitizens };
