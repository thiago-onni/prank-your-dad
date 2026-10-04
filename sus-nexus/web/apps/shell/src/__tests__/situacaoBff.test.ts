// @vitest-environment node
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { HttpResponse, http } from 'msw';
import type {
  SituationCapacityResponse,
  SituationFiltersResponse,
  SituationIndicatorsResponse,
  SituationRankingResponse,
  SituationSeriesResponse,
  SituationTerritoriesResponse,
} from '@sus-nexus/api-client';
import { server } from '@/mocks/server';
import { trinoExecutions } from '@/mocks/trino-handlers';
import { LATEST_COMPETENCE, MAIN_TENANT, OTHER_TENANT } from '@/mocks/situacao-data';
import { computeInequality, handleSituationRequest } from '@/lib/situacao/handler';
import {
  assertSafeSql,
  buildQueries,
  defaultCompetence,
  parseSituationParams,
  SituationParamError,
  VIEW_PARAMS,
} from '@/lib/situacao/queries';
import { runPreparedQuery, sqlStringLiteral } from '@/lib/situacao/trino';
import { SITUATION_VIEWS } from '@sus-nexus/api-client';

const TRINO = { url: 'http://trino.test:8088', user: 'sus-nexus-web', catalog: 'iceberg' };
const ctx = (tenantId: string | undefined = MAIN_TENANT) => ({
  tenantId,
  trino: TRINO,
  correlationId: 'corr-1',
});
const req = (view: string, qs = '') =>
  handleSituationRequest(view, new URL(`http://shell.test/api/situacao/${view}${qs}`), ctx());

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => {
  trinoExecutions.length = 0;
});

describe('Sala de Situação — validação/whitelist de parâmetros', () => {
  const parse = (view: Parameters<typeof parseSituationParams>[0], qs: string) =>
    parseSituationParams(view, new URLSearchParams(qs));

  it.each([
    ['indicadores', "competence=202609' OR 1=1 --"],
    ['indicadores', 'competence=202613'],
    ['indicadores', "cnes=2126672'; DROP TABLE x; --"],
    ['indicadores', 'cnes=212667'],
    [
      'territorios',
      "care_line=diabetes' union select * from iceberg.marts_identified.rpt_care_gap_worklist_identified --",
    ],
    ['territorios', 'care_line=diabetes&team_ine=00010010 2'],
    ['serie', 'indicator=AGE_ABSENTEISMO%27%20OR%201%3D1'],
    ['serie', 'indicator=INDICADOR_INVENTADO'],
    ['ranking', 'indicator=age_absenteismo'],
  ] as const)('%s rejeita %s', (view, qs) => {
    expect(() => parse(view, qs)).toThrow(SituationParamError);
  });

  it('rejeita parâmetros fora da whitelist (inclusive município e SQL livre) e repetidos', () => {
    expect(() => parse('indicadores', 'tenant_id=ibge_3106200')).toThrow(/não permitido/);
    expect(() => parse('indicadores', 'municipality_id=ibge_3106200')).toThrow(/não permitido/);
    expect(() => parse('indicadores', 'sql=select+1')).toThrow(/não permitido/);
    expect(() => parse('indicadores', 'competence=202609&competence=202608')).toThrow(/repetido/);
    expect(() => parse('filtros', 'competence=202609')).toThrow(/não permitido/);
    expect(() => parse('serie', '')).toThrow(/obrigatório: indicator/);
    expect(() => parse('territorios', 'competence=202609')).toThrow(/obrigatório: care_line/);
  });

  it('aceita filtros válidos e usa o mês anterior como competência padrão', () => {
    expect(
      parse('territorios', 'competence=202609&care_line=diabetes&cnes=2126672&team_ine=0001001002'),
    ).toEqual({
      competence: '202609',
      care_line: 'diabetes',
      cnes: '2126672',
      team_ine: '0001001002',
    });
    expect(defaultCompetence(new Date('2026-10-04T12:00:00-03:00'))).toBe('202609');
    expect(defaultCompetence(new Date('2027-01-01T01:00:00-03:00'))).toBe('202612');
  });

  it('todas as consultas são fixas, só leem gold agregado/dimensões e filtram pelo município', () => {
    for (const view of SITUATION_VIEWS) {
      const qs = new URLSearchParams();
      if (VIEW_PARAMS[view].includes('indicator')) qs.set('indicator', 'AGE_ABSENTEISMO');
      if (VIEW_PARAMS[view].includes('care_line')) qs.set('care_line', 'diabetes');
      const queries = buildQueries(view, MAIN_TENANT, parseSituationParams(view, qs), 'iceberg');
      for (const q of Object.values(queries)) {
        expect(q.sql).not.toMatch(/marts_identified|bronze|intermediate|staging/);
        expect(q.sql).toMatch(/iceberg\.(marts_aggregated\.agg_|marts\.dim_)/);
        expect(q.params[0]).toBe(MAIN_TENANT);
        expect(q.sql.match(/\?/g)?.length).toBe(q.params.length);
      }
    }
    expect(() =>
      assertSafeSql('select * from iceberg.marts_identified.x where tenant_id = ?'),
    ).toThrow();
    expect(() => assertSafeSql('select 1 from t')).toThrow(/município/);
    expect(() =>
      buildQueries('filtros', "ibge_3143302' or '1'='1", { competence: '202609' }, 'iceberg'),
    ).toThrow();
    expect(() =>
      buildQueries('filtros', MAIN_TENANT, { competence: '202609' }, 'iceberg; drop'),
    ).toThrow();
  });

  it('literais SQL escapam aspas e recusam caracteres de controle', () => {
    expect(sqlStringLiteral("o'brien")).toBe("'o''brien'");
    expect(() => sqlStringLiteral('a\u0000b')).toThrow();
  });
});

describe('Sala de Situação — BFF → Trino (MSW)', () => {
  it('indicadores: município do token, prepared statement + EXECUTE USING e nextUri paginado', async () => {
    const seen: Request[] = [];
    server.events.on('request:start', ({ request }) => {
      if (request.url.includes('trino.test')) seen.push(request.clone());
    });
    const res = await req('indicadores', `?competence=${LATEST_COMPETENCE}`);
    server.events.removeAllListeners();
    expect(res.status).toBe(200);
    const body = (await res.json()) as SituationIndicatorsResponse;
    expect(body.items).toHaveLength(20);
    expect(body.scope).toEqual({ level: 'municipio', cnes: null });
    // POST inicial (QUEUED) + 2 páginas de dados (20 linhas, página de 10).
    expect(seen.map((r) => r.method)).toEqual(['POST', 'GET', 'GET']);
    const post = seen[0]!;
    expect(post.headers.get('x-trino-user')).toBe('sus-nexus-web');
    expect(post.headers.get('x-trino-catalog')).toBe('iceberg');
    expect(post.headers.get('x-trino-prepared-statement')).toMatch(/^sit_indicadores=select/);
    expect(await post.text()).toBe(
      `EXECUTE sit_indicadores USING 'ibge_3143302', '${LATEST_COMPETENCE}', 'municipio', '-'`,
    );
    const absent = body.items.find((i) => i.code === 'AGE_ABSENTEISMO')!;
    expect(absent.value).toBeCloseTo(0.21, 2);
    expect(absent.target).toBe(0.15);
    expect(absent.is_on_target).toBe(false);
  });

  it('força o município do token: outro tenant só via token, nunca via parâmetro', async () => {
    const forged = await req('indicadores', '?tenant_id=ibge_3106200');
    expect(forged.status).toBe(400);
    expect(trinoExecutions).toHaveLength(0);

    const other = await handleSituationRequest(
      'indicadores',
      new URL(`http://shell.test/api/situacao/indicadores?competence=${LATEST_COMPETENCE}`),
      ctx(OTHER_TENANT),
    );
    const body = (await other.json()) as SituationIndicatorsResponse;
    expect(trinoExecutions[0]!.params[0]).toBe(OTHER_TENANT);
    expect(body.items.every((i) => i.value === 0.5)).toBe(true);

    const noTenant = await handleSituationRequest(
      'filtros',
      new URL('http://shell.test/api/situacao/filtros'),
      { ...ctx(), tenantId: undefined },
    );
    expect(noTenant.status).toBe(403);
  });

  it('rejeita SQL injection e indicador desconhecido com 400, sem consultar o Trino', async () => {
    for (const qs of [
      "?indicator=AGE_ABSENTEISMO'--",
      '?indicator=DROP_TABLE',
      `?indicator=AGE_ABSENTEISMO&competence=${encodeURIComponent("202609') or ('1'='1")}`,
    ]) {
      const res = await req('serie', qs);
      expect(res.status).toBe(400);
      expect(res.headers.get('content-type')).toContain('problem+json');
    }
    expect((await req('livre')).status).toBe(404);
    expect(trinoExecutions).toHaveLength(0);
  });

  it('supressão: valor nulo + is_suppressed, nunca zero (indicador e agg_*)', async () => {
    const ind = (await (
      await req('indicadores', `?competence=${LATEST_COMPETENCE}`)
    ).json()) as SituationIndicatorsResponse;
    const agent = ind.items.find((i) => i.code === 'TAR_AGENTE_SLA_CUMPRIDO')!;
    expect(agent).toMatchObject({
      is_suppressed: true,
      value: null,
      numerator: null,
      denominator: null,
      is_on_target: null,
    });

    const cap = (await (
      await req('capacidade', `?competence=${LATEST_COMPETENCE}`)
    ).json()) as SituationCapacityResponse;
    const santaCasa = cap.hospitals.find((h) => h.hospital_cnes === '2149990')!;
    expect(santaCasa.n_deaths).toEqual({ value: null, suppressed: true });
    expect(santaCasa.n_discharges.suppressed).toBe(false);
    const endo = cap.queue.find((q) => q.specialty === 'endocrinologia')!;
    expect(endo.n_open).toEqual({ value: null, suppressed: true });
    expect(endo.days_waiting_p50).toEqual({ value: null, suppressed: true });
    expect(cap.queue.some((q) => q.specialty === 'outro_municipio')).toBe(false);
  });

  it('série, ranking com desigualdade (suprimidos fora) e territórios', async () => {
    const serie = (await (
      await req('serie', '?indicator=AGE_ABSENTEISMO')
    ).json()) as SituationSeriesResponse;
    expect(serie.points).toHaveLength(12);
    expect(serie.points.map((p) => p.competence)).toEqual(
      [...serie.points.map((p) => p.competence)].sort(),
    );
    expect(serie.indicator?.direction).toBe('menor_melhor');

    const ranking = (await (
      await req('ranking', `?indicator=AGE_ABSENTEISMO&competence=${LATEST_COMPETENCE}`)
    ).json()) as SituationRankingResponse;
    expect(ranking.items).toHaveLength(3);
    const prates = ranking.items.find((i) => i.health_unit_cnes === '2126710')!;
    expect(prates).toMatchObject({
      is_suppressed: true,
      value: null,
      health_unit_name: 'UBS Major Prates',
    });
    expect(ranking.inequality).toMatchObject({ compared: 2, suppressed: 1 });
    expect(ranking.inequality!.ratio).toBeCloseTo(
      ranking.inequality!.highest.value / ranking.inequality!.lowest.value,
    );

    const terr = (await (
      await req('territorios', `?care_line=diabetes&competence=${LATEST_COMPETENCE}&cnes=2126710`)
    ).json()) as SituationTerritoriesResponse;
    expect(terr.items).toHaveLength(2);
    const suppressedTeam = terr.items.find((i) => i.n_detected.suppressed)!;
    expect(suppressedTeam.resolution_rate).toEqual({ value: null, suppressed: true });
    expect(trinoExecutions.at(-1)!.params).toEqual([
      MAIN_TENANT,
      MAIN_TENANT,
      LATEST_COMPETENCE,
      'diabetes',
      '2126710',
      '2126710',
      '',
      '',
    ]);
  });

  it('filtros do município do token', async () => {
    const body = (await (await req('filtros')).json()) as SituationFiltersResponse;
    expect(body.competences[0]).toBe(LATEST_COMPETENCE);
    expect(body.units.every((u) => !u.cnes.startsWith('999'))).toBe(true);
    expect(body.territories.length).toBeGreaterThan(0);
    expect(body.care_lines).toContain('diabetes');
  });

  it('erro do Trino vira 502 problem+json sem vazar SQL', async () => {
    server.use(
      http.post('*/v1/statement', () =>
        HttpResponse.json({
          error: { message: 'line 1: select … boom', errorName: 'SYNTAX_ERROR' },
        }),
      ),
    );
    vi.spyOn(console, 'error').mockImplementation(() => {});
    const res = await req('filtros');
    expect(res.status).toBe(502);
    expect(await res.text()).not.toContain('select');
  });

  it('não segue nextUri para outro servidor (SSRF)', async () => {
    server.use(
      http.post('*/v1/statement', () =>
        HttpResponse.json({ id: 'x', nextUri: 'http://evil.test/v1/x' }),
      ),
    );
    await expect(
      runPreparedQuery(TRINO, {
        name: 'sit_x',
        sql: 'select 1 where tenant_id = ?',
        params: ['a'],
      }),
    ).rejects.toThrow(/fora do servidor/);
  });

  it('computeInequality ignora suprimidos e calcula razão maior ÷ menor', () => {
    const r = computeInequality([
      { key: 'a', label: 'A', value: 0.8, suppressed: false },
      { key: 'b', label: 'B', value: 0.2, suppressed: false },
      { key: 'c', label: 'C', value: null, suppressed: true },
    ]);
    expect(r).toMatchObject({
      highest: { key: 'a' },
      lowest: { key: 'b' },
      ratio: 4,
      compared: 2,
      suppressed: 1,
    });
    expect(r!.difference).toBeCloseTo(0.6);
    expect(
      computeInequality([
        { key: 'a', label: 'A', value: 0, suppressed: false },
        { key: 'b', label: 'B', value: 0.5, suppressed: false },
      ])!.ratio,
    ).toBeNull();
  });
});

describe('rota /api/situacao/[view] (withAuth)', () => {
  beforeEach(() => vi.stubEnv('NEXT_PUBLIC_API_MOCK', 'false'));
  afterEach(() => vi.unstubAllEnvs());

  it('usa o município da sessão e TRINO_* do ambiente', async () => {
    vi.stubEnv('TRINO_URL', 'http://trino.test:8088');
    vi.stubEnv('TRINO_USER', 'bi-web');
    const { GET } = await import('@/app/api/situacao/[view]/route');
    const res = await GET(
      new Request('http://localhost:3000/api/situacao/filtros', {
        headers: { 'x-correlation-id': "bad id'" },
      }),
      { params: Promise.resolve({ view: 'filtros' }) },
    );
    expect(res.status).toBe(200);
    expect(res.headers.get('x-correlation-id')).not.toBe("bad id'");
    expect(trinoExecutions.every((e) => e.user === 'bi-web' && e.params[0] === MAIN_TENANT)).toBe(
      true,
    );
  });
});
