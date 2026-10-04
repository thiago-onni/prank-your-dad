import { withAuth } from '@sus-nexus/auth/server';
import { HEADER_CORRELATION, HEADER_PURPOSE, newCorrelationId } from '@sus-nexus/api-client';
import { cookies } from 'next/headers';
import { env } from '@/lib/env';
import { parsePurposeCookie, PURPOSE_COOKIE } from '@/lib/purpose';
import { ensureMockServer } from '@/mocks/enable';

/**
 * Proxy BFF: o navegador chama `/api/core/api/v1/...`; o servidor anexa o Bearer da sessão
 * e repassa ao core municipal. O token nunca chega ao navegador.
 */
const FORWARD_REQUEST_HEADERS = [
  'accept',
  'content-type',
  'if-match',
  'if-none-match',
  'idempotency-key',
  HEADER_PURPOSE,
  HEADER_CORRELATION,
];
const FORWARD_RESPONSE_HEADERS = [
  'content-type',
  'etag',
  'location',
  'x-correlation-id',
  'cache-control',
];
const ALLOWED_PREFIX = 'api/v1/';

type Ctx = { params: Promise<{ path: string[] }> };

export const dynamic = 'force-dynamic';

export const proxy = withAuth<Ctx>(async (req, ctx, { accessToken, session }) => {
  const { path } = await ctx.params;
  const joined = path.join('/');
  if (!joined.startsWith(ALLOWED_PREFIX) || joined.includes('..')) {
    return Response.json(
      { type: 'about:blank', title: 'Rota não permitida', status: 404 },
      { status: 404 },
    );
  }
  const incoming = new URL(req.url);
  const target = new URL(`${env.coreApiUrl.replace(/\/$/, '')}/${joined}${incoming.search}`);

  const headers = new Headers();
  for (const name of FORWARD_REQUEST_HEADERS) {
    const v = req.headers.get(name);
    if (v) headers.set(name, v);
  }
  if (!headers.has(HEADER_PURPOSE)) {
    const cookieStore = await cookies();
    const purpose = parsePurposeCookie(cookieStore.get(PURPOSE_COOKIE)?.value);
    if (purpose) headers.set(HEADER_PURPOSE, purpose);
  }
  if (!headers.has(HEADER_CORRELATION)) headers.set(HEADER_CORRELATION, newCorrelationId());
  headers.set('authorization', `Bearer ${accessToken}`);
  if (session.user.municipalityId) headers.set('x-tenant-id', session.user.municipalityId);

  const method = req.method.toUpperCase();
  const hasBody = method !== 'GET' && method !== 'HEAD';
  const body = hasBody ? await req.arrayBuffer() : undefined;

  let upstream: Response;
  try {
    await ensureMockServer();
    upstream = await fetch(target, {
      method,
      headers,
      body,
      cache: 'no-store',
      redirect: 'manual',
    });
  } catch (error) {
    const correlationId = headers.get(HEADER_CORRELATION);
    console.error('[bff] core indisponível', {
      correlationId,
      message: error instanceof Error ? error.message : 'erro',
    });
    return Response.json(
      {
        type: 'about:blank',
        title: 'Core indisponível',
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

export { proxy as GET, proxy as POST, proxy as PUT, proxy as PATCH, proxy as DELETE };
