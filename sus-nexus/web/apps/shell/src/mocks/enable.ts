/**
 * Em modo mock (`NEXT_PUBLIC_API_MOCK=true`) sobe o MSW no processo Node do Next.js uma única vez.
 * As chamadas servidor→core feitas pelo proxy BFF são interceptadas e respondidas com dados
 * sintéticos, sem backend. Só é importado por Route Handlers (runtime nodejs); nunca em produção.
 */
const globalRef = globalThis as typeof globalThis & { __susNexusMockServer?: Promise<void> };

export function ensureMockServer(): Promise<void> {
  if (process.env.NEXT_PUBLIC_API_MOCK !== 'true') return Promise.resolve();
  if (
    process.env.NODE_ENV === 'production' &&
    process.env.ALLOW_API_MOCK_IN_PRODUCTION !== 'true'
  ) {
    return Promise.resolve();
  }
  globalRef.__susNexusMockServer ??= import('./server').then(({ server }) => {
    server.listen({ onUnhandledRequest: 'bypass' });
    console.info('[sus-nexus] MSW ativo: respostas sintéticas para o core municipal.');
  });
  return globalRef.__susNexusMockServer;
}
