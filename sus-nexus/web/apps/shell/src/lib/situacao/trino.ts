import 'server-only';

/**
 * Cliente mínimo da API HTTP do Trino (`POST /v1/statement` + `nextUri`), só para o BFF.
 *
 * - Usuário de serviço do grupo `bi` (`TRINO_USER`): lê `marts_aggregated`, `reference` e `marts.dim_*`
 *   (`platform/compose/trino/rules.json`). Esse grupo NÃO tem filtro de linha por município — o BFF aplica
 *   `tenant_id = ?` com o `municipality_id` do token em toda consulta.
 * - Somente consultas fixas: o SQL vem do catálogo `queries.ts` como *prepared statement*
 *   (`X-Trino-Prepared-Statement`) e os valores entram por `EXECUTE … USING` como literais já validados.
 */

export interface TrinoConfig {
  url: string;
  user: string;
  catalog: string;
  /** Senha opcional (HTTPS + autenticação por senha no Trino). Somente servidor. */
  password?: string;
  /** Limite de páginas seguidas por `nextUri` (proteção contra respostas sem fim). */
  maxPages?: number;
  timeoutMs?: number;
}

export interface TrinoColumn {
  name: string;
  type: string;
}

export type TrinoValue = string | number | boolean | null;
export type TrinoRow = Record<string, TrinoValue>;

interface TrinoResponse {
  id?: string;
  nextUri?: string;
  columns?: TrinoColumn[];
  data?: TrinoValue[][];
  stats?: { state?: string };
  error?: { message?: string; errorName?: string; errorType?: string };
}

export class TrinoError extends Error {
  readonly errorName: string | undefined;
  constructor(message: string, errorName?: string) {
    super(message);
    this.name = 'TrinoError';
    this.errorName = errorName;
  }
}

/** Literal SQL de string com escape de aspas. Os valores já passaram por regex estrita. */
export function sqlStringLiteral(value: string): string {
  // eslint-disable-next-line no-control-regex -- recusa caracteres de controle de propósito
  if (/[\u0000-\u001f]/.test(value)) throw new TrinoError('Parâmetro com caractere de controle');
  return `'${value.replace(/'/g, "''")}'`;
}

const STATEMENT_NAME_RE = /^[a-z][a-z0-9_]{0,63}$/;

export interface PreparedQuery {
  /** Nome do prepared statement (identificador SQL simples). */
  name: string;
  /** SQL fixo com `?` como marcadores. */
  sql: string;
  /** Valores (strings) na ordem dos marcadores. */
  params: string[];
}

function headersFor(config: TrinoConfig, extra?: Record<string, string>): Headers {
  const headers = new Headers({
    'X-Trino-User': config.user,
    'X-Trino-Catalog': config.catalog,
    'X-Trino-Source': 'sus-nexus-web-situacao',
    'X-Trino-Client-Tags': 'sala-situacao',
    ...extra,
  });
  if (config.password) {
    headers.set(
      'authorization',
      `Basic ${Buffer.from(`${config.user}:${config.password}`).toString('base64')}`,
    );
  }
  return headers;
}

/** Executa um prepared statement e segue `nextUri` até o fim, devolvendo linhas como objetos. */
export async function runPreparedQuery(
  config: TrinoConfig,
  query: PreparedQuery,
  init: { correlationId?: string } = {},
): Promise<TrinoRow[]> {
  if (!STATEMENT_NAME_RE.test(query.name)) throw new TrinoError('Nome de consulta inválido');
  const placeholders = (query.sql.match(/\?/g) ?? []).length;
  if (placeholders !== query.params.length) {
    throw new TrinoError('Número de parâmetros não corresponde à consulta');
  }
  const using = query.params.map(sqlStringLiteral).join(', ');
  const body = using ? `EXECUTE ${query.name} USING ${using}` : `EXECUTE ${query.name}`;
  const extra: Record<string, string> = {
    'X-Trino-Prepared-Statement': `${query.name}=${encodeURIComponent(query.sql)}`,
  };
  if (init.correlationId) extra['X-Trino-Trace-Token'] = init.correlationId;

  const maxPages = config.maxPages ?? 200;
  const deadline = Date.now() + (config.timeoutMs ?? 30_000);
  const base = config.url.replace(/\/$/, '');
  const origin = new URL(base).origin;

  let res = await fetch(`${base}/v1/statement`, {
    method: 'POST',
    headers: headersFor(config, extra),
    body,
    cache: 'no-store',
  });
  let columns: TrinoColumn[] | undefined;
  const rows: TrinoValue[][] = [];
  for (let page = 0; ; page++) {
    if (!res.ok && res.status !== 503) {
      throw new TrinoError(`Trino respondeu HTTP ${res.status}`);
    }
    const payload = (res.status === 503 ? {} : await res.json()) as TrinoResponse;
    if (payload.error) {
      throw new TrinoError(payload.error.message ?? 'Erro do Trino', payload.error.errorName);
    }
    if (payload.columns && !columns) columns = payload.columns;
    if (payload.data) rows.push(...payload.data);
    const next = payload.nextUri;
    if (!next) break;
    // Só segue nextUri do mesmo servidor configurado (evita SSRF por resposta adulterada).
    if (new URL(next).origin !== origin) throw new TrinoError('nextUri fora do servidor Trino');
    if (page >= maxPages || Date.now() > deadline) {
      void fetch(next, { method: 'DELETE', headers: headersFor(config) }).catch(() => {});
      throw new TrinoError('Consulta excedeu o limite de páginas/tempo');
    }
    res = await fetch(next, { headers: headersFor(config), cache: 'no-store' });
  }
  const cols = columns ?? [];
  return rows.map((r) => Object.fromEntries(cols.map((c, i) => [c.name, r[i] ?? null])));
}
