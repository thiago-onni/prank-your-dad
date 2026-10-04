import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { bffGetJson } from '../bff';
import { coreKeys } from '../keys';
import { useBffBaseUrl } from '../provider';
import type {
  SituationCapacityResponse,
  SituationFiltersResponse,
  SituationIndicatorCode,
  SituationIndicatorsResponse,
  SituationQuery,
  SituationRankingResponse,
  SituationSeriesResponse,
  SituationTerritoriesResponse,
  SituationView,
} from '../situacao/types';

/**
 * Hooks da Sala de Situação (BFF `/api/situacao/<visão>` → Trino). O município é sempre o do
 * token (aplicado no servidor); os filtros aqui são só competência/unidade/equipe/indicador/linha.
 */
function situationUrl(base: string, view: SituationView, query: SituationQuery): string {
  const params = new URLSearchParams();
  for (const [k, v] of Object.entries(query)) if (v) params.set(k, String(v));
  const qs = params.toString();
  return `${base}/api/situacao/${view}${qs ? `?${qs}` : ''}`;
}

function useSituation<T>(view: SituationView, query: SituationQuery, enabled = true) {
  const base = useBffBaseUrl();
  return useQuery({
    queryKey: coreKeys.situation(view, query),
    enabled,
    placeholderData: keepPreviousData,
    staleTime: 5 * 60_000,
    queryFn: ({ signal }) => bffGetJson<T>(situationUrl(base, view, query), { signal }),
  });
}

export function useSituationFilters(options: { enabled?: boolean } = {}) {
  return useSituation<SituationFiltersResponse>('filtros', {}, options.enabled ?? true);
}

export function useSituationIndicators(
  query: { competence?: string; cnes?: string },
  options: { enabled?: boolean } = {},
) {
  return useSituation<SituationIndicatorsResponse>('indicadores', query, options.enabled ?? true);
}

export function useSituationSeries(
  query: { indicator?: SituationIndicatorCode; competence?: string; cnes?: string },
  options: { enabled?: boolean } = {},
) {
  return useSituation<SituationSeriesResponse>(
    'serie',
    query,
    (options.enabled ?? true) && !!query.indicator,
  );
}

export function useSituationRanking(
  query: { indicator?: SituationIndicatorCode; competence?: string },
  options: { enabled?: boolean } = {},
) {
  return useSituation<SituationRankingResponse>(
    'ranking',
    query,
    (options.enabled ?? true) && !!query.indicator,
  );
}

export function useSituationTerritories(
  query: { competence?: string; care_line?: string; cnes?: string; team_ine?: string },
  options: { enabled?: boolean } = {},
) {
  return useSituation<SituationTerritoriesResponse>(
    'territorios',
    query,
    (options.enabled ?? true) && !!query.care_line,
  );
}

export function useSituationCapacity(
  query: { competence?: string; cnes?: string },
  options: { enabled?: boolean } = {},
) {
  return useSituation<SituationCapacityResponse>('capacidade', query, options.enabled ?? true);
}
