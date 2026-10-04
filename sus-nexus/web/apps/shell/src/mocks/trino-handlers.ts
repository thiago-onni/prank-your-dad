import { HttpResponse, http } from 'msw';
import {
  aggCareGapsMonthly,
  aggHospitalMonthly,
  aggIndicadoresMensais,
  aggRegulationQueueCurrent,
  dimHealthUnit,
  dimTerritory,
  type Row,
} from './situacao-data';

/**
 * Trino sintético (MSW) para a Sala de Situação: implementa o protocolo HTTP do Trino
 * (`POST /v1/statement` → `nextUri` paginado) e "executa" os prepared statements fixos do BFF
 * (`X-Trino-Prepared-Statement` + `EXECUTE … USING`) sobre as tabelas de `situacao-data.ts`.
 * Como as regras do Trino (`rules.json`), nega `marts_identified` e exige usuário/catálogo.
 */

const PAGE_SIZE = 10;
const pending = new Map<string, { columns: { name: string; type: string }[]; data: unknown[][] }>();
let seq = 0;

/** Log das execuções (só para testes: inspeção de nome + parâmetros). */
export const trinoExecutions: { name: string; sql: string; params: string[]; user: string }[] = [];

function trinoError(message: string, errorName = 'GENERIC_USER_ERROR') {
  return HttpResponse.json({
    id: `err_${++seq}`,
    stats: { state: 'FAILED' },
    error: { message, errorName, errorType: 'USER_ERROR' },
  });
}

/** Literais `'...'` separados por vírgula (aspas duplicadas = aspa). */
export function parseUsing(list: string): string[] | null {
  const out: string[] = [];
  const re = /\s*'((?:[^']|'')*)'\s*(,|$)/y;
  let m: RegExpExecArray | null;
  let pos = 0;
  while (pos < list.length) {
    re.lastIndex = pos;
    m = re.exec(list);
    if (!m) return null;
    out.push(m[1]!.replace(/''/g, "'"));
    pos = re.lastIndex;
    if (m[2] === '') break;
  }
  return out;
}

const cols = (names: string[]) => names.map((name) => ({ name, type: 'varchar' }));
const project = (rows: Row[], names: string[]) => rows.map((r) => names.map((n) => r[n] ?? null));
const unitName = (tenant: string, cnes: unknown) =>
  dimHealthUnit.find((u) => u.tenant_id === tenant && u.health_unit_cnes === cnes);

const INDICATOR_COLS = [
  'indicator_code',
  'indicator_name',
  'competence',
  'aggregation_level',
  'health_unit_cnes',
  'numerator',
  'denominator',
  'indicator_value',
  'is_suppressed',
  'indicator_unit',
  'direction',
  'target_value',
  'is_on_target',
];

type Exec = (p: string[]) => { names: string[]; rows: Row[] } | null;

const STATEMENTS: Record<string, { arity: number; run: Exec }> = {
  sit_indicadores: {
    arity: 4,
    run: ([tenant, competence, level, cnes]) => ({
      names: INDICATOR_COLS,
      rows: aggIndicadoresMensais
        .filter(
          (r) =>
            r.tenant_id === tenant &&
            r.competence === competence &&
            r.aggregation_level === level &&
            (r.health_unit_cnes ?? '-') === cnes,
        )
        .sort((a, b) => String(a.indicator_code).localeCompare(String(b.indicator_code))),
    }),
  },
  sit_serie: {
    arity: 5,
    run: ([tenant, code, level, cnes, competence]) => ({
      names: INDICATOR_COLS,
      rows: aggIndicadoresMensais
        .filter(
          (r) =>
            r.tenant_id === tenant &&
            r.indicator_code === code &&
            r.aggregation_level === level &&
            (r.health_unit_cnes ?? '-') === cnes &&
            String(r.competence) <= competence!,
        )
        .sort((a, b) => String(b.competence).localeCompare(String(a.competence)))
        .slice(0, 12),
    }),
  },
  sit_ranking: {
    arity: 3,
    run: ([tenant, code, competence]) => {
      const names = [
        'indicator_code',
        'indicator_name',
        'indicator_unit',
        'direction',
        'target_value',
        'health_unit_cnes',
        'health_unit_name',
        'unit_role',
        'numerator',
        'denominator',
        'indicator_value',
        'is_suppressed',
        'is_on_target',
      ];
      const rows = aggIndicadoresMensais
        .filter(
          (r) =>
            r.tenant_id === tenant &&
            r.indicator_code === code &&
            r.competence === competence &&
            r.aggregation_level === 'unidade',
        )
        .map((r) => {
          const u = unitName(tenant!, r.health_unit_cnes);
          return {
            ...r,
            health_unit_name: u?.health_unit_name ?? null,
            unit_role: u?.unit_role ?? null,
          };
        });
      return { names, rows };
    },
  },
  sit_territorios: {
    arity: 8,
    run: ([tenant, tenant2, competence, careLine, cnes, , ine]) => {
      if (tenant !== tenant2) return null;
      const names = [
        'territory_key',
        'health_unit_cnes',
        'health_unit_name',
        'team_ine',
        'team_name',
        'care_line',
        'n_detected',
        'n_resolved',
        'n_open',
        'days_open_p50',
        'resolution_rate',
      ];
      const rows = aggCareGapsMonthly
        .filter(
          (r) =>
            r.tenant_id === tenant &&
            r.competence === competence &&
            r.care_line === careLine &&
            (cnes === '' || r.health_unit_cnes === cnes) &&
            (ine === '' || r.team_ine === ine),
        )
        .map((r) => ({
          ...r,
          health_unit_name: unitName(tenant!, r.health_unit_cnes)?.health_unit_name ?? null,
          team_name:
            dimTerritory.find((t) => t.tenant_id === tenant && t.team_ine === r.team_ine)
              ?.team_name ?? null,
        }));
      return { names, rows };
    },
  },
  sit_capacidade_hospital: {
    arity: 4,
    run: ([tenant, competence, cnes]) => ({
      names: [
        'hospital_cnes',
        'health_unit_name',
        'n_discharges',
        'n_deaths',
        'n_readmitted_30d',
        'los_avg_days',
        'readmission_30d_rate',
        'post_discharge_contact_7d_rate',
      ],
      rows: aggHospitalMonthly
        .filter(
          (r) =>
            r.tenant_id === tenant &&
            r.competence === competence &&
            r.episode_class === 'inpatient' &&
            (cnes === '' || r.hospital_cnes === cnes),
        )
        .map((r) => ({
          ...r,
          health_unit_name: unitName(tenant!, r.hospital_cnes)?.health_unit_name ?? null,
        })),
    }),
  },
  sit_capacidade_fila: {
    arity: 1,
    run: ([tenant]) => ({
      names: [
        'as_of',
        'specialty',
        'priority',
        'request_kind',
        'n_open',
        'n_overdue',
        'n_pending_documents',
        'days_waiting_p50',
        'days_waiting_p90',
      ],
      rows: aggRegulationQueueCurrent.filter((r) => r.tenant_id === tenant),
    }),
  },
  sit_filtros_competencias: {
    arity: 1,
    run: ([tenant]) => ({
      names: ['competence'],
      rows: [
        ...new Set(
          aggIndicadoresMensais
            .filter((r) => r.tenant_id === tenant && r.aggregation_level === 'municipio')
            .map((r) => String(r.competence)),
        ),
      ]
        .sort()
        .reverse()
        .slice(0, 24)
        .map((competence) => ({ competence })),
    }),
  },
  sit_filtros_unidades: {
    arity: 1,
    run: ([tenant]) => ({
      names: ['health_unit_cnes', 'health_unit_name', 'unit_role'],
      rows: dimHealthUnit
        .filter((r) => r.tenant_id === tenant && r.is_active)
        .sort((a, b) => String(a.health_unit_name).localeCompare(String(b.health_unit_name))),
    }),
  },
  sit_filtros_territorios: {
    arity: 1,
    run: ([tenant]) => ({
      names: ['health_unit_cnes', 'team_ine', 'team_name'],
      rows: dimTerritory.filter((r) => r.tenant_id === tenant && r.team_ine),
    }),
  },
  sit_filtros_linhas: {
    arity: 1,
    run: ([tenant]) => ({
      names: ['care_line'],
      rows: [
        ...new Set(
          aggCareGapsMonthly.filter((r) => r.tenant_id === tenant).map((r) => String(r.care_line)),
        ),
      ]
        .sort()
        .map((care_line) => ({ care_line })),
    }),
  },
};

export const trinoHandlers = [
  http.post('*/v1/statement', async ({ request }) => {
    const user = request.headers.get('X-Trino-User');
    if (!user || !request.headers.get('X-Trino-Catalog')) {
      return trinoError('Usuário e catálogo são obrigatórios', 'MISSING_USER_NAME');
    }
    const body = (await request.text()).trim();
    const m = /^EXECUTE ([a-z][a-z0-9_]*)(?: USING (.*))?$/s.exec(body);
    if (!m)
      return trinoError('Somente EXECUTE de prepared statement é aceito no mock', 'SYNTAX_ERROR');
    const name = m[1]!;
    const header = request.headers.get('X-Trino-Prepared-Statement') ?? '';
    const [hName, encoded] = header.split('=');
    if (hName !== name || !encoded)
      return trinoError(`Prepared statement ${name} ausente`, 'NOT_FOUND');
    const sql = decodeURIComponent(encoded);
    if (/marts_identified/i.test(sql)) {
      return trinoError(
        `Access Denied: Cannot select from schema marts_identified`,
        'PERMISSION_DENIED',
      );
    }
    const params = m[2] ? parseUsing(m[2]) : [];
    const stmt = STATEMENTS[name];
    if (!params || params.length !== stmt?.arity) {
      return trinoError(`Consulta desconhecida ou aridade inválida: ${name}`, 'SYNTAX_ERROR');
    }
    trinoExecutions.push({ name, sql, params, user });
    const result = stmt.run(params);
    if (!result) return trinoError('Parâmetros inconsistentes', 'INVALID_ARGUMENTS');
    const id = `q${++seq}`;
    pending.set(id, { columns: cols(result.names), data: project(result.rows, result.names) });
    const base = new URL(request.url);
    // Como o Trino real: a primeira resposta só traz `nextUri` (QUEUED).
    return HttpResponse.json({
      id,
      stats: { state: 'QUEUED' },
      nextUri: `${base.origin}/v1/statement/executing/${id}/0`,
    });
  }),

  http.get('*/v1/statement/executing/:id/:page', ({ params, request }) => {
    const id = String(params.id);
    const page = Number(params.page);
    const q = pending.get(id);
    if (!q) return HttpResponse.json({ error: { message: 'Query not found' } }, { status: 410 });
    const slice = q.data.slice(page * PAGE_SIZE, (page + 1) * PAGE_SIZE);
    const more = (page + 1) * PAGE_SIZE < q.data.length;
    if (!more) pending.delete(id);
    const origin = new URL(request.url).origin;
    return HttpResponse.json({
      id,
      columns: q.columns,
      data: slice,
      stats: { state: more ? 'RUNNING' : 'FINISHED' },
      ...(more ? { nextUri: `${origin}/v1/statement/executing/${id}/${page + 1}` } : {}),
    });
  }),

  http.delete('*/v1/statement/executing/:id/:page', ({ params }) => {
    pending.delete(String(params.id));
    return new HttpResponse(null, { status: 204 });
  }),
];
