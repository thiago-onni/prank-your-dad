'use client';

import { keepPreviousData, useMutation, useQuery } from '@tanstack/react-query';
import { unwrap } from '../client';
import { coreKeys } from '../keys';
import { useCoreClient, useCurrentPurpose } from '../provider';
import type {
  ExamIssue,
  ExamOrder,
  ExamOrderStatus,
  ExamResultDocument,
  Page,
  Purpose,
} from '../types';

export interface ExamOrdersParams {
  status?: ExamOrderStatus;
  issue?: ExamIssue;
  citizen_id?: string;
  requesting_cnes?: string;
  cursor?: string;
  limit?: number;
}

/** Pedidos de exame (ciclo pedido → agendamento → realização → laudo → retorno). */
export function useExamOrders(params: ExamOrdersParams = {}, options?: { enabled?: boolean }) {
  const client = useCoreClient();
  const purpose = useCurrentPurpose();
  return useQuery({
    queryKey: coreKeys.examOrders({ ...params, purpose }),
    enabled: (options?.enabled ?? true) && Boolean(purpose),
    placeholderData: keepPreviousData,
    queryFn: async (): Promise<Page<ExamOrder>> =>
      unwrap(
        await client.GET('/api/v1/exams/orders', {
          params: {
            query: {
              status: params.status,
              issue: params.issue,
              citizen_id: params.citizen_id || undefined,
              requesting_cnes: params.requesting_cnes || undefined,
              cursor: params.cursor,
              limit: params.limit ?? 100,
            },
            header: { 'X-Purpose-Of-Use': purpose as Purpose },
          },
        }),
      ),
  });
}

export function useExamOrder(orderId: string | undefined) {
  const client = useCoreClient();
  const purpose = useCurrentPurpose();
  return useQuery({
    queryKey: coreKeys.examOrder(orderId ?? ''),
    enabled: Boolean(orderId) && Boolean(purpose),
    queryFn: async (): Promise<ExamOrder> =>
      unwrap(
        await client.GET('/api/v1/exams/orders/{orderId}', {
          params: {
            path: { orderId: orderId as string },
            header: { 'X-Purpose-Of-Use': purpose as Purpose },
          },
        }),
      ),
  });
}

export interface ExamResultDocumentInput {
  orderId: string;
  resultId: string;
  /** Finalidade declarada; registrada no access_log pelo core (EXA-006). */
  purpose: Purpose;
}

/**
 * Obtém a URL assinada (curta) do laudo. É uma mutação porque cada chamada gera
 * `access_log` e a URL expira — o resultado nunca entra no cache de consultas.
 */
export function useExamResultDocument() {
  const client = useCoreClient();
  return useMutation({
    mutationFn: async (input: ExamResultDocumentInput): Promise<ExamResultDocument> =>
      unwrap(
        await client.GET('/api/v1/exams/orders/{orderId}/results/{resultId}/document', {
          params: {
            path: { orderId: input.orderId, resultId: input.resultId },
            header: { 'X-Purpose-Of-Use': input.purpose },
          },
        }),
      ),
  });
}
