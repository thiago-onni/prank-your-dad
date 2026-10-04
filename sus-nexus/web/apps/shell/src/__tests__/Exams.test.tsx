import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { axe } from 'vitest-axe';
import { server } from '@/mocks/server';
import { examOrders } from '@/mocks/exams-data';
import { ExamsPage } from '@/features/exames/ExamsPage';
import { ExamOrderDetail } from '@/features/exames/ExamOrderDetail';
import { mockSession, renderWithProviders } from './test-utils';

vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), refresh: vi.fn() }),
  usePathname: () => '/exames',
  useSearchParams: () => new URLSearchParams(),
}));

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  server.resetHandlers();
  vi.unstubAllGlobals();
});
afterAll(() => server.close());

describe('ExamsPage', () => {
  it('lista pedidos com pendências e filtra por "Crítico"', async () => {
    const { container } = renderWithProviders(<ExamsPage />);
    const table = await screen.findByRole('table', { name: 'Exames' }, { timeout: 5000 });
    expect(within(table).getAllByRole('row').length).toBeGreaterThan(1);

    await userEvent.click(screen.getByRole('combobox', { name: 'Pendência' }));
    await userEvent.click(await screen.findByRole('option', { name: 'Crítico' }));
    await waitFor(() => {
      const rows = within(screen.getByRole('table', { name: 'Exames' })).getAllByRole('row');
      expect(rows.length).toBe(2);
      expect(rows[1]).toHaveTextContent('Potássio sérico');
      expect(rows[1]).toHaveTextContent('Resultado crítico');
    });
    expect(await axe(container)).toHaveNoViolations();
  });
});

describe('ExamOrderDetail', () => {
  const critical = examOrders[0]!;

  it('mostra stepper, tempos do ciclo, destaque crítico e abre o laudo por URL assinada', async () => {
    const open = vi.fn();
    vi.stubGlobal('open', open);
    const { container } = renderWithProviders(<ExamOrderDetail orderId={critical.id} />);

    expect(await screen.findByText('Potássio sérico', {}, { timeout: 5000 })).toBeInTheDocument();
    expect(screen.getByText(/resultado marcado como crítico pela origem/)).toBeInTheDocument();

    const cycle = screen.getByRole('region', { name: 'Ciclo do exame' });
    const current = within(cycle).getByText('Laudado').closest('li');
    expect(current).toHaveAttribute('aria-current', 'step');
    expect(current).toHaveTextContent('(etapa atual)');
    expect(within(cycle).getByText('Coletado').closest('li')).toHaveTextContent('(concluída)');
    expect(screen.getByText('Pedido → agendamento')).toBeInTheDocument();
    expect(screen.getByText('22 h')).toBeInTheDocument();

    // Papel clínico (enfermagem na sessão mock): pode abrir o laudo com a finalidade corrente.
    const button = screen.getByRole('button', { name: 'Abrir laudo' });
    expect(button).toHaveAccessibleDescription(/Finalidade do acesso ao laudo/);
    await userEvent.click(button);
    await waitFor(() => expect(open).toHaveBeenCalledTimes(1), { timeout: 5000 });
    const [url, target, features] = open.mock.calls[0] as [string, string, string];
    expect(url).toContain('documents.sus-nexus.invalid/signed/');
    expect(target).toBe('_blank');
    expect(features).toContain('noopener');
    expect(await screen.findByText(/Laudo aberto em nova aba/)).toBeInTheDocument();
    expect(await axe(container)).toHaveNoViolations();
  });

  it('para papéis não clínicos mostra apenas a contagem de observações, sem abrir laudo', async () => {
    renderWithProviders(<ExamOrderDetail orderId={critical.id} />, {
      session: { ...mockSession, roles: ['gestor'] },
    });
    expect(await screen.findByText('Potássio sérico', {}, { timeout: 5000 })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Abrir laudo' })).not.toBeInTheDocument();
    expect(screen.getAllByText(/somente a contagem é mostrada/).length).toBeGreaterThan(0);
    expect(screen.getByText('Observações estruturadas')).toBeInTheDocument();
  });
});
