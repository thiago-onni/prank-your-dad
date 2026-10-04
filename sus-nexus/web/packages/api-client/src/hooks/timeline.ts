'use client';

import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { unwrap } from '../client';
import { coreKeys } from '../keys';
import { useCoreClient, useCurrentPurpose } from '../provider';
import type { Domain, Page, Purpose, TimelineEvent } from '../types';

export interface TimelineParams {
  from?: string;
  to?: string;
  domain?: Domain[];
  cnes?: string;
  status?: string;
  cursor?: string;
  limit?: number;
}

export function useTimeline(citizenId: string | undefined, params: TimelineParams = {}) {
  const client = useCoreClient();
  const purpose = useCurrentPurpose();
  return useQuery({
    queryKey: coreKeys.timeline(citizenId ?? '', { ...params, purpose }),
    enabled: Boolean(citizenId) && Boolean(purpose),
    placeholderData: keepPreviousData,
    queryFn: async (): Promise<Page<TimelineEvent>> =>
      unwrap(
        await client.GET('/api/v1/citizens/{citizenId}/timeline', {
          params: {
            path: { citizenId: citizenId as string },
            header: { 'X-Purpose-Of-Use': purpose as Purpose },
            query: {
              from: params.from || undefined,
              to: params.to || undefined,
              domain: params.domain && params.domain.length > 0 ? params.domain : undefined,
              cnes: params.cnes || undefined,
              status: params.status || undefined,
              cursor: params.cursor,
              limit: params.limit ?? 50,
            },
          },
          querySerializer: { array: { style: 'form', explode: false } },
        }),
      ),
  });
}
