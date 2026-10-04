/** Chaves de cache do TanStack Query, centralizadas para invalidação consistente. */
export const coreKeys = {
  all: ['core'] as const,
  citizens: () => [...coreKeys.all, 'citizens'] as const,
  citizenSearch: (params: object) => [...coreKeys.citizens(), 'search', params] as const,
  citizen: (id: string) => [...coreKeys.citizens(), id] as const,
  citizenSummary: (id: string) => [...coreKeys.citizen(id), 'summary'] as const,
  timeline: (id: string, params: object) => [...coreKeys.citizen(id), 'timeline', params] as const,
  mergeCases: (params: object) => [...coreKeys.all, 'mpi', 'cases', params] as const,
  mergeCase: (id: string) => [...coreKeys.all, 'mpi', 'case', id] as const,
  tasks: (params: object) => [...coreKeys.all, 'tasks', params] as const,
  task: (id: string) => [...coreKeys.all, 'task', id] as const,
  appointments: (params: object) => [...coreKeys.all, 'appointments', params] as const,
  connectors: () => [...coreKeys.all, 'integration', 'connectors'] as const,
  integrationMessages: (params: object) =>
    [...coreKeys.all, 'integration', 'messages', params] as const,
  integrationMessage: (id: string) => [...coreKeys.all, 'integration', 'message', id] as const,
  deadLetters: (params: object) => [...coreKeys.all, 'integration', 'dlq', params] as const,
  reconciliation: (params: object) =>
    [...coreKeys.all, 'integration', 'reconciliation', params] as const,
  healthUnits: (params: object) => [...coreKeys.all, 'reference', 'health-units', params] as const,
  codes: (system: string, params: object) =>
    [...coreKeys.all, 'terminology', system, params] as const,
  accessLog: (params: object) => [...coreKeys.all, 'audit', 'access', params] as const,
  ruleSets: () => [...coreKeys.all, 'admin', 'rules'] as const,
  regulation: () => [...coreKeys.all, 'regulation'] as const,
  regulationRequests: (params: object) =>
    [...coreKeys.regulation(), 'requests', params] as const,
  regulationRequest: (id: string) => [...coreKeys.regulation(), 'request', id] as const,
  regulationCapacity: (params: object) => [...coreKeys.regulation(), 'capacity', params] as const,
  regulationQueueSummary: (params: object) =>
    [...coreKeys.regulation(), 'queues', 'summary', params] as const,
  exams: () => [...coreKeys.all, 'exams'] as const,
  examOrders: (params: object) => [...coreKeys.exams(), 'orders', params] as const,
  examOrder: (id: string) => [...coreKeys.exams(), 'order', id] as const,
};
