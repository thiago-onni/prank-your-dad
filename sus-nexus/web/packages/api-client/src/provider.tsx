'use client';

import { createContext, useContext, useMemo, useState, type ReactNode } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createCoreClient, type CoreClient } from './client';
import { createAiClient, type AiClient } from './ai/client';
import type { Purpose } from './types';

interface CoreApiContextValue {
  client: CoreClient;
  ai: AiClient;
  purpose: Purpose | undefined;
}

const CoreApiContext = createContext<CoreApiContextValue | null>(null);

export interface CoreApiProviderProps {
  children: ReactNode;
  /** Base da API (padrão: `/api/core`, o proxy BFF). */
  baseUrl?: string;
  /** Base do ai-service (padrão: `/api/ai`, o proxy BFF). */
  aiBaseUrl?: string;
  purpose: Purpose | undefined;
  queryClient?: QueryClient;
  client?: CoreClient;
  aiClient?: AiClient;
}

export function createDefaultQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: { staleTime: 30_000, retry: 1, refetchOnWindowFocus: false },
    },
  });
}

/** Provedor do cliente de API + TanStack Query. */
export function CoreApiProvider({
  children,
  baseUrl = '/api/core',
  aiBaseUrl = '/api/ai',
  purpose,
  queryClient,
  client,
  aiClient,
}: CoreApiProviderProps) {
  const [qc] = useState(() => queryClient ?? createDefaultQueryClient());
  const value = useMemo<CoreApiContextValue>(() => {
    const c = client ?? createCoreClient({ baseUrl, getPurpose: () => purpose });
    const ai = aiClient ?? createAiClient({ baseUrl: aiBaseUrl });
    return { client: c, ai, purpose };
  }, [client, aiClient, baseUrl, aiBaseUrl, purpose]);

  return (
    <CoreApiContext.Provider value={value}>
      <QueryClientProvider client={qc}>{children}</QueryClientProvider>
    </CoreApiContext.Provider>
  );
}

export function useCoreClient(): CoreClient {
  const ctx = useContext(CoreApiContext);
  if (!ctx) throw new Error('useCoreClient deve ser usado dentro de <CoreApiProvider>');
  return ctx.client;
}

export function useAiClient(): AiClient {
  const ctx = useContext(CoreApiContext);
  if (!ctx) throw new Error('useAiClient deve ser usado dentro de <CoreApiProvider>');
  return ctx.ai;
}

export function useCurrentPurpose(): Purpose | undefined {
  const ctx = useContext(CoreApiContext);
  if (!ctx) throw new Error('useCurrentPurpose deve ser usado dentro de <CoreApiProvider>');
  return ctx.purpose;
}
