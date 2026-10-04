import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { axe } from 'vitest-axe';
import { server } from '@/mocks/server';
import { regulationRequests } from '@/mocks/regulation-data';
import { RegulationCockpit } from '@/features/regulacao/RegulationCockpit';
import { CapacityPage } from '@/features/regulacao/CapacityPage';
import { renderWithProviders } from './test-utils';

vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), refresh: vi.fn() }),
  usePathname: () => '/regulacao',
  useSearchParams: () => new URLSearchParams(),
}));

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

describe('RegulationCockpit', () => {
  it('exige finalidade para listar a fila, mas mostra o resumo agregado', async () => {
    renderWithProviders(<RegulationCockpit />, { purpose: undefined });
    expect(
      await screen.findByRole('button', { name: 'Filtrar fila: Cardiologia' }),
    ).toBeInTheDocument();
    expect(screen.getByText(/Selecione a finalidade do acesso/)).toBeInTheDocument();
    expect(screen.queryByRole('table', { name: 'Solicitações' })).not.toBeInTheDocument();
  });

  it('mostra resumo por especialidade, filtra a fila ao selecionar um grupo e é acessível', async () => {
    const { container } = renderWithProviders(<RegulationCockpit />);
    const card = await screen.findByRole('article', { name: 'Cardiologia' }, { timeout: 5000 });
    const filter = within(card).getByRole('button', { name: 'Filtrar fila: Cardiologia' });
    expect(filter).toHaveAttribute('aria-pressed', 'false');
    expect(within(card).getByText('Espera média')).toBeInTheDocument();
    expect(within(card).getByText('SLA estourado')).toBeInTheDocument();

    const table = await screen.findByRole('table', { name: 'Solicitações' }, { timeout: 5000 });
    expect(within(table).getAllByRole('row').length).toBeGreaterThan(1);
    // CPF/CNS nunca aparecem na fila: apenas o id opaco do cidadão.
    expect(screen.queryByText(/\d{3}\.\d{3}\.\d{3}-\d{2}/)).not.toBeInTheDocument();

    await userEvent.click(filter);
    expect(filter).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('button', { name: 'Limpar seleção' })).toBeInTheDocument();
    await waitFor(() => {
      const rows = within(screen.getByRole('table', { name: 'Solicitações' })).getAllByRole('row');
      expect(rows.length).toBeGreaterThan(1);
      for (const row of rows.slice(1)) expect(row).toHaveTextContent('Cardiologia');
    });

    expect(await axe(container)).toHaveNoViolations();
  });

  it('filtra por pendência "SLA estourado"', async () => {
    renderWithProviders(<RegulationCockpit />);
    await screen.findByRole('table', { name: 'Solicitações' }, { timeout: 5000 });
    await userEvent.click(screen.getByRole('combobox', { name: 'Pendência' }));
    await userEvent.click(await screen.findByRole('option', { name: 'SLA estourado' }));
    const expected = regulationRequests.filter((r) => r.sla_breached).length;
    await waitFor(() =>
      expect(screen.getByText(/solicitações exibidas/)).toHaveTextContent(
        `${expected} solicitações exibidas`,
      ),
    );
  });

  it('não oferece ações de decisão nem de prioridade (REG-009)', async () => {
    renderWithProviders(<RegulationCockpit />);
    await screen.findByRole('table', { name: 'Solicitações' }, { timeout: 5000 });
    expect(screen.queryByRole('button', { name: /autorizar|negar|priorizar|agendar/i })).toBeNull();
  });
});

describe('CapacityPage', () => {
  it('lista oferta por prestador/serviço/competência e filtra por prestador', async () => {
    const { container } = renderWithProviders(<CapacityPage />);
    const table = await screen.findByRole(
      'table',
      { name: 'Capacidade dos prestadores' },
      { timeout: 5000 },
    );
    expect(within(table).getAllByRole('row').length).toBeGreaterThan(2);
    await userEvent.click(screen.getByRole('combobox', { name: 'Prestador' }));
    await userEvent.click(await screen.findByRole('option', { name: /Clínica Conveniada Vida/ }));
    await waitFor(() => {
      const rows = within(
        screen.getByRole('table', { name: 'Capacidade dos prestadores' }),
      ).getAllByRole('row');
      for (const row of rows.slice(1)) expect(row).toHaveTextContent('Clínica Conveniada Vida');
    });
    expect(await axe(container)).toHaveNoViolations();
  });
});
