import { CoreApiError, HEADER_CORRELATION, HEADER_PURPOSE, newCorrelationId } from './client';
import type { Problem, Purpose } from './types';

/**
 * GET JSON numa rota própria do BFF (mesma origem, cookie de sessão). Injeta finalidade e
 * correlação como o cliente do core e converte erros (problem+json/OperationOutcome) em `CoreApiError`.
 */
export async function bffGetJson<T>(
  url: string,
  options: { purpose?: Purpose; signal?: AbortSignal; accept?: string } = {},
): Promise<T> {
  const headers = new Headers({
    accept: options.accept ?? 'application/json, application/problem+json',
    [HEADER_CORRELATION]: newCorrelationId(),
  });
  if (options.purpose) headers.set(HEADER_PURPOSE, options.purpose);
  const res = await fetch(url, { headers, signal: options.signal, credentials: 'same-origin' });
  let body: unknown;
  try {
    body = await res.json();
  } catch {
    body = undefined;
  }
  if (!res.ok) {
    let problem: Problem | undefined;
    if (body && typeof body === 'object') {
      const b = body as Record<string, unknown>;
      if (b.resourceType === 'OperationOutcome') {
        const issue = Array.isArray(b.issue) ? (b.issue[0] as Record<string, unknown>) : undefined;
        problem = {
          type: 'about:blank',
          status: res.status,
          title:
            typeof issue?.diagnostics === 'string' ? issue.diagnostics : `Erro HTTP ${res.status}`,
        };
      } else {
        problem = b;
      }
    }
    throw new CoreApiError(res.status, problem, res.headers.get(HEADER_CORRELATION) ?? undefined);
  }
  return body as T;
}
