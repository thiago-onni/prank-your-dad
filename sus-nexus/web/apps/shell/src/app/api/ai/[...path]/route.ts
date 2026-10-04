import { withAuth } from '@sus-nexus/auth/server';
import { HEADER_CORRELATION, newCorrelationId } from '@sus-nexus/api-client';
import { env } from '@/lib/env';
import { ensureMockServer } from '@/mocks/enable';

/**
 * Proxy BFF para o ai-service: o navegador chama `/api/ai/<rota>`; o servidor anexa o Bearer da
 * sessão (o ai-service valida o JWT e aplica `admin_roles`/`approver_roles`) e o tenant.
 * A autorização real (papéis, tenant, OPA) é do ai-service; aqui só há lista de rotas permitidas.
 */
const ALLOWED = [/^agents(\/|$)/, /^tools$/, /^runs(\/|$)/, /^approvals$/, /^admin\/kill-switch$/];
const FORWARD_REQUEST_HEADERS = ['accept', 'content-type', HEADER_CORRELATION];
const FORWARD_RESPONSE_HEADERS = ['content-type', 'x-correlation-id'];

type Ctx = { params: Promise<{ path: string[] }> };

export const dynamic = 'force-dynamic';

export const proxy = withAuth<Ctx>(async (req, ctx, { accessToken, session }) => {
  const { path } = await ctx.params;
  const joined = path.join('/');
  if (joined.includes('..') || !ALLOWED.some((re) => re.test(joined))) {
    return Response.json(
      { type: 'about:blank', title: 'Rota não permitida', status: 404 },
      { status: 404, headers: { 'content-type': 'application/problem+json' } },
    );
  }
  const incoming = new URL(req.url);
  const target = new URL(`${env.aiServiceUrl.replace(/\/$/, '')}/${joined}${incoming.search}`);

  const headers = new Headers();
  for (const name of FORWARD_REQUEST_HEADERS) {
    const v = req.headers.get(name);
    if (v) headers.set(name, v);
  }
  if (!headers.has(HEADER_CORRELATION)) headers.set(HEADER_CORRELATION, newCorrelationId());
  headers.set('authorization', `Bearer ${accessToken}`);
  if (session.user.municipalityId) headers.set('x-tenant-id', session.user.municipalityId);

  const method = req.method.toUpperCase();
  const body = method === 'GET' || method === 'HEAD' ? undefined : await req.arrayBuffer();

  let upstream: Response;
  try {
    await ensureMockServer();
    upstream = await fetch(target, { method, headers, body, cache: 'no-store', redirect: 'manual' });
  } catch (error) {
    const correlationId = headers.get(HEADER_CORRELATION);
    console.error('[bff] ai-service indisponível', {
      correlationId,
      message: error instanceof Error ? error.message : 'erro',
    });
    return Response.json(
      {
        type: 'about:blank',
        title: 'Serviço de agentes indisponível',
        status: 502,
        correlation_id: correlationId,
      },
      {
        status: 502,
        headers: {
          'content-type': 'application/problem+json',
          [HEADER_CORRELATION]: correlationId ?? '',
        },
      },
    );
  }

  const responseHeaders = new Headers();
  for (const name of FORWARD_RESPONSE_HEADERS) {
    const v = upstream.headers.get(name);
    if (v) responseHeaders.set(name, v);
  }
  responseHeaders.set(HEADER_CORRELATION, headers.get(HEADER_CORRELATION) ?? '');
  responseHeaders.set('cache-control', 'no-store');
  return new Response(upstream.status === 204 ? null : upstream.body, {
    status: upstream.status,
    headers: responseHeaders,
  });
});

export { proxy as GET, proxy as POST };
