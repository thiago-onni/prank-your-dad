import { setupServer } from 'msw/node';
import { handlers } from './handlers';

/** Servidor MSW (Node) — usado pelo `instrumentation.ts` em modo mock e pelos testes. */
export const server = setupServer(...handlers);
