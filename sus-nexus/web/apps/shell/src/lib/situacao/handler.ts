import type {
  SituationCapacityResponse,
  SituationFiltersResponse,
  SituationIndicator,
  SituationIndicatorMeta,
  SituationIndicatorsResponse,
  SituationInequality,
  SituationInequalityPoint,
  SituationRankingResponse,
  SituationSeriesResponse,
  SituationTerritoriesResponse,
  SituationView,
  SuppressibleValue,
} from '@sus-nexus/api-client';
import {
  buildQueries,
  isIndicatorCode,
  isSituationView,
  parseSituationParams,
  PARAM_PATTERNS,
  SituationParamError,
  type SituationParams,
} from './queries';
import { runPreparedQuery, TrinoError, type TrinoConfig, type TrinoRow } from './trino';

/**
 * Núcleo do BFF da Sala de Situação, independente do Next (testável): valida visão e filtros,
 * força o município do token, executa as consultas fixas no Trino e normaliza a supressão.
 */

export interface SituationContext {
  /** `municipality_id` do token — única fonte do filtro de município. */
  tenantId: string | undefined;
  trino: TrinoConfig;
  correlationId?: string;
  now?: Date;
}

function problem(status: number, title: string, detail?: string, correlationId?: string) {
  return Response.json(
    { type: 'about:blank', title, status, detail, correlation_id: correlationId },
    {
      status,
      headers: { 'content-type': 'application/problem+json', 'cache-control': 'no-store' },
    },
  );
}

const num = (v: unknown): number | null =>
  typeof v === 'number' && Number.isFinite(v)
    ? v
    : typeof v === 'string' && v.trim() !== '' && Number.isFinite(Number(v))
      ? Number(v)
      : null;
const str = (v: unknown): string | null => (typeof v === 'string' ? v : null);
const bool = (v: unknown): boolean | null => (typeof v === 'boolean' ? v : null);

/** Contagem de `agg_*`: zero é publicado, então nulo = suprimido (0 < n < 5). */
function count(v: unknown): SuppressibleValue {
  const value = num(v);
  return { value, suppressed: value === null };
}

/** Medida derivada (taxa/média/percentil) cuja base é `base`: suprimida se a base foi suprimida. */
function derived(v: unknown, base: SuppressibleValue): SuppressibleValue {
  const value = num(v);
  return { value, suppressed: value === null && base.suppressed };
}

function toIndicator(r: TrinoRow): SituationIndicator | null {
  const code = str(r.indicator_code);
  if (!code || !isIndicatorCode(code)) return null;
  const suppressed = bool(r.is_suppressed) === true;
  return {
    code,
    name: str(r.indicator_name) ?? code,
    competence: str(r.competence) ?? '',
    aggregation_level: r.aggregation_level === 'unidade' ? 'unidade' : 'municipio',
    health_unit_cnes: str(r.health_unit_cnes),
    // Supressão: nada de "0" no lugar de nulo.
    numerator: suppressed ? null : num(r.numerator),
    denominator: suppressed ? null : num(r.denominator),
    value: suppressed ? null : num(r.indicator_value),
    is_suppressed: suppressed,
    unit: str(r.indicator_unit) ?? 'proporcao',
    direction: r.direction === 'maior_melhor' ? 'maior_melhor' : 'menor_melhor',
    target: num(r.target_value),
    is_on_target: suppressed ? null : bool(r.is_on_target),
  };
}

function metaOf(r: TrinoRow | undefined): SituationIndicatorMeta | null {
  if (!r) return null;
  const i = toIndicator(r);
  return i
    ? { code: i.code, name: i.name, unit: i.unit, direction: i.direction, target: i.target }
    : null;
}

/** Maior × menor entre valores publicados (suprimidos/sem dado ficam fora e são contados). */
export function computeInequality(
  points: { key: string; label: string; value: number | null; suppressed: boolean }[],
): SituationInequality | null {
  const valid = points.filter(
    (p): p is SituationInequalityPoint & { suppressed: boolean } =>
      p.value !== null && !p.suppressed,
  );
  const suppressed = points.filter((p) => p.suppressed).length;
  if (valid.length < 2) return null;
  let hi = valid[0]!;
  let lo = valid[0]!;
  for (const p of valid) {
    if (p.value > hi.value) hi = p;
    if (p.value < lo.value) lo = p;
  }
  const strip = ({ key, label, value }: SituationInequalityPoint) => ({ key, label, value });
  return {
    highest: strip(hi),
    lowest: strip(lo),
    difference: hi.value - lo.value,
    ratio: lo.value > 0 ? hi.value / lo.value : null,
    compared: valid.length,
    suppressed,
  };
}

async function execute(
  view: SituationView,
  tenant: string,
  params: SituationParams,
  ctx: SituationContext,
): Promise<unknown> {
  const queries = buildQueries(view, tenant, params, ctx.trino.catalog);
  const entries = await Promise.all(
    Object.entries(queries).map(
      async ([k, q]) =>
        [k, await runPreparedQuery(ctx.trino, q, { correlationId: ctx.correlationId })] as const,
    ),
  );
  const rows = Object.fromEntries(entries) as Record<string, TrinoRow[]>;
  const scope = {
    level: params.cnes ? 'unidade' : 'municipio',
    cnes: params.cnes ?? null,
  } as const;

  switch (view) {
    case 'indicadores': {
      const body: SituationIndicatorsResponse = {
        competence: params.competence,
        scope,
        items: (rows.items ?? []).map(toIndicator).filter((i) => i !== null),
      };
      return body;
    }
    case 'serie': {
      const pts = (rows.points ?? []).map(toIndicator).filter((i) => i !== null);
      const body: SituationSeriesResponse = {
        indicator: metaOf(rows.points?.[0]),
        scope,
        points: pts
          .map((i) => ({
            competence: i.competence,
            value: i.value,
            numerator: i.numerator,
            denominator: i.denominator,
            is_suppressed: i.is_suppressed,
            is_on_target: i.is_on_target,
          }))
          .sort((a, b) => (a.competence < b.competence ? -1 : 1)),
      };
      return body;
    }
    case 'ranking': {
      const items = (rows.items ?? []).flatMap((r) => {
        const i = toIndicator({
          ...r,
          aggregation_level: 'unidade',
          competence: params.competence,
        });
        const cnes = str(r.health_unit_cnes);
        if (!i || !cnes) return [];
        return [
          {
            health_unit_cnes: cnes,
            health_unit_name: str(r.health_unit_name) ?? `Estabelecimento CNES ${cnes}`,
            unit_role: str(r.unit_role),
            value: i.value,
            numerator: i.numerator,
            denominator: i.denominator,
            is_suppressed: i.is_suppressed,
            is_on_target: i.is_on_target,
          },
        ];
      });
      const body: SituationRankingResponse = {
        competence: params.competence,
        indicator: metaOf(rows.items?.[0]),
        items,
        inequality: computeInequality(
          items.map((i) => ({
            key: i.health_unit_cnes,
            label: i.health_unit_name,
            value: i.value,
            suppressed: i.is_suppressed,
          })),
        ),
      };
      return body;
    }
    case 'territorios': {
      const items = (rows.items ?? []).flatMap((r) => {
        const cnes = str(r.health_unit_cnes);
        if (!cnes) return [];
        const detected = count(r.n_detected);
        return [
          {
            territory_key: str(r.territory_key) ?? `${cnes}:${str(r.team_ine) ?? '-'}`,
            health_unit_cnes: cnes,
            health_unit_name: str(r.health_unit_name) ?? `Estabelecimento CNES ${cnes}`,
            team_ine: str(r.team_ine),
            team_name: str(r.team_name),
            care_line: str(r.care_line) ?? '',
            n_detected: detected,
            n_resolved: count(r.n_resolved),
            n_open: count(r.n_open),
            days_open_p50: derived(r.days_open_p50, detected),
            resolution_rate: derived(r.resolution_rate, detected),
          },
        ];
      });
      const body: SituationTerritoriesResponse = {
        competence: params.competence,
        care_line: params.care_line ?? null,
        items,
        inequality: computeInequality(
          items.map((i) => ({
            key: i.territory_key,
            label: `${i.team_name ?? `Equipe ${i.team_ine ?? '—'}`} · ${i.health_unit_name}`,
            value: i.resolution_rate.value,
            suppressed: i.resolution_rate.suppressed,
          })),
        ),
      };
      return body;
    }
    case 'capacidade': {
      const hospitals = (rows.hospitals ?? []).flatMap((r) => {
        const cnes = str(r.hospital_cnes);
        if (!cnes) return [];
        const discharges = count(r.n_discharges);
        return [
          {
            hospital_cnes: cnes,
            health_unit_name: str(r.health_unit_name) ?? `Estabelecimento CNES ${cnes}`,
            n_discharges: discharges,
            n_deaths: count(r.n_deaths),
            n_readmitted_30d: count(r.n_readmitted_30d),
            los_avg_days: derived(r.los_avg_days, discharges),
            readmission_30d_rate: derived(r.readmission_30d_rate, discharges),
            post_discharge_contact_7d_rate: derived(r.post_discharge_contact_7d_rate, discharges),
          },
        ];
      });
      const queue = (rows.queue ?? []).map((r) => {
        const open = count(r.n_open);
        return {
          specialty: str(r.specialty) ?? '—',
          priority: str(r.priority) ?? '—',
          request_kind: str(r.request_kind) ?? '—',
          n_open: open,
          n_overdue: count(r.n_overdue),
          n_pending_documents: count(r.n_pending_documents),
          days_waiting_p50: derived(r.days_waiting_p50, open),
          days_waiting_p90: derived(r.days_waiting_p90, open),
        };
      });
      const body: SituationCapacityResponse = {
        competence: params.competence,
        queue_as_of: str(rows.queue?.[0]?.as_of),
        hospitals,
        queue,
      };
      return body;
    }
    case 'filtros': {
      const competences = (rows.competences ?? [])
        .map((r) => str(r.competence))
        .filter((c): c is string => !!c && PARAM_PATTERNS.competence.test(c));
      const body: SituationFiltersResponse = {
        default_competence:
          competences.find((c) => c <= params.competence) ?? competences[0] ?? params.competence,
        competences,
        units: (rows.units ?? []).flatMap((r) => {
          const cnes = str(r.health_unit_cnes);
          return cnes
            ? [{ cnes, name: str(r.health_unit_name) ?? cnes, role: str(r.unit_role) }]
            : [];
        }),
        territories: (rows.territories ?? []).flatMap((r) => {
          const cnes = str(r.health_unit_cnes);
          const ine = str(r.team_ine);
          return cnes && ine
            ? [{ health_unit_cnes: cnes, team_ine: ine, team_name: str(r.team_name) }]
            : [];
        }),
        care_lines: (rows.careLines ?? [])
          .map((r) => str(r.care_line))
          .filter((c): c is string => !!c),
      };
      return body;
    }
  }
}

/** Trata `GET /api/situacao/<visão>?…`. */
export async function handleSituationRequest(
  view: string,
  url: URL,
  ctx: SituationContext,
): Promise<Response> {
  if (!isSituationView(view))
    return problem(404, 'Visão não encontrada', undefined, ctx.correlationId);
  const tenant = ctx.tenantId;
  if (!tenant || !PARAM_PATTERNS.tenant.test(tenant)) {
    return problem(
      403,
      'Município ausente',
      'O token não traz um municipality_id válido.',
      ctx.correlationId,
    );
  }
  let params: SituationParams;
  try {
    params = parseSituationParams(view, url.searchParams, ctx.now);
  } catch (error) {
    if (error instanceof SituationParamError) {
      return problem(400, 'Parâmetro inválido', error.message, ctx.correlationId);
    }
    throw error;
  }
  try {
    const body = await execute(view, tenant, params, ctx);
    return Response.json(body, { headers: { 'cache-control': 'no-store' } });
  } catch (error) {
    // Sem SQL, parâmetros ou PII no log: só visão, correlação e mensagem do erro.
    console.error('[bff] sala de situação: falha no Trino', {
      view,
      correlationId: ctx.correlationId,
      message: error instanceof TrinoError ? (error.errorName ?? 'trino_error') : 'erro',
    });
    return problem(
      502,
      'Lakehouse indisponível',
      'Não foi possível consultar os indicadores agora.',
      ctx.correlationId,
    );
  }
}
