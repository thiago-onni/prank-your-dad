'use client';

import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { newCorrelationId, unwrap } from '../client';
import { coreKeys } from '../keys';
import { useCoreClient, useCurrentPurpose } from '../provider';
import type {
  Competence,
  Page,
  ProductionBatch,
  ProductionBatchCreate,
  ProductionBatchStatus,
  ProductionCorrection,
  ProductionDeadline,
  ProductionExportLayout,
  ProductionIssue,
  ProductionIssueSeverity,
  ProductionIssueStatus,
  ProductionKind,
  ProductionOutcomeRegistration,
  ProductionOutcomeResult,
  ProductionRecord,
  ProductionRecordStatus,
  ProductionSummary,
  Purpose,
} from '../types';

/**
 * Produção e pré-auditoria BPA-C/BPA-I/APAC/AIH (PRO-001..010). Dado administrativo de
 * faturamento: CNS/CPF chegam apenas mascarados. Ações humanas (corrigir, aprovar, exportar)
 * são autorizadas no backend (OPA); agente de IA recebe 403.
 */

export interface ProductionRecordsParams {
  competence?: Competence;
  cnes?: string;
  kind?: ProductionKind;
  status?: ProductionRecordStatus;
  procedure_code?: string;
  citizen_id?: string;
  batch_id?: string;
  cursor?: string;
  limit?: number;
}

/** Registros de produção com status de pré-auditoria. Exige finalidade. */
export function useProductionRecords(
  params: ProductionRecordsParams = {},
  options?: { enabled?: boolean },
) {
  const client = useCoreClient();
  const purpose = useCurrentPurpose();
  return useQuery({
    queryKey: coreKeys.productionRecords({ ...params, purpose }),
    enabled: (options?.enabled ?? true) && Boolean(purpose),
    placeholderData: keepPreviousData,
    queryFn: async (): Promise<Page<ProductionRecord>> =>
      unwrap(
        await client.GET('/api/v1/production/records', {
          params: {
            query: {
              competence: params.competence || undefined,
              cnes: params.cnes || undefined,
              kind: params.kind,
              status: params.status,
              procedure_code: params.procedure_code || undefined,
              citizen_id: params.citizen_id || undefined,
              batch_id: params.batch_id || undefined,
              cursor: params.cursor,
              limit: params.limit ?? 50,
            },
            header: { 'X-Purpose-Of-Use': purpose as Purpose },
          },
        }),
      ),
  });
}

export function useProductionRecord(recordId: string | undefined) {
  const client = useCoreClient();
  const purpose = useCurrentPurpose();
  return useQuery({
    queryKey: coreKeys.productionRecord(recordId ?? ''),
    enabled: Boolean(recordId) && Boolean(purpose),
    queryFn: async (): Promise<ProductionRecord> =>
      unwrap(
        await client.GET('/api/v1/production/records/{recordId}', {
          params: {
            path: { recordId: recordId as string },
            header: { 'X-Purpose-Of-Use': purpose as Purpose },
          },
        }),
      ),
  });
}

export interface ProductionIssuesParams {
  severity?: ProductionIssueSeverity;
  rule?: string;
  competence?: Competence;
  cnes?: string;
  kind?: ProductionKind;
  status?: ProductionIssueStatus;
  record_id?: string;
  cursor?: string;
  limit?: number;
}

/** Fila de pendências de pré-auditoria (padrão: abertas). */
export function useProductionIssues(
  params: ProductionIssuesParams = {},
  options?: { enabled?: boolean },
) {
  const client = useCoreClient();
  return useQuery({
    queryKey: coreKeys.productionIssues(params),
    enabled: options?.enabled ?? true,
    placeholderData: keepPreviousData,
    queryFn: async (): Promise<Page<ProductionIssue>> =>
      unwrap(
        await client.GET('/api/v1/production/issues', {
          params: {
            query: {
              severity: params.severity,
              rule: params.rule || undefined,
              competence: params.competence || undefined,
              cnes: params.cnes || undefined,
              kind: params.kind,
              status: params.status,
              record_id: params.record_id || undefined,
              cursor: params.cursor,
              limit: params.limit ?? 50,
            },
          },
        }),
      ),
  });
}

export interface ProductionBatchesParams {
  competence?: Competence;
  cnes?: string;
  status?: ProductionBatchStatus;
  cursor?: string;
  limit?: number;
}

export function useProductionBatches(
  params: ProductionBatchesParams = {},
  options?: { enabled?: boolean },
) {
  const client = useCoreClient();
  return useQuery({
    queryKey: coreKeys.productionBatches(params),
    enabled: options?.enabled ?? true,
    placeholderData: keepPreviousData,
    queryFn: async (): Promise<Page<ProductionBatch>> =>
      unwrap(
        await client.GET('/api/v1/production/batches', {
          params: {
            query: {
              competence: params.competence || undefined,
              cnes: params.cnes || undefined,
              status: params.status,
              cursor: params.cursor,
              limit: params.limit ?? 50,
            },
          },
        }),
      ),
  });
}

export function useProductionBatch(batchId: string | undefined) {
  const client = useCoreClient();
  return useQuery({
    queryKey: coreKeys.productionBatch(batchId ?? ''),
    enabled: Boolean(batchId),
    queryFn: async (): Promise<ProductionBatch> =>
      unwrap(
        await client.GET('/api/v1/production/batches/{batchId}', {
          params: { path: { batchId: batchId as string } },
        }),
      ),
  });
}

/** Painel da competência (PRO-009). */
export function useProductionSummary(
  params: { competence: Competence; cnes?: string },
  options?: { enabled?: boolean },
) {
  const client = useCoreClient();
  return useQuery({
    queryKey: coreKeys.productionSummary(params),
    enabled: (options?.enabled ?? true) && Boolean(params.competence),
    placeholderData: keepPreviousData,
    queryFn: async (): Promise<ProductionSummary> =>
      unwrap(
        await client.GET('/api/v1/production/summary', {
          params: { query: { competence: params.competence, cnes: params.cnes || undefined } },
        }),
      ),
  });
}

/** Competências e prazos de apresentação (PRO-007). */
export function useProductionDeadlines(params: { from?: Competence; to?: Competence } = {}) {
  const client = useCoreClient();
  return useQuery({
    queryKey: coreKeys.productionDeadlines(params),
    queryFn: async (): Promise<{ items: ProductionDeadline[] }> =>
      unwrap(
        await client.GET('/api/v1/production/deadlines', {
          params: { query: { from: params.from || undefined, to: params.to || undefined } },
        }),
      ),
  });
}

async function invalidateProduction(qc: ReturnType<typeof useQueryClient>) {
  await qc.invalidateQueries({ queryKey: coreKeys.production() });
  // Correções e rejeições oficiais abrem/fecham tarefas `production_issue`.
  await qc.invalidateQueries({ queryKey: [...coreKeys.all, 'tasks'] });
}

export interface CorrectProductionRecordInput extends ProductionCorrection {
  recordId: string;
}

/** Corrige o registro com justificativa e revalida (PRO-006). Avisos podem ser dispensados. */
export function useCorrectProductionRecord() {
  const client = useCoreClient();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({
      recordId,
      ...body
    }: CorrectProductionRecordInput): Promise<ProductionRecord> =>
      unwrap(
        await client.POST('/api/v1/production/records/{recordId}/corrections', {
          params: { path: { recordId } },
          body,
        }),
      ),
    onSuccess: async (record) => {
      qc.setQueryData(coreKeys.productionRecord(record.id), record);
      await invalidateProduction(qc);
    },
  });
}

/** Gera lote rascunho (somente registros `validated`) — PRO-005. */
export function useCreateProductionBatch() {
  const client = useCoreClient();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (body: ProductionBatchCreate): Promise<ProductionBatch> =>
      unwrap(await client.POST('/api/v1/production/batches', { body })),
    onSuccess: async (batch) => {
      qc.setQueryData(coreKeys.productionBatch(batch.id), batch);
      await invalidateProduction(qc);
    },
  });
}

/** Aprovação humana obrigatória do lote, com justificativa (PRO-010). */
export function useApproveProductionBatch() {
  const client = useCoreClient();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: {
      batchId: string;
      justification: string;
    }): Promise<ProductionBatch> =>
      unwrap(
        await client.POST('/api/v1/production/batches/{batchId}/approve', {
          params: { path: { batchId: input.batchId } },
          body: { justification: input.justification },
        }),
      ),
    onSuccess: async (batch) => {
      qc.setQueryData(coreKeys.productionBatch(batch.id), batch);
      await invalidateProduction(qc);
    },
  });
}

/** Exporta lote aprovado (arquivo + SHA-256); registros passam a `exported`. */
export function useExportProductionBatch() {
  const client = useCoreClient();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: {
      batchId: string;
      layout?: ProductionExportLayout;
    }): Promise<ProductionBatch> =>
      unwrap(
        await client.POST('/api/v1/production/batches/{batchId}/export', {
          params: { path: { batchId: input.batchId } },
          body: input.layout ? { layout: input.layout } : {},
        }),
      ),
    onSuccess: async (batch) => {
      qc.setQueryData(coreKeys.productionBatch(batch.id), batch);
      await invalidateProduction(qc);
    },
  });
}

/** Registra o retorno do processamento oficial (PRO-008). Idempotente por origem. */
export function useRegisterProductionOutcome() {
  const client = useCoreClient();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (
      body: ProductionOutcomeRegistration & { idempotencyKey?: string },
    ): Promise<ProductionOutcomeResult> => {
      const { idempotencyKey, ...payload } = body;
      return unwrap(
        await client.POST('/api/v1/production/outcomes', {
          params: { header: { 'Idempotency-Key': idempotencyKey ?? newCorrelationId() } },
          body: payload,
        }),
      );
    },
    onSuccess: async () => {
      await invalidateProduction(qc);
    },
  });
}
