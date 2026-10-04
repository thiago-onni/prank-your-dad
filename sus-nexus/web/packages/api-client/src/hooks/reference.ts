'use client';

import { useQuery } from '@tanstack/react-query';
import { unwrap } from '../client';
import { coreKeys } from '../keys';
import { useCoreClient } from '../provider';
import type { AccessLogEntry, Code, HealthUnit, Page, RuleSet } from '../types';

export function useHealthUnits(
  params: { q?: string; cnes?: string; cursor?: string; limit?: number } = {},
) {
  const client = useCoreClient();
  return useQuery({
    queryKey: coreKeys.healthUnits(params),
    queryFn: async (): Promise<Page<HealthUnit>> =>
      unwrap(
        await client.GET('/api/v1/reference/health-units', {
          params: {
            query: {
              q: params.q || undefined,
              cnes: params.cnes || undefined,
              cursor: params.cursor,
              limit: params.limit ?? 100,
            },
          },
        }),
      ),
  });
}

export type TerminologySystem = 'SIGTAP' | 'CID10' | 'CIAP2' | 'CBO';

export function useCodes(
  system: TerminologySystem,
  params: { q?: string; code?: string; competence?: string } = {},
) {
  const client = useCoreClient();
  return useQuery({
    queryKey: coreKeys.codes(system, params),
    enabled: Boolean(params.q || params.code),
    queryFn: async (): Promise<Page<Code>> =>
      unwrap(
        await client.GET('/api/v1/terminology/{system}/codes', {
          params: {
            path: { system },
            query: { q: params.q, code: params.code, competence: params.competence },
          },
        }),
      ),
  });
}

export function useAccessLog(
  params: {
    citizen_id?: string;
    actor_id?: string;
    from?: string;
    to?: string;
    cursor?: string;
  } = {},
) {
  const client = useCoreClient();
  return useQuery({
    queryKey: coreKeys.accessLog(params),
    queryFn: async (): Promise<Page<AccessLogEntry>> =>
      unwrap(
        await client.GET('/api/v1/audit/access', { params: { query: { ...params, limit: 50 } } }),
      ),
  });
}

export function useRuleSets() {
  const client = useCoreClient();
  return useQuery({
    queryKey: coreKeys.ruleSets(),
    queryFn: async (): Promise<RuleSet[]> => unwrap(await client.GET('/api/v1/admin/rules')),
  });
}
