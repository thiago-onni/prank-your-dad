'use client';

import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { unwrap } from '../client';
import { coreKeys } from '../keys';
import { useCoreClient } from '../provider';
import type { MergeCase, MergeCaseStatus, Page } from '../types';

export interface MergeCasesParams {
  status?: MergeCaseStatus;
  cursor?: string;
  limit?: number;
}

export function useMergeCases(params: MergeCasesParams = {}) {
  const client = useCoreClient();
  return useQuery({
    queryKey: coreKeys.mergeCases(params),
    placeholderData: keepPreviousData,
    queryFn: async (): Promise<Page<MergeCase>> =>
      unwrap(
        await client.GET('/api/v1/mpi/cases', {
          params: {
            query: { status: params.status, cursor: params.cursor, limit: params.limit ?? 50 },
          },
        }),
      ),
  });
}

export function useMergeCase(caseId: string | undefined) {
  const client = useCoreClient();
  return useQuery({
    queryKey: coreKeys.mergeCase(caseId ?? ''),
    enabled: Boolean(caseId),
    queryFn: async (): Promise<MergeCase> =>
      unwrap(
        await client.GET('/api/v1/mpi/cases/{caseId}', {
          params: { path: { caseId: caseId as string } },
        }),
      ),
  });
}

export function useMergeCaseDecision(caseId: string) {
  const client = useCoreClient();
  const qc = useQueryClient();
  const invalidate = async () => {
    await qc.invalidateQueries({ queryKey: coreKeys.mergeCase(caseId) });
    await qc.invalidateQueries({ queryKey: [...coreKeys.all, 'mpi', 'cases'] });
  };
  const merge = useMutation({
    mutationFn: async (input: {
      surviving_citizen_id: string;
      reason: string;
    }): Promise<MergeCase> =>
      unwrap(
        await client.POST('/api/v1/mpi/cases/{caseId}/merge', {
          params: { path: { caseId } },
          body: input,
        }),
      ),
    onSuccess: invalidate,
  });
  const reject = useMutation({
    mutationFn: async (input: { reason: string }): Promise<MergeCase> =>
      unwrap(
        await client.POST('/api/v1/mpi/cases/{caseId}/reject', {
          params: { path: { caseId } },
          body: input,
        }),
      ),
    onSuccess: invalidate,
  });
  return { merge, reject };
}

export function useUnmerge() {
  const client = useCoreClient();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: { mergeId: string; reason: string }): Promise<MergeCase> =>
      unwrap(
        await client.POST('/api/v1/mpi/merges/{mergeId}/unmerge', {
          params: { path: { mergeId: input.mergeId } },
          body: { reason: input.reason },
        }),
      ),
    onSuccess: () => qc.invalidateQueries({ queryKey: [...coreKeys.all, 'mpi'] }),
  });
}
