import {
  SITUATION_INDICATOR_CODES,
  SITUATION_VIEWS,
  type SituationIndicatorCode,
  type SituationView,
} from '@sus-nexus/api-client';
import type { PreparedQuery } from './trino';

/**
 * Catálogo FIXO de consultas da Sala de Situação. O navegador nunca envia SQL: escolhe uma visão
 * (whitelist) e filtros validados por regex estrita; o município vem sempre do token.
 *
 * Fontes (somente gold agregado e dimensões — nunca `marts_identified`):
 *  - `marts_aggregated.agg_indicadores_mensais`, `agg_care_gaps_monthly`, `agg_hospital_monthly`,
 *    `agg_regulation_queue_current`;
 *  - `marts.dim_health_unit`, `marts.dim_territory` (o grupo `bi` do Trino só lê `marts.dim_*`).
 */

export const PARAM_PATTERNS = {
  tenant: /^ibge_[0-9]{7}$/,
  competence: /^[0-9]{4}(0[1-9]|1[0-2])$/,
  cnes: /^[0-9]{7}$/,
  team_ine: /^[0-9]{10}$/,
  care_line: /^[a-z][a-z0-9_]{1,48}$/,
  catalog: /^[a-z][a-z0-9_]{0,62}$/,
} as const;

/** Parâmetros aceitos por visão (qualquer outro → 400). */
export const VIEW_PARAMS: Record<SituationView, readonly string[]> = {
  indicadores: ['competence', 'cnes'],
  serie: ['indicator', 'cnes', 'competence'],
  ranking: ['indicator', 'competence'],
  territorios: ['competence', 'care_line', 'cnes', 'team_ine'],
  capacidade: ['competence', 'cnes'],
  filtros: [],
};

const REQUIRED_PARAMS: Partial<Record<SituationView, readonly string[]>> = {
  serie: ['indicator'],
  ranking: ['indicator'],
  territorios: ['care_line'],
};

export interface SituationParams {
  competence: string;
  cnes?: string;
  team_ine?: string;
  indicator?: SituationIndicatorCode;
  care_line?: string;
}

export class SituationParamError extends Error {
  readonly field: string;
  constructor(field: string, message: string) {
    super(message);
    this.name = 'SituationParamError';
    this.field = field;
  }
}

export function isSituationView(value: string): value is SituationView {
  return (SITUATION_VIEWS as readonly string[]).includes(value);
}

export function isIndicatorCode(value: string): value is SituationIndicatorCode {
  return (SITUATION_INDICATOR_CODES as readonly string[]).includes(value);
}

/** Competência padrão: mês anterior (último mês fechado) no fuso America/Sao_Paulo. */
export function defaultCompetence(now: Date = new Date()): string {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'America/Sao_Paulo',
    year: 'numeric',
    month: '2-digit',
  }).formatToParts(now);
  const year = Number(parts.find((p) => p.type === 'year')?.value);
  const month = Number(parts.find((p) => p.type === 'month')?.value);
  const prevYear = month === 1 ? year - 1 : year;
  const prevMonth = month === 1 ? 12 : month - 1;
  return `${prevYear}${String(prevMonth).padStart(2, '0')}`;
}

/** Valida os parâmetros da visão. Lança `SituationParamError` (→ 400) em qualquer desvio. */
export function parseSituationParams(
  view: SituationView,
  search: URLSearchParams,
  now?: Date,
): SituationParams {
  const allowed = VIEW_PARAMS[view];
  const seen = new Set<string>();
  for (const key of search.keys()) {
    if (!allowed.includes(key)) {
      throw new SituationParamError(key, `Parâmetro não permitido: ${key.slice(0, 40)}`);
    }
    if (seen.has(key)) throw new SituationParamError(key, `Parâmetro repetido: ${key}`);
    seen.add(key);
  }
  for (const key of REQUIRED_PARAMS[view] ?? []) {
    if (!search.get(key)) throw new SituationParamError(key, `Parâmetro obrigatório: ${key}`);
  }
  const out: SituationParams = { competence: defaultCompetence(now) };
  const competence = search.get('competence');
  if (competence !== null) {
    if (!PARAM_PATTERNS.competence.test(competence))
      throw new SituationParamError('competence', 'Competência inválida (AAAAMM).');
    out.competence = competence;
  }
  const cnes = search.get('cnes');
  if (cnes !== null) {
    if (!PARAM_PATTERNS.cnes.test(cnes))
      throw new SituationParamError('cnes', 'CNES inválido (7 dígitos).');
    out.cnes = cnes;
  }
  const team = search.get('team_ine');
  if (team !== null) {
    if (!PARAM_PATTERNS.team_ine.test(team))
      throw new SituationParamError('team_ine', 'INE inválido (10 dígitos).');
    out.team_ine = team;
  }
  const indicator = search.get('indicator');
  if (indicator !== null) {
    if (!isIndicatorCode(indicator))
      throw new SituationParamError('indicator', 'Indicador desconhecido.');
    out.indicator = indicator;
  }
  const careLine = search.get('care_line');
  if (careLine !== null) {
    if (!PARAM_PATTERNS.care_line.test(careLine))
      throw new SituationParamError('care_line', 'Linha de cuidado inválida.');
    out.care_line = careLine;
  }
  return out;
}

/** Nomes totalmente qualificados (o catálogo vem de TRINO_CATALOG, validado por regex). */
function tables(catalog: string) {
  if (!PARAM_PATTERNS.catalog.test(catalog)) throw new Error('TRINO_CATALOG inválido');
  return {
    indicators: `${catalog}.marts_aggregated.agg_indicadores_mensais`,
    careGaps: `${catalog}.marts_aggregated.agg_care_gaps_monthly`,
    hospital: `${catalog}.marts_aggregated.agg_hospital_monthly`,
    queue: `${catalog}.marts_aggregated.agg_regulation_queue_current`,
    units: `${catalog}.marts.dim_health_unit`,
    territory: `${catalog}.marts.dim_territory`,
  };
}

const INDICATOR_COLUMNS = `indicator_code, indicator_name, competence, aggregation_level, health_unit_cnes,
  numerator, denominator, indicator_value, is_suppressed, indicator_unit, direction, target_value, is_on_target`;

/** Montagem das consultas fixas. `tenant` = `municipality_id` do token (já validado). */
export function buildQueries(
  view: SituationView,
  tenant: string,
  p: SituationParams,
  catalog: string,
): Record<string, PreparedQuery> {
  if (!PARAM_PATTERNS.tenant.test(tenant))
    throw new SituationParamError('tenant', 'Município inválido');
  const T = tables(catalog);
  const level = p.cnes ? 'unidade' : 'municipio';
  const cnesKey = p.cnes ?? '-';
  let queries: Record<string, PreparedQuery>;
  switch (view) {
    case 'indicadores':
      queries = {
        items: {
          name: 'sit_indicadores',
          sql: `select ${INDICATOR_COLUMNS}
from ${T.indicators}
where tenant_id = ? and competence = ? and aggregation_level = ? and coalesce(health_unit_cnes, '-') = ?
order by indicator_code`,
          params: [tenant, p.competence, level, cnesKey],
        },
      };
      break;
    case 'serie':
      queries = {
        points: {
          name: 'sit_serie',
          sql: `select ${INDICATOR_COLUMNS}
from ${T.indicators}
where tenant_id = ? and indicator_code = ? and aggregation_level = ? and coalesce(health_unit_cnes, '-') = ?
  and competence <= ?
order by competence desc
limit 12`,
          params: [tenant, p.indicator ?? '', level, cnesKey, p.competence],
        },
      };
      break;
    case 'ranking':
      queries = {
        items: {
          name: 'sit_ranking',
          sql: `select a.indicator_code, a.indicator_name, a.indicator_unit, a.direction, a.target_value,
  a.health_unit_cnes, u.health_unit_name, u.unit_role, a.numerator, a.denominator, a.indicator_value,
  a.is_suppressed, a.is_on_target
from ${T.indicators} as a
left join ${T.units} as u on u.tenant_id = a.tenant_id and u.health_unit_cnes = a.health_unit_cnes
where a.tenant_id = ? and a.indicator_code = ? and a.competence = ? and a.aggregation_level = 'unidade'
order by a.health_unit_cnes`,
          params: [tenant, p.indicator ?? '', p.competence],
        },
      };
      break;
    case 'territorios':
      queries = {
        items: {
          name: 'sit_territorios',
          sql: `select g.territory_key, g.health_unit_cnes, u.health_unit_name, g.team_ine, t.team_name, g.care_line,
  g.n_detected, g.n_resolved, g.n_open, g.days_open_p50, g.resolution_rate
from ${T.careGaps} as g
left join ${T.units} as u on u.tenant_id = g.tenant_id and u.health_unit_cnes = g.health_unit_cnes
left join (
  select tenant_id, team_ine, max(team_name) as team_name from ${T.territory}
  where tenant_id = ? and team_ine is not null group by tenant_id, team_ine
) as t on t.tenant_id = g.tenant_id and t.team_ine = g.team_ine
where g.tenant_id = ? and g.competence = ? and g.care_line = ?
  and (? = '' or g.health_unit_cnes = ?) and (? = '' or g.team_ine = ?)
order by g.health_unit_cnes, g.team_ine`,
          params: [
            tenant,
            tenant,
            p.competence,
            p.care_line ?? '',
            p.cnes ?? '',
            p.cnes ?? '',
            p.team_ine ?? '',
            p.team_ine ?? '',
          ],
        },
      };
      break;
    case 'capacidade':
      queries = {
        hospitals: {
          name: 'sit_capacidade_hospital',
          sql: `select h.hospital_cnes, u.health_unit_name, h.n_discharges, h.n_deaths, h.n_readmitted_30d,
  h.los_avg_days, h.readmission_30d_rate, h.post_discharge_contact_7d_rate
from ${T.hospital} as h
left join ${T.units} as u on u.tenant_id = h.tenant_id and u.health_unit_cnes = h.hospital_cnes
where h.tenant_id = ? and h.competence = ? and h.episode_class = 'inpatient'
  and (? = '' or h.hospital_cnes = ?)
order by h.hospital_cnes`,
          params: [tenant, p.competence, p.cnes ?? '', p.cnes ?? ''],
        },
        queue: {
          name: 'sit_capacidade_fila',
          sql: `select as_of, specialty, priority, request_kind, n_open, n_overdue, n_pending_documents,
  days_waiting_p50, days_waiting_p90
from ${T.queue}
where tenant_id = ?
order by specialty, priority, request_kind`,
          params: [tenant],
        },
      };
      break;
    case 'filtros':
      queries = {
        competences: {
          name: 'sit_filtros_competencias',
          sql: `select distinct competence
from ${T.indicators}
where tenant_id = ? and aggregation_level = 'municipio'
order by competence desc
limit 24`,
          params: [tenant],
        },
        units: {
          name: 'sit_filtros_unidades',
          sql: `select health_unit_cnes, health_unit_name, unit_role
from ${T.units}
where tenant_id = ? and is_active
order by health_unit_name`,
          params: [tenant],
        },
        territories: {
          name: 'sit_filtros_territorios',
          sql: `select health_unit_cnes, team_ine, max(team_name) as team_name
from ${T.territory}
where tenant_id = ? and team_ine is not null and health_unit_cnes is not null
group by health_unit_cnes, team_ine
order by health_unit_cnes, team_ine`,
          params: [tenant],
        },
        careLines: {
          name: 'sit_filtros_linhas',
          sql: `select distinct care_line
from ${T.careGaps}
where tenant_id = ? and care_line is not null
order by care_line`,
          params: [tenant],
        },
      };
      break;
  }
  for (const q of Object.values(queries)) assertSafeSql(q.sql);
  return queries;
}

/** Defesa em profundidade: nada de camada identificada, bronze/silver ou escrita. */
export function assertSafeSql(sql: string): void {
  const lower = sql.toLowerCase();
  if (
    /marts_identified|\bbronze\.|\bstaging\.|\bintermediate\.|\bpostgresql\./.test(lower) ||
    /\b(insert|update|delete|drop|create|alter|grant|call|merge)\b/.test(lower) ||
    lower.includes(';')
  ) {
    throw new Error('Consulta fora do escopo permitido da Sala de Situação');
  }
  if (!/where\s+(\w+\.)?tenant_id = \?/.test(lower)) {
    throw new Error('Consulta sem filtro de município');
  }
}
