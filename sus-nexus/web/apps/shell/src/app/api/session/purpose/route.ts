import { cookies } from 'next/headers';
import { isPurpose } from '@sus-nexus/api-client';
import { withAuth } from '@sus-nexus/auth/server';
import { PURPOSE_COOKIE, purposesForRoles } from '@/lib/purpose';

/** Persiste a finalidade de acesso (X-Purpose-Of-Use) em cookie httpOnly da sessão. */
export const POST = withAuth(async (req, _ctx, { session }) => {
  let purpose: unknown;
  try {
    purpose = ((await req.json()) as { purpose?: unknown }).purpose;
  } catch {
    purpose = undefined;
  }
  if (!isPurpose(purpose)) {
    return Response.json(
      { type: 'about:blank', title: 'Finalidade inválida', status: 422 },
      { status: 422 },
    );
  }
  if (!purposesForRoles(session.roles).includes(purpose)) {
    return Response.json(
      { type: 'about:blank', title: 'Finalidade não permitida para seus papéis', status: 403 },
      { status: 403 },
    );
  }
  const store = await cookies();
  store.set(PURPOSE_COOKIE, purpose, {
    httpOnly: true,
    sameSite: 'strict',
    secure: process.env.NODE_ENV === 'production',
    path: '/',
    maxAge: 8 * 60 * 60,
  });
  return Response.json({ purpose });
});

export const DELETE = withAuth(async () => {
  const store = await cookies();
  store.delete(PURPOSE_COOKIE);
  return new Response(null, { status: 204 });
});
