export * from './types';
export {
  createCoreClient,
  createHeadersMiddleware,
  unwrap,
  newCorrelationId,
  CoreApiError,
  HEADER_PURPOSE,
  HEADER_CORRELATION,
  type CoreClient,
  type CoreClientOptions,
} from './client';
export { coreKeys } from './keys';
export {
  CoreApiProvider,
  createDefaultQueryClient,
  useCoreClient,
  useAiClient,
  useCurrentPurpose,
  type CoreApiProviderProps,
} from './provider';
export * from './hooks/citizens';
export * from './hooks/timeline';
export * from './hooks/mpi';
export * from './hooks/tasks';
export * from './hooks/integration';
export * from './hooks/reference';
export * from './hooks/appointments';
export * from './hooks/regulation';
export * from './hooks/exams';
export * from './ai/types';
export { createAiClient, AiContractError, type AiClient, type AiClientOptions } from './ai/client';
export { aiKeys } from './ai/keys';
export * from './ai/hooks';
