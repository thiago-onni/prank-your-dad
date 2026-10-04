import type { ReactNode } from 'react';
import { render, type RenderOptions } from '@testing-library/react';
import { QueryClient } from '@tanstack/react-query';
import { CoreApiProvider, type Purpose } from '@sus-nexus/api-client';
import { SessionProvider, type PublicSession } from '@sus-nexus/auth/client';
import { ToastProvider, TooltipProvider } from '@sus-nexus/design-system';

export const mockSession: PublicSession = {
  user: { id: 'user_mock', name: 'Maria Operadora', municipalityId: 'ibge_3143302' },
  roles: ['cadastrador', 'operador_integracao', 'enfermagem'],
  expires: new Date(Date.now() + 3600_000).toISOString(),
};

export function renderWithProviders(
  ui: ReactNode,
  options: { purpose?: Purpose | undefined; session?: PublicSession | null } & RenderOptions = {},
) {
  const { session = mockSession, ...rest } = options;
  const purpose = 'purpose' in options ? options.purpose : 'identity_management';
  delete (rest as { purpose?: unknown }).purpose;
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false } },
  });
  return render(
    <SessionProvider session={session}>
      <CoreApiProvider baseUrl="http://core.test" purpose={purpose} queryClient={queryClient}>
        <TooltipProvider>
          <ToastProvider>{ui}</ToastProvider>
        </TooltipProvider>
      </CoreApiProvider>
    </SessionProvider>,
    rest,
  );
}
