/** Chaves de cache do TanStack Query para o ai-service. */
export const aiKeys = {
  all: ['ai'] as const,
  agents: () => [...aiKeys.all, 'agents'] as const,
  tools: () => [...aiKeys.all, 'tools'] as const,
  runs: (params: object) => [...aiKeys.all, 'runs', params] as const,
  run: (id: string) => [...aiKeys.all, 'run', id] as const,
  approvals: (params: object) => [...aiKeys.all, 'approvals', params] as const,
  killSwitch: () => [...aiKeys.all, 'kill-switch'] as const,
};
