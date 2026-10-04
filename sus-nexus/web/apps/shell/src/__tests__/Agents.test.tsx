import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { axe } from 'vitest-axe';
import { server } from '@/mocks/server';
import { aiApprovals, aiRuns } from '@/mocks/ai-data';
import { AgentsCockpit } from '@/features/agentes/AgentsCockpit';
import { AgentRunDetail } from '@/features/agentes/AgentRunDetail';
import { mockSession, renderWithProviders } from './test-utils';

vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), refresh: vi.fn() }),
  usePathname: () => '/agentes',
  useSearchParams: () => new URLSearchParams(),
}));

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

const gestor = { ...mockSession, roles: ['gestor'] };

describe('AgentsCockpit', () => {
  it('lista o catálogo com autonomia derivada das ferramentas e esconde o kill switch de gestor', async () => {
    const { container } = renderWithProviders(<AgentsCockpit />, { session: gestor });
    expect(
      await screen.findByRole('article', { name: 'regulation_completeness' }, { timeout: 5000 }),
    ).toBeInTheDocument();
    const suggestion = screen.getByRole('article', { name: 'mpi_duplicate_suggestion' });
    expect(await within(suggestion).findByText('Somente sugestão')).toBeInTheDocument();
    const completeness = screen.getByRole('article', { name: 'regulation_completeness' });
    expect(await within(completeness).findByText('Ação com aprovação humana')).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Kill switch' })).not.toBeInTheDocument();
    expect(await axe(container)).toHaveNoViolations();
  });

  it('mostra o atalho do kill switch para DPO', async () => {
    renderWithProviders(<AgentsCockpit />, { session: { ...mockSession, roles: ['dpo'] } });
    expect(await screen.findByRole('link', { name: 'Kill switch' })).toHaveAttribute(
      'href',
      '/agentes/kill-switch',
    );
  });

  it('lista execuções com ações pendentes', async () => {
    renderWithProviders(<AgentsCockpit initialTab="execucoes" />, { session: gestor });
    const table = await screen.findByRole('table', { name: 'Execuções' }, { timeout: 5000 });
    expect(within(table).getAllByRole('row').length).toBeGreaterThan(1);
    expect(within(table).getAllByText(/ações aguardando aprovação/).length).toBeGreaterThan(0);
  });

  it('aprova uma ação pendente com justificativa obrigatória (10–1000) e atualiza a fila', async () => {
    const pending = aiApprovals.filter((a) => a.status === 'pending');
    const target = pending[0]!;
    const { container } = renderWithProviders(<AgentsCockpit initialTab="aprovacoes" />, {
      session: gestor,
    });
    const table = await screen.findByRole('table', { name: 'Aprovações' }, { timeout: 5000 });
    expect(within(table).getAllByRole('row')).toHaveLength(pending.length + 1);

    const row = within(table).getByText(target.action_id).closest('tr')!;
    await userEvent.click(within(row).getByRole('button', { name: 'Revisar' }));
    const panel = await screen.findByRole('region', {
      name: 'Aprovar ou rejeitar ação do agente',
    });
    expect(within(panel).getByText('core.create_pending_issue')).toBeInTheDocument();
    expect(within(panel).getByText(/request_id = reg_/)).toBeInTheDocument();

    await userEvent.click(within(panel).getByRole('button', { name: 'Aprovar e executar' }));
    expect(within(panel).getByRole('alert')).toHaveTextContent('ao menos 10 caracteres');

    await userEvent.type(
      within(panel).getByLabelText(/Justificativa/),
      'Conferi o encaminhamento: falta mesmo o ECG recente.',
    );
    await userEvent.click(within(panel).getByRole('button', { name: 'Aprovar e executar' }));
    await waitFor(
      () => expect(screen.getByText('Ação aprovada e executada.')).toBeInTheDocument(),
      {
        timeout: 5000,
      },
    );
    const run = aiRuns.find((r) => r.id === target.run_id)!;
    const action = run.actions.find((a) => a.id === target.action_id)!;
    expect(action.status).toBe('approved');
    expect(action.justification).toContain('ECG recente');
    expect(target.status).toBe('approved');
    await waitFor(() =>
      expect(
        within(screen.getByRole('table', { name: 'Aprovações' })).getAllByRole('row'),
      ).toHaveLength(pending.length),
    );
    expect(await axe(container)).toHaveNoViolations();
  });
});

describe('AgentRunDetail', () => {
  it('mostra o rastro de decisão com contexto, ferramentas, decisão OPA e ações', async () => {
    const run = aiRuns.find((r) => r.status === 'denied')!;
    const { container } = renderWithProviders(<AgentRunDetail runId={run.id} />, {
      session: gestor,
    });
    expect(
      await screen.findByText(`Execução ${run.id}`, {}, { timeout: 5000 }),
    ).toBeInTheDocument();
    expect(screen.getByRole('alert')).toHaveTextContent('mpi.merge');
    expect(screen.getByText('Negada pelo OPA')).toBeInTheDocument();
    expect(screen.getAllByText(/forbidden_action_class/).length).toBeGreaterThan(0);
    expect(screen.getByText('Ações planejadas (1)')).toBeInTheDocument();
    expect(
      screen.getByText('Esta execução não tem ações aguardando aprovação.'),
    ).toBeInTheDocument();
    expect(await axe(container)).toHaveNoViolations();
  });
});
