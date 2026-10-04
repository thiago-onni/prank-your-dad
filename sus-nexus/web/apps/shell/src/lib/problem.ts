import { CoreApiError } from '@sus-nexus/api-client';

/** Mensagem amigável + código de suporte a partir de um erro. */
export function describeError(error: unknown): { message: string; correlationId?: string } {
  if (error instanceof CoreApiError) {
    const detail = error.problem?.detail;
    return {
      message: detail ? `${error.message}: ${detail}` : error.message,
      correlationId: error.correlationId,
    };
  }
  if (error instanceof Error) return { message: error.message };
  return { message: 'Erro desconhecido' };
}
