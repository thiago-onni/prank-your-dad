'use client';

import { useQueries } from '@tanstack/react-query';
import {
  coreKeys,
  unwrap,
  useCoreClient,
  useCurrentPurpose,
  type CitizenDetail,
  type Purpose,
} from '@sus-nexus/api-client';

/**
 * Resolve cidadãos das tarefas visíveis (nome exibido + microárea) para o filtro do ACS.
 * Limitação conhecida: o contrato de tarefas não expõe microárea; o backend deveria oferecer
 * `GET /tasks?microarea=` para evitar N consultas.
 */
export function useCitizenLookup(ids: string[]) {
  const client = useCoreClient();
  const purpose = useCurrentPurpose();
  const results = useQueries({
    queries: ids.map((id) => ({
      queryKey: coreKeys.citizen(id),
      enabled: Boolean(purpose),
      staleTime: 5 * 60_000,
      queryFn: async (): Promise<CitizenDetail> =>
        unwrap(
          await client.GET('/api/v1/citizens/{citizenId}', {
            params: { path: { citizenId: id }, header: { 'X-Purpose-Of-Use': purpose as Purpose } },
          }),
        ),
    })),
  });
  const map = new Map<string, CitizenDetail>();
  results.forEach((r, i) => {
    if (r.data) map.set(ids[i]!, r.data);
  });
  return { citizens: map, isLoading: results.some((r) => r.isLoading) };
}
