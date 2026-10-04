import type { z } from 'zod';
import { CoreApiError, HEADER_CORRELATION, newCorrelationId } from '../client';
import type { Problem } from '../types';
import {
  agentApprovalSchema,
  agentDescriptorSchema,
  agentRunRecordSchema,
  killSwitchResponseSchema,
  toolDescriptorSchema,
  type AgentApproval,
  type AgentDescriptor,
  type AgentRunRecord,
  type ApprovalStatus,
  type KillSwitchResponse,
  type KillSwitchState,
  type RunStatus,
  type ToolDescriptor,
} from './types';

/**
 * Cliente do ai-service. No navegador aponta para o proxy BFF (`/api/ai`), que anexa o Bearer
 * da sessão. Sem tipos gerados ainda (ver `./types.ts`): as respostas são validadas com zod.
 */
export interface AiClientOptions {
  baseUrl: string;
  fetch?: typeof globalThis.fetch;
  getCorrelationId?: () => string;
  /** Cabeçalhos adicionais (ex.: Bearer no servidor). Nunca use no navegador. */
  getHeaders?: () => Record<string, string> | Promise<Record<string, string>>;
}

/** Resposta do ai-service fora do contrato esperado. */
export class AiContractError extends Error {
  readonly issues: string[];
  constructor(path: string, issues: string[]) {
    super(`Resposta inesperada do ai-service em ${path}`);
    this.name = 'AiContractError';
    this.issues = issues;
  }
}

export interface AiClient {
  listAgents(): Promise<AgentDescriptor[]>;
  listTools(): Promise<ToolDescriptor[]>;
  listRuns(params?: { agent_id?: string; status?: RunStatus; limit?: number }): Promise<
    AgentRunRecord[]
  >;
  getRun(runId: string): Promise<AgentRunRecord>;
  approveAction(runId: string, actionId: string, justification: string): Promise<AgentRunRecord>;
  rejectAction(runId: string, actionId: string, justification: string): Promise<AgentRunRecord>;
  listApprovals(params?: { status?: ApprovalStatus; agent_id?: string }): Promise<AgentApproval[]>;
  getKillSwitch(): Promise<KillSwitchResponse>;
  setKillSwitch(state: KillSwitchState): Promise<KillSwitchResponse>;
}

export function createAiClient(options: AiClientOptions): AiClient {
  const base = options.baseUrl.replace(/\/$/, '');
  const doFetch = options.fetch ?? ((input, init) => globalThis.fetch(input, init));

  async function request<T>(
    method: 'GET' | 'POST',
    path: string,
    schema: z.ZodType<T, z.ZodTypeDef, unknown>,
    init: { query?: Record<string, string | number | undefined>; body?: unknown } = {},
  ): Promise<T> {
    const url = new URL(`${base}${path}`, 'http://placeholder.invalid');
    for (const [k, v] of Object.entries(init.query ?? {})) {
      if (v !== undefined && v !== '') url.searchParams.set(k, String(v));
    }
    const headers = new Headers({ Accept: 'application/json, application/problem+json' });
    headers.set(HEADER_CORRELATION, (options.getCorrelationId ?? newCorrelationId)());
    if (init.body !== undefined) headers.set('content-type', 'application/json');
    if (options.getHeaders) {
      for (const [k, v] of Object.entries(await options.getHeaders())) headers.set(k, v);
    }
    // Base relativa (`/api/ai`) → mantém relativa; absoluta → usa a URL completa.
    const target = base.startsWith('http') ? url.toString() : `${url.pathname}${url.search}`;
    const response = await doFetch(target, {
      method,
      headers,
      body: init.body === undefined ? undefined : JSON.stringify(init.body),
      cache: 'no-store',
    });
    const text = await response.text();
    let json: unknown = undefined;
    if (text) {
      try {
        json = JSON.parse(text) as unknown;
      } catch {
        json = undefined;
      }
    }
    if (!response.ok) {
      const problem = json && typeof json === 'object' ? (json as Problem) : undefined;
      throw new CoreApiError(
        response.status,
        problem,
        response.headers.get(HEADER_CORRELATION) ?? headers.get(HEADER_CORRELATION) ?? undefined,
      );
    }
    const parsed = schema.safeParse(json);
    if (!parsed.success) {
      throw new AiContractError(
        path,
        parsed.error.issues.map((i) => `${i.path.join('.')}: ${i.message}`),
      );
    }
    return parsed.data;
  }

  return {
    listAgents: () => request('GET', '/agents', agentDescriptorSchema.array()),
    listTools: () => request('GET', '/tools', toolDescriptorSchema.array()),
    listRuns: (params = {}) =>
      request('GET', '/runs', agentRunRecordSchema.array(), {
        query: { agent_id: params.agent_id, status: params.status, limit: params.limit ?? 50 },
      }),
    getRun: (runId) =>
      request('GET', `/runs/${encodeURIComponent(runId)}`, agentRunRecordSchema),
    approveAction: (runId, actionId, justification) =>
      request(
        'POST',
        `/runs/${encodeURIComponent(runId)}/actions/${encodeURIComponent(actionId)}/approve`,
        agentRunRecordSchema,
        { body: { justification } },
      ),
    rejectAction: (runId, actionId, justification) =>
      request(
        'POST',
        `/runs/${encodeURIComponent(runId)}/actions/${encodeURIComponent(actionId)}/reject`,
        agentRunRecordSchema,
        { body: { justification } },
      ),
    listApprovals: (params = {}) =>
      request('GET', '/approvals', agentApprovalSchema.array(), {
        query: { status: params.status ?? 'pending', agent_id: params.agent_id },
      }),
    getKillSwitch: () => request('GET', '/admin/kill-switch', killSwitchResponseSchema),
    setKillSwitch: (state) =>
      request('POST', '/admin/kill-switch', killSwitchResponseSchema, { body: state }),
  };
}
