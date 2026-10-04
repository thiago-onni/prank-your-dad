'use client';

import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { unwrap } from '../client';
import { coreKeys } from '../keys';
import { useCoreClient, useCurrentPurpose } from '../provider';
import type {
  DischargeFollowupInput,
  FollowupStatus,
  HospitalEpisode,
  HospitalEpisodeStatus,
  Page,
  Purpose,
} from '../types';

export interface HospitalEpisodesParams {
  citizen_id?: string;
  hospital_cnes?: string;
  status?: HospitalEpisodeStatus;
  /** Início do período de alta (ISO 8601). */
  discharged_from?: string;
  /** Fim do período de alta (ISO 8601). */
  discharged_to?: string;
  /** UBS de referência do cidadão (visão da APS sobre sua população). */
  reference_cnes?: string;
  followup_status?: FollowupStatus;
  cursor?: string;
  limit?: number;
}

/** Internações e passagens por urgência (HOS-006). */
export function useHospitalEpisodes(
  params: HospitalEpisodesParams = {},
  options?: { enabled?: boolean },
) {
  const client = useCoreClient();
  const purpose = useCurrentPurpose();
  return useQuery({
    queryKey: coreKeys.hospitalEpisodes({ ...params, purpose }),
    enabled: (options?.enabled ?? true) && Boolean(purpose),
    placeholderData: keepPreviousData,
    queryFn: async (): Promise<Page<HospitalEpisode>> =>
      unwrap(
        await client.GET('/api/v1/hospital/episodes', {
          params: {
            query: {
              citizen_id: params.citizen_id || undefined,
              hospital_cnes: params.hospital_cnes || undefined,
              status: params.status,
              discharged_from: params.discharged_from || undefined,
              discharged_to: params.discharged_to || undefined,
              reference_cnes: params.reference_cnes || undefined,
              followup_status: params.followup_status,
              cursor: params.cursor,
              limit: params.limit ?? 100,
            },
            header: { 'X-Purpose-Of-Use': purpose as Purpose },
          },
        }),
      ),
  });
}

export function useHospitalEpisode(episodeId: string | undefined) {
  const client = useCoreClient();
  const purpose = useCurrentPurpose();
  return useQuery({
    queryKey: coreKeys.hospitalEpisode(episodeId ?? ''),
    enabled: Boolean(episodeId) && Boolean(purpose),
    queryFn: async (): Promise<HospitalEpisode> =>
      unwrap(
        await client.GET('/api/v1/hospital/episodes/{episodeId}', {
          params: {
            path: { episodeId: episodeId as string },
            header: { 'X-Purpose-Of-Use': purpose as Purpose },
          },
        }),
      ),
  });
}

export interface DischargeFollowupMutationInput extends DischargeFollowupInput {
  episodeId: string;
}

/** Registra o desfecho da tentativa de contato pós-alta (CUI-006). */
export function useRegisterDischargeFollowup() {
  const client = useCoreClient();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({
      episodeId,
      ...body
    }: DischargeFollowupMutationInput): Promise<HospitalEpisode> =>
      unwrap(
        await client.POST('/api/v1/hospital/episodes/{episodeId}/followup', {
          params: { path: { episodeId } },
          body,
        }),
      ),
    onSuccess: async (episode) => {
      qc.setQueryData(coreKeys.hospitalEpisode(episode.id), episode);
      await qc.invalidateQueries({ queryKey: [...coreKeys.hospital(), 'episodes'] });
      // O core conclui a tarefa vinculada e resolve a lacuna pós-alta.
      await qc.invalidateQueries({ queryKey: [...coreKeys.all, 'tasks'] });
      await qc.invalidateQueries({ queryKey: [...coreKeys.all, 'task'] });
      await qc.invalidateQueries({ queryKey: [...coreKeys.careplan(), 'gaps'] });
      await qc.invalidateQueries({ queryKey: coreKeys.citizenSummary(episode.citizen_id) });
    },
  });
}
