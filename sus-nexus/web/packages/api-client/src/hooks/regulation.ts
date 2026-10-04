'use client';

import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { unwrap } from '../client';
import { coreKeys } from '../keys';
import { useCoreClient, useCurrentPurpose } from '../provider';
import type {
  Page,
  ProviderCapacity,
  Purpose,
  RegulationIssueFilter,
  RegulationIssueInput,
  RegulationPriority,
  RegulationQueueGroupBy,
  RegulationQueueItem,
  RegulationRequest,
  RegulationSort,
  RegulationStatus,
} from '../types';

export interface RegulationRequestsParams {
  status?: RegulationStatus;
  priority?: RegulationPriority;
  specialty?: string;
  service_code?: string;
  requesting_cnes?: string;
  provider_cnes?: string;
  citizen_id?: string;
  territory?: string;
  issue?: RegulationIssueFilter;
  sort?: RegulationSort;
  cursor?: string;
  limit?: number;
}

/** Fila regulatória (REG-004). Exige finalidade (dados de cidadão). */
export function useRegulationRequests(
  params: RegulationRequestsParams = {},
  options?: { enabled?: boolean },
) {
  const client = useCoreClient();
  const purpose = useCurrentPurpose();
  return useQuery({
    queryKey: coreKeys.regulationRequests({ ...params, purpose }),
    enabled: (options?.enabled ?? true) && Boolean(purpose),
    placeholderData: keepPreviousData,
    queryFn: async (): Promise<Page<RegulationRequest>> =>
      unwrap(
        await client.GET('/api/v1/regulation/requests', {
          params: {
            query: {
              status: params.status,
              priority: params.priority,
              specialty: params.specialty || undefined,
              service_code: params.service_code || undefined,
              requesting_cnes: params.requesting_cnes || undefined,
              provider_cnes: params.provider_cnes || undefined,
              citizen_id: params.citizen_id || undefined,
              territory: params.territory || undefined,
              issue: params.issue,
              sort: params.sort,
              cursor: params.cursor,
              limit: params.limit ?? 100,
            },
            header: { 'X-Purpose-Of-Use': purpose as Purpose },
          },
        }),
      ),
  });
}

export function useRegulationRequest(requestId: string | undefined) {
  const client = useCoreClient();
  const purpose = useCurrentPurpose();
  return useQuery({
    queryKey: coreKeys.regulationRequest(requestId ?? ''),
    enabled: Boolean(requestId) && Boolean(purpose),
    queryFn: async (): Promise<RegulationRequest> =>
      unwrap(
        await client.GET('/api/v1/regulation/requests/{requestId}', {
          params: {
            path: { requestId: requestId as string },
            header: { 'X-Purpose-Of-Use': purpose as Purpose },
          },
        }),
      ),
  });
}

export interface AddRegulationIssueInput {
  requestId: string;
  kind: RegulationIssueInput['kind'];
  description: string;
}

/**
 * Registra pendência documental/administrativa (REG-005). O SUS Nexus nunca decide nem
 * altera prioridade: a decisão permanece no sistema oficial de regulação (REG-009).
 */
export function useAddRegulationIssue() {
  const client = useCoreClient();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: AddRegulationIssueInput): Promise<RegulationRequest> =>
      unwrap(
        await client.POST('/api/v1/regulation/requests/{requestId}/issues', {
          params: { path: { requestId: input.requestId } },
          body: { kind: input.kind, description: input.description, origin: { kind: 'user' } },
        }),
      ),
    onSuccess: async (request) => {
      qc.setQueryData(coreKeys.regulationRequest(request.id), request);
      await qc.invalidateQueries({ queryKey: coreKeys.regulation() });
    },
  });
}

export interface ProviderCapacityParams {
  provider_cnes?: string;
  service_code?: string;
  competence?: string;
  cursor?: string;
  limit?: number;
}

/** Oferta/capacidade por prestador e serviço (REG-006). */
export function useProviderCapacity(
  params: ProviderCapacityParams = {},
  options?: { enabled?: boolean },
) {
  const client = useCoreClient();
  return useQuery({
    queryKey: coreKeys.regulationCapacity(params),
    enabled: options?.enabled ?? true,
    placeholderData: keepPreviousData,
    queryFn: async (): Promise<Page<ProviderCapacity>> =>
      unwrap(
        await client.GET('/api/v1/regulation/capacity', {
          params: {
            query: {
              provider_cnes: params.provider_cnes || undefined,
              service_code: params.service_code || undefined,
              competence: params.competence || undefined,
              cursor: params.cursor,
              limit: params.limit ?? 100,
            },
          },
        }),
      ),
  });
}

/** Indicadores da fila agrupados (espera média/p90, SLA estourado, pendências, capacidade). */
export function useRegulationQueueSummary(groupBy: RegulationQueueGroupBy = 'specialty') {
  const client = useCoreClient();
  return useQuery({
    queryKey: coreKeys.regulationQueueSummary({ group_by: groupBy }),
    placeholderData: keepPreviousData,
    queryFn: async (): Promise<{ items: RegulationQueueItem[] }> =>
      unwrap(
        await client.GET('/api/v1/regulation/queues/summary', {
          params: { query: { group_by: groupBy } },
        }),
      ),
  });
}
