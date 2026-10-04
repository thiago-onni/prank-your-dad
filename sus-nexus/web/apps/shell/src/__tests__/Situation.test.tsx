import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { HttpResponse, http } from 'msw';
import { axe } from 'vitest-axe';
import type { PublicSession } from '@sus-nexus/auth/client';
import { server } from '@/mocks/server';
import { trinoExecutions } from '@/mocks/trino-handlers';
import { handleSituationRequest } from '@/lib/situacao/handler';
import { SituationPage } from '@/features/situacao/SituationPage';
import { BiAnalysisPanel } from '@/features/situacao/BiAnalysisPanel';
import { biRun } from '@/mocks/bi-agent-handlers';
import { formatSuppressible, trafficLight } from '@/features/situacao/format';
import { visibleNavItems } from '@/components/layout/nav';
import { mockSession, renderWithProviders } from './test-utils';

vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), refresh: vi.fn() }),
  usePathname: () => '/situacao',
  useSearchParams: () => new URLSearchParams(),
}));

/** O navegador chama o BFF; aqui o BFF real (handler) roda contra o Trino do MSW. */
const bffRequests: URL[] = [];
const bff = http.get('http://shell.test/api/situacao/:view', ({ request, params }) => {
  const url = new URL(request.url);
  bffRequests.push(url);
  return handleSituationRequest(String(params.view), url, {
    // Em produção vem do token (withAuth); o navegador nunca envia o município.
    tenantId: 'ibge_3143302',
    trino: { url: 'http://trino.test:8088', user: 'sus-nexus-web', catalog: 'iceberg' },
  });
});

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
beforeEach(() => {
  server.use(bff);
  bffRequests.length = 0;
  trinoExecutions.length = 0;
});
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

const as = (...roles: string[]): PublicSession => ({ ...mockSession, roles });
const card = (code: string) =>
  screen.getAllByRole('article').find((a) => a.getAttribute('data-indicator') === code)!;

describe('semáforo e supressão (format)', () => {
  const base = {
    target: 0.8,
    direction: 'maior_melhor' as const,
    is_suppressed: false,
    is_on_target: null,
  };
  it('atingida / atenção (≤10%) / crítica / suprimido / sem dado', () => {
    expect(trafficLight({ ...base, value: 0.85 }).level).toBe('atingida');
    expect(trafficLight({ ...base, value: 0.75 }).level).toBe('atencao');
    expect(trafficLight({ ...base, value: 0.6 }).level).toBe('critica');
    expect(
      trafficLight({ ...base, value: 0.16, target: 0.15, direction: 'menor_melhor' }).level,
    ).toBe('atencao');
    expect(
      trafficLight({ ...base, value: 0.2, target: 0.15, direction: 'menor_melhor' }).level,
    ).toBe('critica');
    expect(trafficLight({ ...base, value: null, is_suppressed: true }).level).toBe('suprimido');
    expect(trafficLight({ ...base, value: null }).level).toBe('sem_dado');
    // is_on_target do dbt prevalece.
    expect(trafficLight({ ...base, value: 0.6, is_on_target: true }).level).toBe('atingida');
  });
  it('suprimido nunca vira zero', () => {
    expect(formatSuppressible({ value: null, suppressed: true })).toBe('<5 (suprimido)');
    expect(formatSuppressible({ value: null, suppressed: false })).toBe('sem dado');
    expect(formatSuppressible({ value: 0, suppressed: false })).toBe('0');
  });
});

describe('SituationPage (/situacao)', () => {
  it('navegação: gestor, auditor e admin municipal veem; ACS e profissionais não', () => {
    for (const r of ['gestor', 'auditor', 'admin_municipal', 'admin'])
      expect(visibleNavItems([r]).map((i) => i.href)).toContain('/situacao');
    for (const r of ['acs', 'enfermagem', 'medico', 'profissional_aps', 'cadastrador', 'dpo'])
      expect(visibleNavItems([r]).map((i) => i.href)).not.toContain('/situacao');
  });

  it('ACS vê acesso restrito e nenhuma consulta é feita', () => {
    renderWithProviders(<SituationPage />, { session: as('acs') });
    expect(screen.getByText('Acesso restrito')).toBeInTheDocument();
    expect(bffRequests).toHaveLength(0);
  });

  it('cartões com meta e semáforo, supressão explícita, série e axe', async () => {
    const { container } = renderWithProviders(
      <SituationPage metabaseUrl="https://metabase.exemplo.gov.br/dashboard/1" />,
      { session: as('gestor') },
    );
    await screen.findByRole(
      'heading',
      { name: 'Indicadores da competência 09/2026' },
      { timeout: 5000 },
    );
    await waitFor(() => expect(screen.getAllByRole('article')).toHaveLength(20));

    expect(card('AGE_ABSENTEISMO').dataset.level).toBe('critica');
    expect(within(card('AGE_ABSENTEISMO')).getByText('Crítica')).toBeInTheDocument();
    expect(within(card('AGE_ABSENTEISMO')).getByText('≤ 15%')).toBeInTheDocument();
    expect(card('AGE_COMPARECIMENTO').dataset.level).toBe('atencao');
    expect(within(card('AGE_COMPARECIMENTO')).getByText('Atenção')).toBeInTheDocument();
    expect(card('REG_SLA_CUMPRIDO').dataset.level).toBe('atingida');

    const agent = card('TAR_AGENTE_SLA_CUMPRIDO');
    expect(agent.dataset.level).toBe('suprimido');
    expect(within(agent).getByTestId('indicator-value')).toHaveTextContent('<5 (suprimido)');
    expect(within(agent).queryByText(/^0(,0)?%?$/)).not.toBeInTheDocument();

    const summary = screen.getByRole('list', { name: 'Situação das metas' });
    expect(within(summary).getByText(/Suprimido: 1/)).toBeInTheDocument();

    expect(
      await screen.findByRole('img', { name: /Gráfico da série histórica de Taxa de absenteísmo/ }),
    ).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Abrir painel no Metabase/ })).toHaveAttribute(
      'href',
      'https://metabase.exemplo.gov.br/dashboard/1',
    );
    // O navegador nunca envia município/SQL ao BFF.
    for (const u of bffRequests) {
      expect([...u.searchParams.keys()]).not.toContain('tenant_id');
      expect(u.search).not.toMatch(/select|ibge_/i);
    }
    expect(trinoExecutions.every((e) => e.params[0] === 'ibge_3143302')).toBe(true);
    expect(await axe(container)).toHaveNoViolations();
  });

  it('série histórica selecionável com tabela acessível', async () => {
    const user = userEvent.setup();
    renderWithProviders(<SituationPage />, { session: as('auditor') });
    await waitFor(() => expect(screen.getAllByRole('article')).toHaveLength(20), { timeout: 5000 });
    await user.click(
      within(card('TAR_AGENTE_SLA_CUMPRIDO')).getByRole('button', { name: /Série histórica/ }),
    );
    const heading = await screen.findByRole('heading', {
      name: /Série histórica — Tarefas criadas por agentes/,
    });
    const section = heading.closest('section')!;
    await user.click(within(section).getByRole('button', { name: 'Mostrar tabela' }));
    const table = within(section).getByRole('table', { name: 'Tabela da série histórica' });
    const last = within(table).getAllByRole('row').at(-1)!;
    expect(last).toHaveTextContent('09/2026');
    expect(last).toHaveTextContent('<5 (suprimido)');
  });

  it('comparação entre unidades: ranking, suprimido fora e desigualdade (razão)', async () => {
    const { container } = renderWithProviders(<SituationPage initialTab="comparacao" />, {
      session: as('admin_municipal'),
    });
    const table = await screen.findByRole(
      'table',
      { name: 'Ranking de unidades' },
      { timeout: 5000 },
    );
    const rows = within(table).getAllByRole('row').slice(1);
    expect(rows).toHaveLength(3);
    const prates = rows.find((r) => r.dataset.cnes === '2126710')!;
    expect(prates).toHaveTextContent('<5 (suprimido)');
    expect(rows.at(-1)).toBe(prates);
    expect(screen.getByTestId('inequality-ratio').textContent).toMatch(/^\d+(,\d)?×$/);
    expect(screen.getByText('2 comparadas · 1 suprimidas')).toBeInTheDocument();
    expect(await axe(container)).toHaveNoViolations();
  });

  it('territórios: mapa esquemático + tabela com células suprimidas', async () => {
    const { container } = renderWithProviders(<SituationPage initialTab="territorios" />, {
      session: as('gestor'),
    });
    expect(
      await screen.findByRole(
        'img',
        { name: /Mapa esquemático: 6 territórios/ },
        { timeout: 5000 },
      ),
    ).toBeInTheDocument();
    const table = screen.getByRole('table', { name: 'Territórios' });
    expect(within(table).getAllByText('<5 (suprimido)').length).toBeGreaterThan(0);
    expect(
      trinoExecutions.some((e) => e.name === 'sit_territorios' && e.params[3] === 'diabetes'),
    ).toBe(true);
    expect(await axe(container)).toHaveNoViolations();
  });

  it('capacidade e risco: hospitais, fila do lakehouse e fila ao vivo do core', async () => {
    const { container } = renderWithProviders(<SituationPage initialTab="capacidade" />, {
      session: as('gestor'),
    });
    const hospitals = await screen.findByRole(
      'table',
      { name: 'Capacidade hospitalar — internações da competência' },
      { timeout: 5000 },
    );
    const santaCasa = within(hospitals)
      .getAllByRole('row')
      .find((r) => r.dataset.cnes === '2149990')!;
    expect(within(santaCasa).getAllByRole('cell')[1]).toHaveTextContent('<5 (suprimido)');
    expect(screen.getByRole('table', { name: 'Fila de regulação (lakehouse)' })).toHaveTextContent(
      'Ortopedia',
    );
    expect(
      await screen.findByRole('table', { name: 'Fila de regulação agora (core)' }),
    ).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Riscos da competência' })).toBeInTheDocument();
    expect(await axe(container)).toHaveNoViolations();
  });
});

describe('Análise assistida (IA) — agente bi_situation_analyst', () => {
  it('gestor gera a análise: fora da meta, tendência, desigualdade, hipóteses rotuladas, recomendações e fontes', async () => {
    const user = userEvent.setup();
    const bodies: unknown[] = [];
    server.events.on('request:start', ({ request }) => {
      if (request.url.endsWith('/agents/bi_situation_analyst/run')) {
        expect(request.url).toBe('http://ai.test/agents/bi_situation_analyst/run');
        void request
          .clone()
          .json()
          .then((b) => bodies.push(b));
      }
    });
    const { container } = renderWithProviders(<SituationPage />, { session: as('gestor') });
    const panel = await screen.findByRole(
      'region',
      { name: 'Análise assistida (IA)' },
      { timeout: 5000 },
    );
    expect(within(panel).getByRole('note')).toHaveTextContent(
      /gerada por IA a partir de dados agregados/,
    );
    expect(within(panel).getByRole('note')).toHaveTextContent(/revisão humana/);
    await waitFor(() => expect(screen.getAllByRole('article')).toHaveLength(20));
    await user.type(within(panel).getByLabelText(/Foco da análise/), 'absenteísmo');
    await user.click(within(panel).getByRole('button', { name: 'Gerar análise' }));
    const result = await within(panel).findByTestId('bi-analysis-result');
    server.events.removeAllListeners();

    const section = (name: string) => within(result).getByRole('region', { name });
    expect(section('Indicadores fora da meta')).toHaveTextContent('Taxa de absenteísmo (no-show)');
    expect(section('Tendência')).toHaveTextContent(/melhora|piora|estável|indeterminada/);
    expect(section('Desigualdade')).toHaveTextContent(/razão/);
    const hyp = within(section('Hipóteses')).getAllByRole('listitem');
    expect(hyp.length).toBeGreaterThan(0);
    for (const h of hyp) {
      expect(h).toHaveAttribute('data-kind', 'hipotese');
      expect(within(h).getByText('Hipótese')).toBeInTheDocument();
    }
    expect(section('Recomendações')).toHaveTextContent('Coordenação da APS');
    expect(section('Limitações dos dados')).toHaveTextContent('TAR_AGENTE_SLA_CUMPRIDO');
    await user.click(within(result).getByRole('button', { name: /Mostrar fontes/ }));
    expect(within(result).getByRole('table', { name: 'Fontes da análise' })).toBeInTheDocument();

    expect(bodies).toEqual([
      { competence: '202609', trend_months: 6, care_line: 'diabetes', question: 'absenteísmo' },
    ]);
    expect(JSON.stringify(bodies)).not.toMatch(/tenant|ibge_/);
    expect(await axe(container)).toHaveNoViolations();
  });

  it('invalid_output vira erro amigável e nada da saída é exibido', async () => {
    const user = userEvent.setup();
    server.use(
      http.post('*/agents/bi_situation_analyst/run', () =>
        HttpResponse.json(biRun({ competence: '202609', trend_months: 6 }, null)),
      ),
    );
    renderWithProviders(
      <BiAnalysisPanel competence="202609" info={(c) => ({ name: c, unit: 'proporcao' })} />,
      {
        session: as('auditor'),
      },
    );
    await user.click(screen.getByRole('button', { name: 'Gerar análise' }));
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Não foi possível validar a análise');
    expect(screen.queryByTestId('bi-analysis-result')).not.toBeInTheDocument();
  });

  it('painel escondido para papéis sem permissão', () => {
    for (const role of ['acs', 'enfermagem', 'medico', 'dpo', 'cadastrador']) {
      const { unmount } = renderWithProviders(
        <BiAnalysisPanel competence="202609" info={(c) => ({ name: c, unit: 'proporcao' })} />,
        { session: as(role) },
      );
      expect(
        screen.queryByRole('region', { name: 'Análise assistida (IA)' }),
      ).not.toBeInTheDocument();
      unmount();
    }
  });
});
