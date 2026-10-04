'use client';

import { createContext, useContext, useMemo, type ReactNode } from 'react';
import { hasRole, type PublicSession } from './types';

export type { PublicSession, SessionUser } from './types';

interface SessionContextValue {
  session: PublicSession | null;
  status: 'authenticated' | 'unauthenticated';
}

const SessionContext = createContext<SessionContextValue | null>(null);

/** Provedor da sessão pública (sem tokens), hidratado pelo servidor. */
export function SessionProvider({
  session,
  children,
}: {
  session: PublicSession | null;
  children: ReactNode;
}) {
  const value = useMemo<SessionContextValue>(
    () => ({ session, status: session ? 'authenticated' : 'unauthenticated' }),
    [session],
  );
  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}

export function useSession(): SessionContextValue {
  const ctx = useContext(SessionContext);
  if (!ctx) throw new Error('useSession deve ser usado dentro de <SessionProvider>');
  return ctx;
}

/** `true` se a sessão tem algum dos papéis (admin sempre passa). Apenas usabilidade — autorização real é no backend. */
export function useHasRole(...roles: string[]): boolean {
  const { session } = useSession();
  return hasRole(session, ...roles);
}
