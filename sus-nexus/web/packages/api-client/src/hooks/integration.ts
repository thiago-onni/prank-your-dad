'use client';

import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { unwrap } from '../client';
import { coreKeys } from '../keys';
import { useCoreClient } from '../provider';
import type {
  ConnectorStatus,
  DeadLetter,
  IntegrationMessage,
  IntegrationMessageStatus,
  Page,
  ReconciliationEntry,
} from '../types';

export function useConnectors(options?: { refetchInterval?: number }) {
  const client = useCoreClient();
  return useQuery({
    queryKey: coreKeys.connectors(),
    refetchInterval: options?.refetchInterval,
    queryFn: async (): Promise<ConnectorStatus[]> =>
      unwrap(await client.GET('/api/v1/integration/connectors')),
  });
}

export interface IntegrationMessagesParams {
  connector_id?: string;
  status?: IntegrationMessageStatus;
  from?: string;
  to?: string;
  cursor?: string;
  limit?: number;
}

export function useIntegrationMessages(params: IntegrationMessagesParams = {}) {
  const client = useCoreClient();
  return useQuery({
    queryKey: coreKeys.integrationMessages(params),
    placeholderData: keepPreviousData,
    queryFn: async (): Promise<Page<IntegrationMessage>> =>
      unwrap(
        await client.GET('/api/v1/integration/messages', {
          params: {
            query: {
              connector_id: params.connector_id || undefined,
              status: params.status,
              from: params.from || undefined,
              to: params.to || undefined,
              cursor: params.cursor,
              limit: params.limit ?? 50,
            },
          },
        }),
      ),
  });
}

export function useIntegrationMessage(messageId: string | undefined) {
  const client = useCoreClient();
  return useQuery({
    queryKey: coreKeys.integrationMessage(messageId ?? ''),
    enabled: Boolean(messageId),
    queryFn: async (): Promise<IntegrationMessage> =>
      unwrap(
        await client.GET('/api/v1/integration/messages/{messageId}', {
          params: { path: { messageId: messageId as string } },
        }),
      ),
  });
}

export function useReprocessMessage() {
  const client = useCoreClient();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (messageId: string): Promise<void> => {
      const result = await client.POST('/api/v1/integration/messages/{messageId}/reprocess', {
        params: { path: { messageId } },
      });
      unwrap(result);
    },
    onSuccess: async (_data, messageId) => {
      await qc.invalidateQueries({ queryKey: coreKeys.integrationMessage(messageId) });
      await qc.invalidateQueries({ queryKey: [...coreKeys.all, 'integration'] });
    },
  });
}

export function useDeadLetters(params: { cursor?: string; limit?: number } = {}) {
  const client = useCoreClient();
  return useQuery({
    queryKey: coreKeys.deadLetters(params),
    placeholderData: keepPreviousData,
    queryFn: async (): Promise<Page<DeadLetter>> =>
      unwrap(
        await client.GET('/api/v1/integration/dlq', {
          params: { query: { cursor: params.cursor, limit: params.limit ?? 50 } },
        }),
      ),
  });
}

export function useReconciliation(
  params: { connector_id?: string; cursor?: string; limit?: number } = {},
) {
  const client = useCoreClient();
  return useQuery({
    queryKey: coreKeys.reconciliation(params),
    placeholderData: keepPreviousData,
    queryFn: async (): Promise<Page<ReconciliationEntry>> =>
      unwrap(
        await client.GET('/api/v1/integration/reconciliation', {
          params: {
            query: {
              connector_id: params.connector_id || undefined,
              cursor: params.cursor,
              limit: params.limit ?? 50,
            },
          },
        }),
      ),
  });
}
