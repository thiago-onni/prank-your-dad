'use client';

import type { ReactNode } from 'react';
import { CoreApiProvider, type Purpose } from '@sus-nexus/api-client';
import { SessionProvider, type PublicSession } from '@sus-nexus/auth/client';
import { ToastProvider, TooltipProvider } from '@sus-nexus/design-system';
import { ThemeProvider } from './ThemeProvider';

export interface AppProvidersProps {
  session: PublicSession | null;
  purpose: Purpose | undefined;
  nonce?: string;
  children: ReactNode;
}

export function AppProviders({ session, purpose, nonce, children }: AppProvidersProps) {
  return (
    <SessionProvider session={session}>
      <ThemeProvider nonce={nonce}>
        <CoreApiProvider purpose={purpose}>
          <TooltipProvider>
            <ToastProvider>{children}</ToastProvider>
          </TooltipProvider>
        </CoreApiProvider>
      </ThemeProvider>
    </SessionProvider>
  );
}
