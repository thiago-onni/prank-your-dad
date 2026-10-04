import { getAuth } from '@sus-nexus/auth/server';

/** Rotas do Auth.js (signin/callback/signout/session). Resolvidas por requisição, não no build. */
export const dynamic = 'force-dynamic';

export const GET = (req: Request): Promise<Response> => getAuth().handlers.GET(req);
export const POST = (req: Request): Promise<Response> => getAuth().handlers.POST(req);
