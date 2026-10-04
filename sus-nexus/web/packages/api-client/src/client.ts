import createClient, { type Client, type Middleware } from 'openapi-fetch';
import type { paths } from './generated/core';
import type { Problem, Purpose } from './types';

export const HEADER_PURPOSE = 'X-Purpose-Of-Use';
export const HEADER_CORRELATION = 'X-Correlation-Id';

export type CoreClient = Client<paths>;

export interface CoreClientOptions {
  /** Base da API. No navegador: `/api/core` (proxy BFF). No servidor: URL do core. */
  baseUrl: string;
  fetch?: typeof globalThis.fetch;
  /** Finalidade de acesso corrente (LGPD). Injetada quando a requisição não a define. */
  getPurpose?: () => Purpose | undefined;
  /** Gera um id de correlação por requisição (padrão: `crypto.randomUUID`). */
  getCorrelationId?: () => string;
  /** Cabeçalhos adicionais (ex.: Bearer no servidor). Nunca use no navegador. */
  getHeaders?: () => Record<string, string> | Promise<Record<string, string>>;
}

export function newCorrelationId(): string {
  if (typeof globalThis.crypto?.randomUUID === 'function') {
    return globalThis.crypto.randomUUID();
  }
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
}

/** Erro de API com detalhes RFC 9457. */
export class CoreApiError extends Error {
  readonly status: number;
  readonly problem: Problem | undefined;
  readonly correlationId: string | undefined;

  constructor(status: number, problem: Problem | undefined, correlationId?: string) {
    super(problem?.title ?? problem?.detail ?? `Erro HTTP ${status}`);
    this.name = 'CoreApiError';
    this.status = status;
    this.problem = problem;
    this.correlationId = problem?.correlation_id ?? correlationId;
  }
}

/** Interceptador que injeta `X-Purpose-Of-Use` e `X-Correlation-Id` em toda requisição. */
export function createHeadersMiddleware(options: CoreClientOptions): Middleware {
  return {
    async onRequest({ request }) {
      if (!request.headers.has(HEADER_PURPOSE)) {
        const purpose = options.getPurpose?.();
        if (purpose) request.headers.set(HEADER_PURPOSE, purpose);
      }
      if (!request.headers.has(HEADER_CORRELATION)) {
        request.headers.set(HEADER_CORRELATION, (options.getCorrelationId ?? newCorrelationId)());
      }
      if (options.getHeaders) {
        const extra = await options.getHeaders();
        for (const [k, v] of Object.entries(extra)) request.headers.set(k, v);
      }
      return request;
    },
  };
}

export function createCoreClient(options: CoreClientOptions): CoreClient {
  const client = createClient<paths>({
    baseUrl: options.baseUrl,
    fetch: options.fetch,
    headers: { Accept: 'application/json, application/problem+json' },
  });
  client.use(createHeadersMiddleware(options));
  return client;
}

/** Converte o resultado do openapi-fetch em dado ou lança `CoreApiError`. */
export function unwrap<T>(result: { data?: T; error?: unknown; response: Response }): T {
  if (result.error !== undefined || !result.response.ok) {
    const problem =
      result.error && typeof result.error === 'object' ? (result.error as Problem) : undefined;
    throw new CoreApiError(
      result.response.status,
      problem,
      result.response.headers.get(HEADER_CORRELATION) ?? undefined,
    );
  }
  return result.data as T;
}
