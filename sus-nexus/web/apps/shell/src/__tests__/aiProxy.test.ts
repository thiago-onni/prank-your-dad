// @vitest-environment node
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

describe('proxy BFF /api/ai', () => {
  const fetchSpy = vi.fn<typeof fetch>();
  beforeEach(() => {
    vi.stubEnv('NEXT_PUBLIC_API_MOCK', 'false');
    fetchSpy.mockReset();
    vi.stubGlobal('fetch', fetchSpy);
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.unstubAllEnvs();
  });

  it('encaminha ao AI_SERVICE_URL com Bearer e tenant; nunca expõe o token', async () => {
    fetchSpy.mockResolvedValue(
      new Response('[]', { status: 200, headers: { 'content-type': 'application/json' } }),
    );
    const { GET } = await import('@/app/api/ai/[...path]/route');
    const req = new Request('http://localhost:3000/api/ai/runs?status=running&limit=10', {
      headers: { 'x-correlation-id': 'corr-ai-1' },
    });
    const res = await GET(req, { params: Promise.resolve({ path: ['runs'] }) });
    expect(res.status).toBe(200);
    expect(res.headers.get('x-correlation-id')).toBe('corr-ai-1');
    expect(res.headers.get('cache-control')).toBe('no-store');
    const [url, init] = fetchSpy.mock.calls[0]!;
    expect((url as URL).href).toBe('http://ai.test/runs?status=running&limit=10');
    const headers = new Headers(init?.headers);
    expect(headers.get('authorization')).toBe('Bearer mock-access-token');
    expect(headers.get('x-tenant-id')).toBe('ibge_3143302');
    expect(await res.text()).not.toContain('mock-access-token');
  });

  it('repassa o corpo das decisões de aprovação', async () => {
    fetchSpy.mockResolvedValue(
      new Response('{}', { status: 200, headers: { 'content-type': 'application/json' } }),
    );
    const { POST } = await import('@/app/api/ai/[...path]/route');
    const body = JSON.stringify({ justification: 'Conferido pelo regulador responsável.' });
    const res = await POST(
      new Request('http://localhost:3000/api/ai/runs/run_1/actions/act_1/approve', {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body,
      }),
      { params: Promise.resolve({ path: ['runs', 'run_1', 'actions', 'act_1', 'approve'] }) },
    );
    expect(res.status).toBe(200);
    const [url, init] = fetchSpy.mock.calls[0]!;
    expect((url as URL).href).toBe('http://ai.test/runs/run_1/actions/act_1/approve');
    expect(init?.method).toBe('POST');
    expect(Buffer.from(init?.body as ArrayBuffer).toString()).toBe(body);
  });

  it('encaminha o agente de BI (bi_situation_analyst) sem tenant no corpo; o tenant vai no cabeçalho', async () => {
    fetchSpy.mockResolvedValue(
      new Response('{}', { status: 200, headers: { 'content-type': 'application/json' } }),
    );
    const { POST } = await import('@/app/api/ai/[...path]/route');
    const body = JSON.stringify({ competence: '202609', trend_months: 6 });
    const res = await POST(
      new Request('http://localhost:3000/api/ai/agents/bi_situation_analyst/run', {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body,
      }),
      { params: Promise.resolve({ path: ['agents', 'bi_situation_analyst', 'run'] }) },
    );
    expect(res.status).toBe(200);
    const [url, init] = fetchSpy.mock.calls[0]!;
    expect((url as URL).href).toBe('http://ai.test/agents/bi_situation_analyst/run');
    expect(new Headers(init?.headers).get('x-tenant-id')).toBe('ibge_3143302');
    expect(Buffer.from(init?.body as ArrayBuffer).toString()).toBe(body);
  });

  it('rejeita rotas fora da lista permitida (ex.: /health, /metrics, /admin/outra)', async () => {
    const { GET } = await import('@/app/api/ai/[...path]/route');
    for (const path of [['health'], ['metrics'], ['admin', 'outra'], ['..', 'agents']]) {
      const res = await GET(new Request(`http://localhost:3000/api/ai/${path.join('/')}`), {
        params: Promise.resolve({ path }),
      });
      expect(res.status).toBe(404);
    }
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it('responde 502 problem+json quando o ai-service está fora', async () => {
    fetchSpy.mockRejectedValue(new Error('ECONNREFUSED'));
    vi.spyOn(console, 'error').mockImplementation(() => {});
    const { GET } = await import('@/app/api/ai/[...path]/route');
    const res = await GET(new Request('http://localhost:3000/api/ai/agents'), {
      params: Promise.resolve({ path: ['agents'] }),
    });
    expect(res.status).toBe(502);
    expect(res.headers.get('content-type')).toContain('problem+json');
    const body = (await res.json()) as { title: string; correlation_id: string };
    expect(body.title).toBe('Serviço de agentes indisponível');
    expect(body.correlation_id).toBeTruthy();
  });
});
