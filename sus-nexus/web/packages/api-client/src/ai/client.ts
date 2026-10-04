import createClient, { type Client } from 'openapi-fetch';
import { createHeadersMiddleware, unwrap } from '../client';
import type { paths } from '../generated/ai';
import {
  normalizeKillSwitch,
  normalizeRun,
  normalizeTool,
  type AgentApproval,
  type AgentDescriptor,
  type AgentRunRecord,
  type ApprovalStatus,
  type BiSituationInput,
  type KillSwitchResponse,
  type KillSwitchState,
  type RunStatus,
  type ToolDescriptor,
} from './types';

/** Cliente openapi-fetch tipado pelo contrato `ai-service.yaml`. */
export type AiRawClient = Client<paths>;

/**
 * Cliente do ai-service. No navegador aponta para o proxy BFF (`/api/ai`), que anexa o Bearer
 * da sessão e o tenant; no servidor pode receber a URL do serviço e `getHeaders` com o Bearer.
 */
export interface AiClientOptions {
  baseUrl: string;
  fetch?: typeof globalThis.fetch;
  getCorrelationId?: () => string;
  /** Cabeçalhos adicionais (ex.: Bearer no servidor). Nunca use no navegador. */
  getHeaders?: () => Record<string, string> | Promise<Record<string, string>>;
}

export interface AgentRunsQuery {
  agent_id?: string;
  status?: RunStatus;
  limit?: number;
}

export interface ApprovalsQuery {
  status?: ApprovalStatus;
  agent_id?: string;
}

export interface AiClient {
  /** Acesso direto ao cliente tipado (respostas brutas do contrato). */
  raw: AiRawClient;
  listAgents(): Promise<AgentDescriptor[]>;
  listTools(): Promise<ToolDescriptor[]>;
  listRuns(params?: AgentRunsQuery): Promise<AgentRunRecord[]>;
  getRun(runId: string): Promise<AgentRunRecord>;
  approveAction(runId: string, actionId: string, justification: string): Promise<AgentRunRecord>;
  rejectAction(runId: string, actionId: string, justification: string): Promise<AgentRunRecord>;
  listApprovals(params?: ApprovalsQuery): Promise<AgentApproval[]>;
  getKillSwitch(): Promise<KillSwitchResponse>;
  setKillSwitch(state: KillSwitchState): Promise<KillSwitchResponse>;
  /** Agente de BI (somente agregados; município do token; gestor/auditor/admin_municipal). */
  runBiSituationAnalyst(input: BiSituationInput): Promise<AgentRunRecord>;
}

export function createAiRawClient(options: AiClientOptions): AiRawClient {
  const client = createClient<paths>({
    baseUrl: options.baseUrl,
    fetch: options.fetch,
    headers: { Accept: 'application/json, application/problem+json' },
  });
  client.use(
    createHeadersMiddleware({
      baseUrl: options.baseUrl,
      getCorrelationId: options.getCorrelationId,
      getHeaders: options.getHeaders,
    }),
  );
  return client;
}

export function createAiClient(options: AiClientOptions): AiClient {
  const raw = createAiRawClient(options);
  const decide = async (
    kind: 'approve' | 'reject',
    runId: string,
    actionId: string,
    justification: string,
  ): Promise<AgentRunRecord> => {
    const params = { path: { run_id: runId, action_id: actionId } };
    const body = { justification };
    const result =
      kind === 'approve'
        ? await raw.POST('/runs/{run_id}/actions/{action_id}/approve', { params, body })
        : await raw.POST('/runs/{run_id}/actions/{action_id}/reject', { params, body });
    return normalizeRun(unwrap(result));
  };

  return {
    raw,
    listAgents: async () => unwrap(await raw.GET('/agents')),
    listTools: async () => unwrap(await raw.GET('/tools')).map(normalizeTool),
    listRuns: async (params = {}) =>
      unwrap(
        await raw.GET('/runs', {
          params: {
            query: {
              agent_id: params.agent_id || undefined,
              status: params.status,
              limit: params.limit ?? 50,
            },
          },
        }),
      ).map(normalizeRun),
    getRun: async (runId) =>
      normalizeRun(
        unwrap(await raw.GET('/runs/{run_id}', { params: { path: { run_id: runId } } })),
      ),
    approveAction: (runId, actionId, justification) =>
      decide('approve', runId, actionId, justification),
    rejectAction: (runId, actionId, justification) =>
      decide('reject', runId, actionId, justification),
    listApprovals: async (params = {}) =>
      unwrap(
        await raw.GET('/approvals', {
          params: {
            query: { status: params.status ?? 'pending', agent_id: params.agent_id || undefined },
          },
        }),
      ),
    getKillSwitch: async () => normalizeKillSwitch(unwrap(await raw.GET('/admin/kill-switch'))),
    setKillSwitch: async (state) =>
      normalizeKillSwitch(unwrap(await raw.POST('/admin/kill-switch', { body: state }))),
    runBiSituationAnalyst: async (input) =>
      normalizeRun(unwrap(await raw.POST('/agents/bi_situation_analyst/run', { body: input }))),
  };
}
