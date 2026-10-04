/**
 * Contrato JSON do BFF da Sala de Situação (`/api/situacao/<visão>`), servido pelo shell a partir dos
 * agregados gold pseudonimizados do dbt no Trino (`marts_aggregated.agg_*`, `marts.dim_*`).
 *
 * Supressão de células pequenas: valores nulos por `0 < n < 5` chegam como `value: null` com
 * `suppressed: true` (ou `is_suppressed`) — **nunca** como zero. `value: null` sem supressão = sem dado.
 */

export const SITUATION_VIEWS = [
  'indicadores',
  'serie',
  'ranking',
  'territorios',
  'capacidade',
  'filtros',
] as const;
export type SituationView = (typeof SITUATION_VIEWS)[number];

/** Códigos de `seed_metas_indicadores` (data/INDICADORES.md). Whitelist do BFF. */
export const SITUATION_INDICATOR_CODES = [
  'AGE_ABSENTEISMO',
  'AGE_COMPARECIMENTO',
  'AGE_CANCELAMENTO',
  'AGE_REAPROVEITAMENTO',
  'REG_ESPERA_P50_DIAS',
  'REG_ESPERA_P90_DIAS',
  'REG_SLA_CUMPRIDO',
  'REG_DEVOLUCAO',
  'REG_REALIZACAO',
  'EXA_CICLO_COMPLETO',
  'EXA_RESULTADO_SEM_RETORNO',
  'EXA_DIAS_PEDIDO_RESULTADO_P50',
  'HOS_REINTERNACAO_30D',
  'HOS_CONTATO_POS_ALTA_7D',
  'HOS_PERMANENCIA_MEDIA_DIAS',
  'CUI_LACUNAS_RESOLVIDAS',
  'TAR_SLA_CUMPRIDO',
  'TAR_AUTOMACAO',
  'TAR_AGENTE_SLA_CUMPRIDO',
  'PRO_GLOSA',
] as const;
export type SituationIndicatorCode = (typeof SITUATION_INDICATOR_CODES)[number];

export type IndicatorDirection = 'maior_melhor' | 'menor_melhor';
/** `proporcao` (0–1) ou `dias`. */
export type IndicatorUnit = 'proporcao' | 'dias' | (string & {});

/** Valor de um `agg_*` sujeito a supressão. */
export interface SuppressibleValue {
  value: number | null;
  suppressed: boolean;
}

export interface SituationScope {
  level: 'municipio' | 'unidade';
  cnes: string | null;
}

/** Linha de `agg_indicadores_mensais`. */
export interface SituationIndicator {
  code: SituationIndicatorCode;
  name: string;
  competence: string;
  aggregation_level: 'municipio' | 'unidade';
  health_unit_cnes: string | null;
  numerator: number | null;
  denominator: number | null;
  value: number | null;
  is_suppressed: boolean;
  unit: IndicatorUnit;
  direction: IndicatorDirection;
  target: number | null;
  is_on_target: boolean | null;
}

export interface SituationIndicatorsResponse {
  competence: string;
  scope: SituationScope;
  items: SituationIndicator[];
}

export interface SituationSeriesPoint {
  competence: string;
  value: number | null;
  numerator: number | null;
  denominator: number | null;
  is_suppressed: boolean;
  is_on_target: boolean | null;
}

export interface SituationIndicatorMeta {
  code: SituationIndicatorCode;
  name: string;
  unit: IndicatorUnit;
  direction: IndicatorDirection;
  target: number | null;
}

export interface SituationSeriesResponse {
  indicator: SituationIndicatorMeta | null;
  scope: SituationScope;
  points: SituationSeriesPoint[];
}

export interface SituationInequalityPoint {
  key: string;
  label: string;
  value: number;
}

/** Desigualdade entre unidades/territórios: só valores publicados (suprimidos ficam fora). */
export interface SituationInequality {
  highest: SituationInequalityPoint;
  lowest: SituationInequalityPoint;
  /** maior − menor. */
  difference: number;
  /** maior ÷ menor (nulo quando o menor é 0). */
  ratio: number | null;
  compared: number;
  suppressed: number;
}

export interface SituationRankingItem {
  health_unit_cnes: string;
  health_unit_name: string;
  unit_role: string | null;
  value: number | null;
  numerator: number | null;
  denominator: number | null;
  is_suppressed: boolean;
  is_on_target: boolean | null;
}

export interface SituationRankingResponse {
  competence: string;
  indicator: SituationIndicatorMeta | null;
  items: SituationRankingItem[];
  inequality: SituationInequality | null;
}

/** Linha de `agg_care_gaps_monthly` (território = unidade × equipe). */
export interface SituationTerritoryRow {
  territory_key: string;
  health_unit_cnes: string;
  health_unit_name: string;
  team_ine: string | null;
  team_name: string | null;
  care_line: string;
  n_detected: SuppressibleValue;
  n_resolved: SuppressibleValue;
  n_open: SuppressibleValue;
  days_open_p50: SuppressibleValue;
  resolution_rate: SuppressibleValue;
}

export interface SituationTerritoriesResponse {
  competence: string;
  care_line: string | null;
  items: SituationTerritoryRow[];
  inequality: SituationInequality | null;
}

/** `agg_hospital_monthly` (internação). */
export interface SituationHospitalRow {
  hospital_cnes: string;
  health_unit_name: string;
  n_discharges: SuppressibleValue;
  n_deaths: SuppressibleValue;
  n_readmitted_30d: SuppressibleValue;
  los_avg_days: SuppressibleValue;
  readmission_30d_rate: SuppressibleValue;
  post_discharge_contact_7d_rate: SuppressibleValue;
}

/** `agg_regulation_queue_current` (foto da execução do dbt). */
export interface SituationQueueRow {
  specialty: string;
  priority: string;
  request_kind: string;
  n_open: SuppressibleValue;
  n_overdue: SuppressibleValue;
  n_pending_documents: SuppressibleValue;
  days_waiting_p50: SuppressibleValue;
  days_waiting_p90: SuppressibleValue;
}

export interface SituationCapacityResponse {
  competence: string;
  queue_as_of: string | null;
  hospitals: SituationHospitalRow[];
  queue: SituationQueueRow[];
}

export interface SituationFiltersResponse {
  default_competence: string;
  competences: string[];
  units: { cnes: string; name: string; role: string | null }[];
  territories: { health_unit_cnes: string; team_ine: string; team_name: string | null }[];
  care_lines: string[];
}

export interface SituationQuery {
  competence?: string;
  cnes?: string;
  team_ine?: string;
  indicator?: SituationIndicatorCode;
  care_line?: string;
}
