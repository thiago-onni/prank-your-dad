import { SITUATION_INDICATOR_CODES, type SituationIndicatorCode } from '@sus-nexus/api-client';
import { healthUnits, rng, teamsByUnit } from './data';

/**
 * Lakehouse sintético da Sala de Situação: linhas com o MESMO schema dos modelos dbt
 * (`data/dbt/models/marts_aggregated/*.sql`, `marts/dim_*.sql`), já com a supressão primária do
 * macro `suppress_small_cells` (0 < n < 5 → nulo; zero publicado). Dois municípios, para provar
 * que o BFF filtra pelo `municipality_id` do token.
 */

export const SMALL_CELL_THRESHOLD = 5;
export const MAIN_TENANT = 'ibge_3143302';
export const OTHER_TENANT = 'ibge_3106200';
const random = rng(20260926);

/** 12 competências até 09/2026 (último mês fechado em relação a 04/10/2026). */
export const SITUATION_COMPETENCES = Array.from({ length: 12 }, (_, i) => {
  const d = new Date(Date.UTC(2026, 8 - i, 1));
  return `${d.getUTCFullYear()}${String(d.getUTCMonth() + 1).padStart(2, '0')}`;
}).reverse();
export const LATEST_COMPETENCE = SITUATION_COMPETENCES[SITUATION_COMPETENCES.length - 1]!;
const monthStart = (c: string) => `${c.slice(0, 4)}-${c.slice(4)}-01`;

interface Meta {
  name: string;
  unit: 'proporcao' | 'dias';
  direction: 'maior_melhor' | 'menor_melhor';
  target: number;
  /** Onde o indicador é aberto por unidade. */
  units: 'aps' | 'hospital' | 'none';
  /** Valor municipal da última competência (define o semáforo de demonstração). */
  latest: number;
  kind?: 'percentile' | 'sum';
}

/** `seed_metas_indicadores.csv` + valores de demonstração. */
export const INDICATOR_META: Record<SituationIndicatorCode, Meta> = {
  AGE_ABSENTEISMO: {
    name: 'Taxa de absenteísmo (no-show)',
    unit: 'proporcao',
    direction: 'menor_melhor',
    target: 0.15,
    units: 'aps',
    latest: 0.21,
  },
  AGE_COMPARECIMENTO: {
    name: 'Taxa de comparecimento',
    unit: 'proporcao',
    direction: 'maior_melhor',
    target: 0.85,
    units: 'aps',
    latest: 0.79,
  },
  AGE_CANCELAMENTO: {
    name: 'Taxa de cancelamento',
    unit: 'proporcao',
    direction: 'menor_melhor',
    target: 0.1,
    units: 'aps',
    latest: 0.08,
  },
  AGE_REAPROVEITAMENTO: {
    name: 'Reaproveitamento de vagas canceladas',
    unit: 'proporcao',
    direction: 'maior_melhor',
    target: 0.5,
    units: 'aps',
    latest: 0.47,
  },
  REG_ESPERA_P50_DIAS: {
    name: 'Tempo de espera até agendamento (mediana)',
    unit: 'dias',
    direction: 'menor_melhor',
    target: 30,
    units: 'aps',
    latest: 27,
    kind: 'percentile',
  },
  REG_ESPERA_P90_DIAS: {
    name: 'Tempo de espera até agendamento (p90)',
    unit: 'dias',
    direction: 'menor_melhor',
    target: 90,
    units: 'aps',
    latest: 118,
    kind: 'percentile',
  },
  REG_SLA_CUMPRIDO: {
    name: 'Solicitações reguladas dentro do SLA',
    unit: 'proporcao',
    direction: 'maior_melhor',
    target: 0.8,
    units: 'aps',
    latest: 0.83,
  },
  REG_DEVOLUCAO: {
    name: 'Taxa de devolução de solicitações',
    unit: 'proporcao',
    direction: 'menor_melhor',
    target: 0.1,
    units: 'aps',
    latest: 0.12,
  },
  REG_REALIZACAO: {
    name: 'Solicitações encerradas com realização',
    unit: 'proporcao',
    direction: 'maior_melhor',
    target: 0.85,
    units: 'aps',
    latest: 0.88,
  },
  EXA_CICLO_COMPLETO: {
    name: 'Pedidos de exame com ciclo completo (retorno)',
    unit: 'proporcao',
    direction: 'maior_melhor',
    target: 0.6,
    units: 'aps',
    latest: 0.44,
  },
  EXA_RESULTADO_SEM_RETORNO: {
    name: 'Resultados sem retorno ao solicitante',
    unit: 'proporcao',
    direction: 'menor_melhor',
    target: 0.3,
    units: 'aps',
    latest: 0.31,
  },
  EXA_DIAS_PEDIDO_RESULTADO_P50: {
    name: 'Dias do pedido ao resultado (mediana)',
    unit: 'dias',
    direction: 'menor_melhor',
    target: 15,
    units: 'aps',
    latest: 12,
    kind: 'percentile',
  },
  HOS_REINTERNACAO_30D: {
    name: 'Reinternação em até 30 dias',
    unit: 'proporcao',
    direction: 'menor_melhor',
    target: 0.1,
    units: 'hospital',
    latest: 0.09,
  },
  HOS_CONTATO_POS_ALTA_7D: {
    name: 'Contato da APS em até 7 dias após a alta',
    unit: 'proporcao',
    direction: 'maior_melhor',
    target: 0.8,
    units: 'aps',
    latest: 0.62,
  },
  HOS_PERMANENCIA_MEDIA_DIAS: {
    name: 'Tempo médio de permanência (internação)',
    unit: 'dias',
    direction: 'menor_melhor',
    target: 5,
    units: 'hospital',
    latest: 5.3,
    kind: 'sum',
  },
  CUI_LACUNAS_RESOLVIDAS: {
    name: 'Lacunas de cuidado resolvidas',
    unit: 'proporcao',
    direction: 'maior_melhor',
    target: 0.6,
    units: 'aps',
    latest: 0.64,
  },
  TAR_SLA_CUMPRIDO: {
    name: 'Tarefas concluídas dentro do SLA',
    unit: 'proporcao',
    direction: 'maior_melhor',
    target: 0.85,
    units: 'none',
    latest: 0.87,
  },
  TAR_AUTOMACAO: {
    name: 'Tarefas criadas por automação (agente/workflow/regra/conector)',
    unit: 'proporcao',
    direction: 'maior_melhor',
    target: 0.5,
    units: 'none',
    latest: 0.55,
  },
  TAR_AGENTE_SLA_CUMPRIDO: {
    name: 'Tarefas criadas por agentes concluídas no SLA',
    unit: 'proporcao',
    direction: 'maior_melhor',
    target: 0.85,
    units: 'none',
    latest: 0.9,
  },
  PRO_GLOSA: {
    name: 'Taxa de glosa (rejeição) da produção',
    unit: 'proporcao',
    direction: 'menor_melhor',
    target: 0.05,
    units: 'aps',
    latest: 0.04,
  },
};

export type Row = Record<string, string | number | boolean | null>;

/** Mesmo comportamento do macro `suppress_small_cells`. */
export const suppress = (v: number | null, n: number | null = v) =>
  n !== null && n > 0 && n < SMALL_CELL_THRESHOLD ? null : v;

// ---------- dimensões ----------
const SANTA_CASA = { cnes: '2149990', name: 'Hospital Filantrópico Regional' };
const OTHER_UNITS = [
  { cnes: '9990011', name: 'UBS Centro (outro município)', role: 'aps' },
  { cnes: '9990022', name: 'UBS Bairro Alto (outro município)', role: 'aps' },
];
const APS_UNITS = healthUnits.filter((u) => u.kind_code === '02');
const HOSPITAL_UNITS = [
  ...healthUnits.filter((u) => u.cnes === '2200385').map((u) => ({ cnes: u.cnes, name: u.name })),
  SANTA_CASA,
];

export const dimHealthUnit: Row[] = [
  ...healthUnits.map((u) => ({
    health_unit_key: `${MAIN_TENANT}:${u.cnes}`,
    tenant_id: MAIN_TENANT,
    health_unit_cnes: u.cnes,
    health_unit_name: u.name,
    kind_code: u.kind_code ?? null,
    kind_description: u.kind_description ?? null,
    unit_role: u.cnes === '2200385' ? 'hospital' : u.kind_code === '02' ? 'aps' : 'outro',
    is_active: true,
    is_registered_in_core: true,
  })),
  {
    health_unit_key: `${MAIN_TENANT}:${SANTA_CASA.cnes}`,
    tenant_id: MAIN_TENANT,
    health_unit_cnes: SANTA_CASA.cnes,
    health_unit_name: SANTA_CASA.name,
    kind_code: null,
    kind_description: null,
    unit_role: 'hospital',
    is_active: true,
    is_registered_in_core: false,
  },
  ...OTHER_UNITS.map((u) => ({
    health_unit_key: `${OTHER_TENANT}:${u.cnes}`,
    tenant_id: OTHER_TENANT,
    health_unit_cnes: u.cnes,
    health_unit_name: u.name,
    kind_code: '02',
    kind_description: null,
    unit_role: u.role,
    is_active: true,
    is_registered_in_core: true,
  })),
];

export const dimTerritory: Row[] = [
  ...APS_UNITS.flatMap((u) =>
    (teamsByUnit[u.cnes] ?? []).map((t) => ({
      territory_key: `${MAIN_TENANT}:${u.cnes}:${t.ine}:-`,
      tenant_id: MAIN_TENANT,
      health_unit_key: `${MAIN_TENANT}:${u.cnes}`,
      health_unit_cnes: u.cnes,
      team_ine: t.ine,
      team_name: t.name,
      team_type: 'eSF',
      microarea: null,
      territory_level: 'equipe',
    })),
  ),
  ...OTHER_UNITS.map((u, i) => ({
    territory_key: `${OTHER_TENANT}:${u.cnes}:999000000${i}:-`,
    tenant_id: OTHER_TENANT,
    health_unit_key: `${OTHER_TENANT}:${u.cnes}`,
    health_unit_cnes: u.cnes,
    team_ine: `999000000${i}`,
    team_name: `ESF Outro Município ${i + 1}`,
    team_type: 'eSF',
    microarea: null,
    territory_level: 'equipe',
  })),
];

// ---------- agg_indicadores_mensais ----------
function indicatorRow(
  tenant: string,
  code: SituationIndicatorCode,
  competence: string,
  cnes: string | null,
  value: number,
  denominator: number,
): Row {
  const m = INDICATOR_META[code];
  const num =
    m.kind === 'percentile'
      ? null
      : m.kind === 'sum'
        ? Math.round(value * denominator)
        : Math.round(value * denominator);
  const v = m.kind === undefined && denominator > 0 ? (num ?? 0) / denominator : value;
  const small = denominator > 0 && denominator < SMALL_CELL_THRESHOLD;
  return {
    indicator_code: code,
    indicator_name: m.name,
    tenant_id: tenant,
    month_start: monthStart(competence),
    competence,
    aggregation_level: cnes ? 'unidade' : 'municipio',
    health_unit_cnes: cnes,
    health_unit_key: cnes ? `${tenant}:${cnes}` : null,
    numerator: small ? null : suppress(num),
    denominator: suppress(denominator),
    indicator_value: small ? null : Math.round(v * 10000) / 10000,
    is_suppressed: small,
    indicator_unit: m.unit,
    direction: m.direction,
    target_value: m.target,
    is_on_target: small ? null : m.direction === 'maior_melhor' ? v >= m.target : v <= m.target,
  };
}

const clampRate = (code: SituationIndicatorCode, v: number) =>
  INDICATOR_META[code].unit === 'proporcao' ? Math.min(0.99, Math.max(0.01, v)) : Math.max(0.5, v);

function buildIndicators(): Row[] {
  const rows: Row[] = [];
  for (const code of SITUATION_INDICATOR_CODES) {
    const m = INDICATOR_META[code];
    SITUATION_COMPETENCES.forEach((competence, idx) => {
      const isLatest = competence === LATEST_COMPETENCE;
      // Tendência suave até o valor de demonstração da última competência.
      const drift = (SITUATION_COMPETENCES.length - 1 - idx) * 0.01 * (random() - 0.5) * 4;
      const base = clampRate(code, isLatest ? m.latest : m.latest * (1 + drift));
      // Uma competência municipal suprimida de propósito (agentes: poucas tarefas avaliáveis).
      const muniDen =
        code === 'TAR_AGENTE_SLA_CUMPRIDO' && isLatest ? 3 : 200 + Math.floor(random() * 600);
      rows.push(indicatorRow(MAIN_TENANT, code, competence, null, base, muniDen));
      const units =
        m.units === 'aps'
          ? APS_UNITS.map((u) => u.cnes)
          : m.units === 'hospital'
            ? HOSPITAL_UNITS.map((u) => u.cnes)
            : [];
      units.forEach((cnes, ui) => {
        const factor = [0.75, 1, 1.35][ui % 3]! + (random() - 0.5) * 0.1;
        // UBS Major Prates com denominador pequeno no absenteísmo → célula suprimida.
        const den =
          code === 'AGE_ABSENTEISMO' && cnes === '2126710' && isLatest
            ? 4
            : 40 + Math.floor(random() * 200);
        rows.push(
          indicatorRow(MAIN_TENANT, code, competence, cnes, clampRate(code, base * factor), den),
        );
      });
      // Outro município: valores muito diferentes (detecta vazamento de tenant).
      rows.push(indicatorRow(OTHER_TENANT, code, competence, null, clampRate(code, 0.5), 300));
      rows.push(
        indicatorRow(
          OTHER_TENANT,
          code,
          competence,
          OTHER_UNITS[0]!.cnes,
          clampRate(code, 0.5),
          100,
        ),
      );
    });
  }
  return rows;
}
export const aggIndicadoresMensais: Row[] = buildIndicators();

// ---------- agg_care_gaps_monthly ----------
export const CARE_LINES = [
  'diabetes',
  'gestante',
  'hipertensao',
  'rastreamento_cancer_mama',
  'saude_mental',
];

function careGapRow(
  tenant: string,
  competence: string,
  cnes: string,
  ine: string,
  careLine: string,
  detected: number,
  resolved: number,
): Row {
  const open = Math.max(0, detected - resolved);
  const withTask = Math.round(detected * 0.7);
  return {
    tenant_id: tenant,
    month_start: monthStart(competence),
    competence,
    territory_key: `${tenant}:${cnes}:${ine}:-`,
    health_unit_key: `${tenant}:${cnes}`,
    health_unit_cnes: cnes,
    team_ine: ine,
    care_line: careLine,
    n_detected: suppress(detected),
    n_resolved: suppress(resolved),
    n_open: suppress(open),
    n_with_task: suppress(withTask),
    days_open_p50: suppress(12 + Math.round(random() * 30), detected),
    resolution_rate: suppress(detected > 0 ? resolved / detected : null, detected),
  };
}

function buildCareGaps(): Row[] {
  const rows: Row[] = [];
  for (const competence of SITUATION_COMPETENCES) {
    APS_UNITS.forEach((u, ui) => {
      (teamsByUnit[u.cnes] ?? []).forEach((t, ti) => {
        CARE_LINES.forEach((line) => {
          // Equipe II da UBS Major Prates com poucas lacunas → suprimida.
          const detected = ui === 2 && ti === 1 ? 3 : 12 + Math.floor(random() * 30);
          const rate = [0.82, 0.55, 0.38][ui % 3]! + (ti === 0 ? 0.05 : -0.05);
          rows.push(
            careGapRow(
              MAIN_TENANT,
              competence,
              u.cnes,
              t.ine,
              line,
              detected,
              Math.round(detected * rate),
            ),
          );
        });
      });
    });
    rows.push(
      careGapRow(OTHER_TENANT, competence, OTHER_UNITS[0]!.cnes, '9990000000', 'diabetes', 40, 40),
    );
  }
  return rows;
}
export const aggCareGapsMonthly: Row[] = buildCareGaps();

// ---------- agg_hospital_monthly ----------
function hospitalRow(
  tenant: string,
  competence: string,
  cnes: string,
  discharges: number,
  deaths: number,
): Row {
  const alive = discharges - deaths;
  const readmitted = Math.round(alive * (0.06 + random() * 0.08));
  const contacted = Math.round(alive * (0.55 + random() * 0.3));
  return {
    tenant_id: tenant,
    month_start: monthStart(competence),
    competence,
    hospital_unit_key: `${tenant}:${cnes}`,
    hospital_cnes: cnes,
    episode_class: 'inpatient',
    n_discharges: suppress(discharges),
    n_discharged_alive: suppress(alive),
    n_deaths: suppress(deaths),
    n_readmitted_30d: suppress(readmitted),
    n_contacted_7d: suppress(contacted),
    n_with_post_discharge_task: suppress(alive),
    los_avg_days: suppress(Math.round((3.5 + random() * 3) * 10) / 10, discharges),
    los_p50_days: suppress(4, discharges),
    hours_to_first_contact_p50: suppress(30, contacted),
    minutes_discharge_to_task_p90: suppress(9, alive),
    readmission_30d_rate: suppress(alive > 0 ? readmitted / alive : null, alive),
    post_discharge_contact_7d_rate: suppress(alive > 0 ? contacted / alive : null, alive),
  };
}

function buildHospital(): Row[] {
  const rows: Row[] = [];
  for (const competence of SITUATION_COMPETENCES) {
    rows.push(hospitalRow(MAIN_TENANT, competence, '2200385', 180 + Math.floor(random() * 60), 9));
    rows.push(
      hospitalRow(MAIN_TENANT, competence, SANTA_CASA.cnes, 60 + Math.floor(random() * 20), 2),
    );
    rows.push(hospitalRow(OTHER_TENANT, competence, '9990099', 999, 0));
  }
  return rows;
}
export const aggHospitalMonthly: Row[] = buildHospital();

// ---------- agg_regulation_queue_current ----------
const QUEUE_SPECS: [string, string, string, number, number][] = [
  ['cardiologia', 'routine', 'consultation', 64, 12],
  ['cardiologia', 'urgent', 'consultation', 7, 3],
  ['ortopedia', 'routine', 'consultation', 112, 41],
  ['oftalmologia', 'routine', 'consultation', 88, 9],
  ['neurologia', 'priority', 'consultation', 23, 6],
  ['ressonancia_magnetica', 'routine', 'exam', 45, 18],
  ['endocrinologia', 'urgent', 'consultation', 3, 1],
];
export const aggRegulationQueueCurrent: Row[] = [
  ...QUEUE_SPECS.map(([specialty, priority, kind, open, overdue]) => ({
    tenant_id: MAIN_TENANT,
    as_of: '2026-10-04 06:00:00.000000',
    specialty,
    priority,
    request_kind: kind,
    n_open: suppress(open),
    n_overdue: suppress(overdue),
    n_pending_documents: suppress(Math.round(open * 0.08)),
    days_waiting_p50: suppress(Math.round(open * 0.4), open),
    days_waiting_p90: suppress(Math.round(open * 1.1), open),
    days_waiting_max: suppress(Math.round(open * 1.6), open),
  })),
  {
    tenant_id: OTHER_TENANT,
    as_of: '2026-10-04 06:00:00.000000',
    specialty: 'outro_municipio',
    priority: 'routine',
    request_kind: 'consultation',
    n_open: 999,
    n_overdue: 999,
    n_pending_documents: 0,
    days_waiting_p50: 1,
    days_waiting_p90: 1,
    days_waiting_max: 1,
  },
];
