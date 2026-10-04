// @vitest-environment node
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const { cookieStore } = vi.hoisted(() => ({ cookieStore: new Map<string, string>() }));
vi.mock('next/headers', () => ({
  cookies: () =>
    Promise.resolve({
      get: (name: string) =>
        cookieStore.has(name) ? { name, value: cookieStore.get(name) } : undefined,
    }),
}));

describe('proxy BFF /api/core', () => {
  const fetchSpy = vi.fn<typeof fetch>();
  beforeEach(() => {
    // Sem MSW aqui: o teste observa a chamada real ao core via spy de fetch.
    vi.stubEnv('NEXT_PUBLIC_API_MOCK', 'false');
    cookieStore.clear();
    fetchSpy.mockReset();
    vi.stubGlobal('fetch', fetchSpy);
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.unstubAllEnvs();
  });

  it('anexa Bearer, tenant e finalidade do cookie; nunca expõe o token', async () => {
    cookieStore.set('sus-nexus.purpose', 'identity_management');
    fetchSpy.mockResolvedValue(
      new Response(JSON.stringify({ items: [] }), {
        status: 200,
        headers: { 'content-type': 'application/json', etag: '"1"' },
      }),
    );
    const { GET } = await import('@/app/api/core/[...path]/route');
    const req = new Request('http://localhost:3000/api/core/api/v1/citizens?q=maria', {
      headers: { 'x-correlation-id': 'corr-1' },
    });
    const res = await GET(req, { params: Promise.resolve({ path: ['api', 'v1', 'citizens'] }) });
    expect(res.status).toBe(200);
    expect(res.headers.get('etag')).toBe('"1"');
    expect(res.headers.get('x-correlation-id')).toBe('corr-1');
    const [url, init] = fetchSpy.mock.calls[0]!;
    expect((url as URL).href).toBe('http://core.test/api/v1/citizens?q=maria');
    const headers = new Headers(init?.headers);
    // AUTH_MODE=mock (vitest.config): sessão fake do pacote de auth.
    expect(headers.get('authorization')).toBe('Bearer mock-access-token');
    expect(headers.get('x-purpose-of-use')).toBe('identity_management');
    expect(headers.get('x-tenant-id')).toBe('ibge_3143302');
    expect(await res.text()).not.toContain('mock-access-token');
  });

  it('rejeita caminhos fora de /api/v1', async () => {
    const { GET } = await import('@/app/api/core/[...path]/route');
    const res = await GET(new Request('http://localhost:3000/api/core/admin'), {
      params: Promise.resolve({ path: ['admin'] }),
    });
    expect(res.status).toBe(404);
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it('responde 502 problem+json quando o core está fora', async () => {
    fetchSpy.mockRejectedValue(new Error('ECONNREFUSED'));
    vi.spyOn(console, 'error').mockImplementation(() => {});
    const { POST } = await import('@/app/api/core/[...path]/route');
    const res = await POST(
      new Request('http://localhost:3000/api/core/api/v1/tasks', { method: 'POST', body: '{}' }),
      {
        params: Promise.resolve({ path: ['api', 'v1', 'tasks'] }),
      },
    );
    expect(res.status).toBe(502);
    expect(res.headers.get('content-type')).toContain('problem+json');
  });
});
