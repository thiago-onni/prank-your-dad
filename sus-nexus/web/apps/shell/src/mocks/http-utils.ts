import { HttpResponse } from 'msw';
import type { Purpose } from '@sus-nexus/api-client';

/** Utilitários compartilhados pelos handlers MSW. */

const PAGE_DEFAULT = 50;

export function problem(
  status: number,
  title: string,
  detail?: string,
  errors?: { field: string; message: string }[],
) {
  return HttpResponse.json(
    {
      type: 'about:blank',
      title,
      status,
      detail,
      correlation_id: `corr-mock-${Date.now()}`,
      errors,
    },
    { status, headers: { 'content-type': 'application/problem+json' } },
  );
}

export function requirePurpose(request: Request): Purpose | Response {
  const purpose = request.headers.get('X-Purpose-Of-Use');
  if (!purpose)
    return problem(400, 'Finalidade ausente', 'Cabeçalho X-Purpose-Of-Use é obrigatório.');
  return purpose as Purpose;
}

/** Paginação por cursor opaco (índice codificado em base64). */
export function paginate<T>(items: T[], url: URL): { items: T[]; next_cursor: string | null } {
  const limit = Math.min(Number(url.searchParams.get('limit') ?? PAGE_DEFAULT), 200);
  const cursor = url.searchParams.get('cursor');
  const start = cursor ? Number(atob(cursor)) || 0 : 0;
  const page = items.slice(start, start + limit);
  const next = start + limit < items.length ? btoa(String(start + limit)) : null;
  return { items: page, next_cursor: next };
}

export function normalize(s: string): string {
  return s.normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase();
}

export async function readJson<T>(request: Request): Promise<T | undefined> {
  try {
    return (await request.json()) as T;
  } catch {
    return undefined;
  }
}

export const delay = () =>
  new Promise((r) => setTimeout(r, process.env.NODE_ENV === 'test' ? 0 : 150));
