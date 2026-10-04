import 'server-only';
import type { NextAuthConfig, NextAuthResult } from 'next-auth';
import type { JWT } from 'next-auth/jwt';
import type { NextRequest } from 'next/server';
import type KeycloakProvider from 'next-auth/providers/keycloak';
import { readAuthEnv, type AuthEnv } from './config';
import { decodeJwtPayload, extractRoles } from './jwt';
import { toPublicSession, type PublicSession, type ServerSession } from './types';

declare module 'next-auth' {
  interface Session {
    roles: string[];
    accessToken?: string;
    error?: string;
    user: { id: string; name?: string | null; email?: string | null; municipalityId?: string };
  }
}

declare module 'next-auth/jwt' {
  interface JWT {
    accessToken?: string;
    refreshToken?: string;
    idToken?: string;
    expiresAt?: number;
    roles?: string[];
    municipalityId?: string;
    sub?: string;
    error?: string;
  }
}

export interface AuthHandlers {
  GET: (req: Request) => Promise<Response>;
  POST: (req: Request) => Promise<Response>;
}

export interface SusAuth {
  mode: AuthEnv['mode'];
  /** Sessão completa (servidor). Inclui `accessToken` — nunca envie ao navegador. */
  auth: () => Promise<ServerSession | null>;
  /** Sessão sanitizada para hidratar o `SessionProvider`. */
  publicSession: () => Promise<PublicSession | null>;
  handlers: AuthHandlers;
  signIn: (callbackUrl?: string) => Promise<void>;
  signOut: () => Promise<void>;
}

async function refreshAccessToken(token: JWT, env: AuthEnv): Promise<JWT> {
  if (!token.refreshToken) return { ...token, error: 'RefreshTokenError' };
  try {
    const res = await fetch(`${env.keycloak.issuer}/protocol/openid-connect/token`, {
      method: 'POST',
      headers: { 'content-type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({
        grant_type: 'refresh_token',
        client_id: env.keycloak.clientId,
        client_secret: env.keycloak.clientSecret,
        refresh_token: token.refreshToken,
      }),
    });
    const data = (await res.json()) as {
      access_token?: string;
      refresh_token?: string;
      id_token?: string;
      expires_in?: number;
      error?: string;
    };
    if (!res.ok || !data.access_token) throw new Error(data.error ?? 'refresh_failed');
    const payload = decodeJwtPayload(data.access_token);
    return {
      ...token,
      accessToken: data.access_token,
      refreshToken: data.refresh_token ?? token.refreshToken,
      idToken: data.id_token ?? token.idToken,
      expiresAt: Math.floor(Date.now() / 1000) + (data.expires_in ?? 300),
      roles: extractRoles(payload, env.keycloak.clientId),
      error: undefined,
    };
  } catch {
    return { ...token, error: 'RefreshTokenError' };
  }
}

function buildKeycloakConfig(env: AuthEnv, Keycloak: typeof KeycloakProvider): NextAuthConfig {
  const cookieBase = {
    httpOnly: true,
    sameSite: 'strict' as const,
    path: '/',
    secure: env.secureCookies,
  };
  const prefix = env.secureCookies ? '__Secure-' : '';
  return {
    secret: env.secret,
    trustHost: true,
    session: { strategy: 'jwt', maxAge: 8 * 60 * 60 },
    providers: [
      Keycloak({
        clientId: env.keycloak.clientId,
        clientSecret: env.keycloak.clientSecret,
        issuer: env.keycloak.issuer,
        authorization: { params: { scope: 'openid profile email offline_access' } },
      }),
    ],
    cookies: {
      sessionToken: { name: `${prefix}sus-nexus.session`, options: cookieBase },
      callbackUrl: {
        name: `${prefix}sus-nexus.callback-url`,
        options: { ...cookieBase, sameSite: 'lax' },
      },
      csrfToken: {
        name: `${env.secureCookies ? '__Host-' : ''}sus-nexus.csrf`,
        options: cookieBase,
      },
      pkceCodeVerifier: {
        name: `${prefix}sus-nexus.pkce`,
        options: { ...cookieBase, sameSite: 'lax', maxAge: 900 },
      },
      state: {
        name: `${prefix}sus-nexus.state`,
        options: { ...cookieBase, sameSite: 'lax', maxAge: 900 },
      },
      nonce: { name: `${prefix}sus-nexus.nonce`, options: { ...cookieBase, sameSite: 'lax' } },
    },
    callbacks: {
      async jwt({ token, account }) {
        if (account?.access_token) {
          const payload = decodeJwtPayload(account.access_token);
          return {
            ...token,
            accessToken: account.access_token,
            refreshToken: account.refresh_token,
            idToken: account.id_token,
            expiresAt: account.expires_at ?? Math.floor(Date.now() / 1000) + 300,
            roles: extractRoles(payload, env.keycloak.clientId),
            municipalityId:
              typeof payload.municipality_id === 'string' ? payload.municipality_id : undefined,
            sub: typeof payload.sub === 'string' ? payload.sub : token.sub,
          };
        }
        const skew = 30;
        if (token.expiresAt && Date.now() / 1000 < token.expiresAt - skew) return token;
        return refreshAccessToken(token, env);
      },
      session({ session, token }) {
        session.roles = token.roles ?? [];
        session.accessToken = token.accessToken;
        session.error = token.error;
        session.user = {
          ...session.user,
          id: token.sub ?? '',
          municipalityId: token.municipalityId,
        };
        return session;
      },
    },
    events: {
      async signOut(message) {
        // Encerra a sessão também no Keycloak (RP-initiated logout).
        const idToken = 'token' in message ? message.token?.idToken : undefined;
        if (!idToken) return;
        try {
          await fetch(
            `${env.keycloak.issuer}/protocol/openid-connect/logout?${new URLSearchParams({ id_token_hint: idToken })}`,
          );
        } catch {
          /* best effort */
        }
      },
    },
  };
}

/** Remove tokens de respostas de `/api/auth/session` — o navegador nunca recebe o Bearer. */
function sanitizeSessionResponse(handler: (req: Request) => Promise<Response>) {
  return async (req: Request): Promise<Response> => {
    const res = await handler(req);
    const url = new URL(req.url);
    if (
      !url.pathname.endsWith('/session') ||
      !res.headers.get('content-type')?.includes('application/json')
    ) {
      return res;
    }
    const body = (await res.json()) as Record<string, unknown> | null;
    if (body && typeof body === 'object') {
      delete body.accessToken;
      delete body.refreshToken;
      delete body.idToken;
    }
    return new Response(JSON.stringify(body), { status: res.status, headers: res.headers });
  };
}

/**
 * `next-auth` é carregado sob demanda: o modo mock e os testes unitários nunca o importam,
 * e o shell só paga o custo quando AUTH_MODE=keycloak.
 */
function createKeycloakAuth(env: AuthEnv): SusAuth {
  let instance: Promise<NextAuthResult> | undefined;
  const nextAuth = (): Promise<NextAuthResult> => {
    instance ??= (async () => {
      const [{ default: NextAuth }, { default: Keycloak }] = await Promise.all([
        import('next-auth'),
        import('next-auth/providers/keycloak'),
      ]);
      return NextAuth(buildKeycloakConfig(env, Keycloak));
    })();
    return instance;
  };
  const auth = async (): Promise<ServerSession | null> => {
    const session = await (await nextAuth()).auth();
    if (!session) return null;
    return {
      user: {
        id: session.user.id,
        name: session.user.name ?? session.user.email ?? 'Usuário',
        email: session.user.email ?? undefined,
        municipalityId: session.user.municipalityId,
      },
      roles: session.roles,
      expires: session.expires,
      accessToken: session.accessToken,
      error: session.error,
    };
  };
  return {
    mode: 'keycloak',
    auth,
    publicSession: async () => toPublicSession(await auth()),
    handlers: {
      // Os Route Handlers do Next entregam um NextRequest em tempo de execução.
      GET: sanitizeSessionResponse(async (req) =>
        (await nextAuth()).handlers.GET(req as NextRequest),
      ),
      POST: async (req) => (await nextAuth()).handlers.POST(req as NextRequest),
    },
    signIn: async (callbackUrl) => {
      await (await nextAuth()).signIn('keycloak', { redirectTo: callbackUrl ?? '/' });
    },
    signOut: async () => {
      await (await nextAuth()).signOut({ redirectTo: '/' });
    },
  };
}

export function createMockSession(env: AuthEnv): ServerSession {
  return {
    user: {
      id: 'user_mock',
      name: env.mock.user,
      email: env.mock.email,
      municipalityId: env.mock.municipalityId,
    },
    roles: env.mock.roles,
    expires: new Date(Date.now() + 8 * 3600 * 1000).toISOString(),
    accessToken: 'mock-access-token',
  };
}

function createMockAuth(env: AuthEnv): SusAuth {
  const session = () => Promise.resolve(createMockSession(env));
  const redirectHome = (req: Request) => {
    const url = new URL(req.url);
    const target = url.searchParams.get('callbackUrl') ?? '/';
    return Promise.resolve(Response.redirect(new URL(target, url.origin), 302));
  };
  const handlers: AuthHandlers = {
    GET: async (req) => {
      const url = new URL(req.url);
      if (url.pathname.endsWith('/session')) {
        return Response.json(toPublicSession(await session()));
      }
      return redirectHome(req);
    },
    POST: redirectHome,
  };
  return {
    mode: 'mock',
    auth: session,
    publicSession: async () => toPublicSession(await session()),
    handlers,
    signIn: async () => {},
    signOut: async () => {},
  };
}

let cached: SusAuth | undefined;

/** Fábrica do BFF de autenticação. Escolhe Keycloak ou mock conforme `AUTH_MODE`. */
export function createAuth(env: AuthEnv = readAuthEnv()): SusAuth {
  return env.mode === 'mock' ? createMockAuth(env) : createKeycloakAuth(env);
}

/** Instância singleton (lazy) configurada a partir de `process.env`. */
export function getAuth(): SusAuth {
  cached ??= createAuth();
  return cached;
}

export interface AuthedContext {
  session: ServerSession;
  accessToken: string;
}

export type AuthedHandler<Ctx = unknown> = (
  req: Request,
  ctx: Ctx,
  auth: AuthedContext,
) => Promise<Response> | Response;

function problem(status: number, title: string, detail?: string): Response {
  return new Response(JSON.stringify({ type: 'about:blank', title, status, detail }), {
    status,
    headers: { 'content-type': 'application/problem+json' },
  });
}

/**
 * Envolve um Route Handler exigindo sessão válida. Entrega o Bearer para chamadas
 * servidor→core. Opcionalmente exige papéis.
 */
export function withAuth<Ctx = unknown>(
  handler: AuthedHandler<Ctx>,
  options: { roles?: string[]; getAuth?: () => SusAuth } = {},
): (req: Request, ctx: Ctx) => Promise<Response> {
  return async (req, ctx) => {
    const session = await (options.getAuth ?? getAuth)().auth();
    if (!session || session.error === 'RefreshTokenError' || !session.accessToken) {
      return problem(401, 'Não autenticado', 'Sessão ausente ou expirada. Faça login novamente.');
    }
    if (options.roles && options.roles.length > 0) {
      const ok =
        session.roles.includes('admin') || options.roles.some((r) => session.roles.includes(r));
      if (!ok) return problem(403, 'Acesso negado', 'Seu papel não permite esta operação.');
    }
    return handler(req, ctx, { session, accessToken: session.accessToken });
  };
}

export { readAuthEnv, type AuthEnv } from './config';
export * from './types';
