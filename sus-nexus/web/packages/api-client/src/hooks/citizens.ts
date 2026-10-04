'use client';

import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { unwrap } from '../client';
import { coreKeys } from '../keys';
import { useCoreClient, useCurrentPurpose } from '../provider';
import type {
  CitizenDetail,
  CitizenOperationalSummary,
  CitizenSummary,
  Page,
  Purpose,
  RegistrationState,
} from '../types';

export interface CitizenSearchParams {
  q?: string;
  identifier?: string;
  birthdate?: string;
  registration_state?: RegistrationState;
  cursor?: string;
  limit?: number;
}

function hasCriteria(p: CitizenSearchParams): boolean {
  return Boolean(p.q?.trim() || p.identifier?.trim() || p.birthdate);
}

/** Busca de cidadãos por nome / identificador (CNS|CPF) / data de nascimento. */
export function useCitizenSearch(params: CitizenSearchParams, options?: { enabled?: boolean }) {
  const client = useCoreClient();
  const purpose = useCurrentPurpose();
  const enabled = (options?.enabled ?? true) && Boolean(purpose) && hasCriteria(params);
  return useQuery({
    queryKey: coreKeys.citizenSearch({ ...params, purpose }),
    enabled,
    placeholderData: keepPreviousData,
    queryFn: async (): Promise<Page<CitizenSummary>> =>
      unwrap(
        await client.GET('/api/v1/citizens', {
          params: {
            query: {
              q: params.q || undefined,
              identifier: params.identifier || undefined,
              birthdate: params.birthdate || undefined,
              registration_state: params.registration_state,
              cursor: params.cursor,
              limit: params.limit ?? 20,
            },
            header: { 'X-Purpose-Of-Use': purpose as Purpose },
          },
        }),
      ),
  });
}

export function useCitizen(citizenId: string | undefined) {
  const client = useCoreClient();
  const purpose = useCurrentPurpose();
  return useQuery({
    queryKey: coreKeys.citizen(citizenId ?? ''),
    enabled: Boolean(citizenId) && Boolean(purpose),
    queryFn: async (): Promise<CitizenDetail> =>
      unwrap(
        await client.GET('/api/v1/citizens/{citizenId}', {
          params: {
            path: { citizenId: citizenId as string },
            header: { 'X-Purpose-Of-Use': purpose as Purpose },
          },
        }),
      ),
  });
}

export function useCitizenSummary(citizenId: string | undefined) {
  const client = useCoreClient();
  const purpose = useCurrentPurpose();
  return useQuery({
    queryKey: coreKeys.citizenSummary(citizenId ?? ''),
    enabled: Boolean(citizenId) && Boolean(purpose),
    queryFn: async (): Promise<CitizenOperationalSummary> =>
      unwrap(
        await client.GET('/api/v1/citizens/{citizenId}/summary', {
          params: {
            path: { citizenId: citizenId as string },
            header: { 'X-Purpose-Of-Use': purpose as Purpose },
          },
        }),
      ),
  });
}

export interface RevealIdentifierInput {
  citizenId: string;
  identifierId: string;
  purpose: Purpose;
  justification: string;
}

/**
 * Revela um identificador (CPF/CNS) em claro. Exige finalidade + justificativa;
 * o backend registra `access_log`. Nunca armazene o valor retornado em cache.
 */
export function useRevealIdentifier() {
  const client = useCoreClient();
  return useMutation({
    mutationFn: async (input: RevealIdentifierInput): Promise<{ system: string; value: string }> =>
      unwrap(
        await client.POST('/api/v1/citizens/{citizenId}/identifiers/{identifierId}/reveal', {
          params: { path: { citizenId: input.citizenId, identifierId: input.identifierId } },
          body: { purpose: input.purpose, justification: input.justification },
        }),
      ),
  });
}

export function useInvalidateCitizen() {
  const qc = useQueryClient();
  return (citizenId: string) => qc.invalidateQueries({ queryKey: coreKeys.citizen(citizenId) });
}
