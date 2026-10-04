import { setupWorker } from 'msw/browser';
import { handlers } from './handlers';

/**
 * Worker MSW para o navegador (opcional). O modo padrão de mock intercepta no servidor
 * (BFF → core), mantendo o fluxo idêntico ao de produção. Para usar no navegador execute
 * `npx msw init public/` e inicie `worker.start()` em um componente cliente.
 */
export const worker = setupWorker(...handlers);
