'use client';

import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { unwrap } from '../client';
import { coreKeys } from '../keys';
import { useCoreClient, useCurrentPurpose } from '../provider';
import type {
  CareGap,
  CareGapKind,
  CareGapResolveInput,
  CareGapStatus,
  CarePlan,
  CarePlanCloseInput,
  CarePlanCreate,
  CarePlanItemUpdate,
  CarePlanStatus,
  Page,
  Protocol,
  ProtocolCreate,
  ProtocolStatus,
  ProtocolTransitionInput,
  Purpose,
} from '../types';

// ---------- planos de cuidado ----------

export interface CarePlansParams {
  citizen_id?: string;
  care_line?: string;
  status?: CarePlanStatus;
  team_ine?: string;
  cnes?: string;
  cursor?: string;
  limit?: number;
}

export function useCarePlans(params: CarePlansParams = {}, options?: { enabled?: boolean }) {
  const client = useCoreClient();
  const purpose = useCurrentPurpose();
  return useQuery({
    queryKey: coreKeys.carePlans({ ...params, purpose }),
    enabled: (options?.enabled ?? true) && Boolean(purpose),
    placeholderData: keepPreviousData,
    queryFn: async (): Promise<Page<CarePlan>> =>
      unwrap(
        await client.GET('/api/v1/careplans', {
          params: {
            query: {
              citizen_id: params.citizen_id || undefined,
              care_line: params.care_line || undefined,
              status: params.status,
              team_ine: params.team_ine || undefined,
              cnes: params.cnes || undefined,
              cursor: params.cursor,
              limit: params.limit ?? 100,
            },
            header: { 'X-Purpose-Of-Use': purpose as Purpose },
          },
        }),
      ),
  });
}

export function useCarePlan(carePlanId: string | undefined) {
  const client = useCoreClient();
  const purpose = useCurrentPurpose();
  return useQuery({
    queryKey: coreKeys.carePlan(carePlanId ?? ''),
    enabled: Boolean(carePlanId) && Boolean(purpose),
    queryFn: async (): Promise<CarePlan> =>
      unwrap(
        await client.GET('/api/v1/careplans/{carePlanId}', {
          params: {
            path: { carePlanId: carePlanId as string },
            header: { 'X-Purpose-Of-Use': purpose as Purpose },
          },
        }),
      ),
  });
}

function useCarePlanInvalidation() {
  const qc = useQueryClient();
  return async (plan: CarePlan) => {
    qc.setQueryData(coreKeys.carePlan(plan.id), plan);
    await qc.invalidateQueries({ queryKey: [...coreKeys.careplan(), 'plans'] });
    await qc.invalidateQueries({ queryKey: [...coreKeys.careplan(), 'gaps'] });
    await qc.invalidateQueries({ queryKey: coreKeys.citizenSummary(plan.citizen_id) });
  };
}

/** Cria plano a partir de um protocolo vigente (CUI-001). */
export function useCreateCarePlan() {
  const client = useCoreClient();
  const invalidate = useCarePlanInvalidation();
  return useMutation({
    mutationFn: async (input: CarePlanCreate): Promise<CarePlan> =>
      unwrap(await client.POST('/api/v1/careplans', { body: input })),
    onSuccess: invalidate,
  });
}

export interface CarePlanItemMutationInput extends CarePlanItemUpdate {
  carePlanId: string;
  itemId: string;
}

/** Registra realização/desfecho de um item previsto (CUI-002). */
export function useUpdateCarePlanItem() {
  const client = useCoreClient();
  const invalidate = useCarePlanInvalidation();
  return useMutation({
    mutationFn: async ({
      carePlanId,
      itemId,
      ...body
    }: CarePlanItemMutationInput): Promise<CarePlan> =>
      unwrap(
        await client.POST('/api/v1/careplans/{carePlanId}/items/{itemId}', {
          params: { path: { carePlanId, itemId } },
          body,
        }),
      ),
    onSuccess: invalidate,
  });
}

export interface CloseCarePlanMutationInput extends CarePlanCloseInput {
  carePlanId: string;
}

export function useCloseCarePlan() {
  const client = useCoreClient();
  const invalidate = useCarePlanInvalidation();
  return useMutation({
    mutationFn: async ({ carePlanId, ...body }: CloseCarePlanMutationInput): Promise<CarePlan> =>
      unwrap(
        await client.POST('/api/v1/careplans/{carePlanId}/close', {
          params: { path: { carePlanId } },
          body,
        }),
      ),
    onSuccess: invalidate,
  });
}

// ---------- lacunas de cuidado / busca ativa ----------

export interface CareGapsParams {
  care_line?: string;
  gap_kind?: CareGapKind;
  cnes?: string;
  team_ine?: string;
  microarea?: string;
  status?: CareGapStatus;
  min_days_overdue?: number;
  cursor?: string;
  limit?: number;
}

/** Lacunas de cuidado / lista de busca ativa (CUI-003/004). */
export function useCareGaps(params: CareGapsParams = {}, options?: { enabled?: boolean }) {
  const client = useCoreClient();
  const purpose = useCurrentPurpose();
  return useQuery({
    queryKey: coreKeys.careGaps({ ...params, purpose }),
    enabled: (options?.enabled ?? true) && Boolean(purpose),
    placeholderData: keepPreviousData,
    queryFn: async (): Promise<Page<CareGap>> =>
      unwrap(
        await client.GET('/api/v1/caregaps', {
          params: {
            query: {
              care_line: params.care_line || undefined,
              gap_kind: params.gap_kind,
              cnes: params.cnes || undefined,
              team_ine: params.team_ine || undefined,
              microarea: params.microarea || undefined,
              status: params.status ?? 'open',
              min_days_overdue: params.min_days_overdue,
              cursor: params.cursor,
              limit: params.limit ?? 200,
            },
            header: { 'X-Purpose-Of-Use': purpose as Purpose },
          },
        }),
      ),
  });
}

export interface ResolveCareGapMutationInput extends CareGapResolveInput {
  careGapId: string;
}

/** Registra o desfecho da busca ativa (CUI-006). */
export function useResolveCareGap() {
  const client = useCoreClient();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ careGapId, ...body }: ResolveCareGapMutationInput): Promise<CareGap> =>
      unwrap(
        await client.POST('/api/v1/caregaps/{careGapId}/resolve', {
          params: { path: { careGapId } },
          body,
        }),
      ),
    onSuccess: async (gap) => {
      await qc.invalidateQueries({ queryKey: [...coreKeys.careplan(), 'gaps'] });
      await qc.invalidateQueries({ queryKey: [...coreKeys.careplan(), 'plans'] });
      await qc.invalidateQueries({ queryKey: coreKeys.citizenSummary(gap.citizen_id) });
    },
  });
}

// ---------- protocolos ----------

export interface ProtocolsParams {
  care_line?: string;
  status?: ProtocolStatus;
}

/** Protocolos de linha de cuidado (configuráveis, versionados — CUI-009). */
export function useProtocols(params: ProtocolsParams = {}, options?: { enabled?: boolean }) {
  const client = useCoreClient();
  return useQuery({
    queryKey: coreKeys.protocols(params),
    enabled: options?.enabled ?? true,
    queryFn: async (): Promise<Protocol[]> =>
      unwrap(
        await client.GET('/api/v1/protocols', {
          params: {
            query: { care_line: params.care_line || undefined, status: params.status },
          },
        }),
      ),
  });
}

/** Cria nova versão (rascunho) de protocolo. */
export function useCreateProtocolVersion() {
  const client = useCoreClient();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: ProtocolCreate): Promise<Protocol> =>
      unwrap(await client.POST('/api/v1/protocols', { body: input })),
    onSuccess: () => qc.invalidateQueries({ queryKey: [...coreKeys.careplan(), 'protocols'] }),
  });
}

export interface ProtocolTransitionMutationInput extends ProtocolTransitionInput {
  protocolId: string;
  version: string;
}

/** Ciclo de aprovação: draft → in_review → approved → active → revoked. */
export function useTransitionProtocol() {
  const client = useCoreClient();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({
      protocolId,
      version,
      ...body
    }: ProtocolTransitionMutationInput): Promise<Protocol> =>
      unwrap(
        await client.POST('/api/v1/protocols/{protocolId}/versions/{version}/transition', {
          params: { path: { protocolId, version } },
          body,
        }),
      ),
    onSuccess: () => qc.invalidateQueries({ queryKey: [...coreKeys.careplan(), 'protocols'] }),
  });
}
