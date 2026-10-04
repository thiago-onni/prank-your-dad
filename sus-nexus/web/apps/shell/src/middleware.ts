import { NextResponse, type NextRequest } from 'next/server';

/**
 * CSP estrita com nonce por requisição + redirecionamento para login quando não há
 * sessão (modo Keycloak). Os demais cabeçalhos de segurança estão em `next.config.ts`.
 */
const PUBLIC_PATHS = ['/entrar', '/api/auth', '/api/health', '/_next', '/favicon.ico'];

function buildCsp(nonce: string): string {
  const dev = process.env.NODE_ENV !== 'production';
  return [
    `default-src 'self'`,
    `script-src 'self' 'nonce-${nonce}' 'strict-dynamic'${dev ? " 'unsafe-eval'" : ''}`,
    // Radix/Next usam atributos style inline; folhas de estilo continuam restritas a 'self'.
    `style-src 'self' 'unsafe-inline'`,
    `img-src 'self' data: blob:`,
    `font-src 'self' data:`,
    `connect-src 'self'${dev ? ' ws: wss:' : ''}`,
    `frame-ancestors 'none'`,
    `form-action 'self'`,
    `base-uri 'self'`,
    `object-src 'none'`,
    `upgrade-insecure-requests`,
  ].join('; ');
}

function hasSessionCookie(req: NextRequest): boolean {
  return req.cookies.has('sus-nexus.session') || req.cookies.has('__Secure-sus-nexus.session');
}

export function middleware(req: NextRequest): NextResponse {
  const nonce = btoa(crypto.randomUUID());
  const csp = buildCsp(nonce);
  const requestHeaders = new Headers(req.headers);
  requestHeaders.set('x-nonce', nonce);
  requestHeaders.set('content-security-policy', csp);

  const { pathname } = req.nextUrl;
  const isPublic = PUBLIC_PATHS.some((p) => pathname === p || pathname.startsWith(`${p}/`));
  if (!isPublic && process.env.AUTH_MODE !== 'mock' && !hasSessionCookie(req)) {
    if (pathname.startsWith('/api/')) {
      return NextResponse.json(
        { type: 'about:blank', title: 'Não autenticado', status: 401 },
        { status: 401, headers: { 'content-type': 'application/problem+json' } },
      );
    }
    const login = new URL('/entrar', req.url);
    login.searchParams.set('callbackUrl', pathname);
    return NextResponse.redirect(login);
  }

  const res = NextResponse.next({ request: { headers: requestHeaders } });
  res.headers.set('Content-Security-Policy', csp);
  return res;
}

export const config = {
  matcher: [{ source: '/((?!_next/static|_next/image|favicon.ico|.*\\.(?:svg|png|ico|webp)$).*)' }],
};
