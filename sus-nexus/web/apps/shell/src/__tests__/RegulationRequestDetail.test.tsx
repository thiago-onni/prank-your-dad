import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { axe } from 'vitest-axe';
import { server } from '@/mocks/server';
import { regulationRequests } from '@/mocks/regulation-data';
import { RegulationRequestDetail } from '@/features/regulacao/RegulationRequestDetail';
import { renderWithProviders } from './test-utils';

vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), refresh: vi.fn() }),
  usePathname: () => '/regulacao/x',
  useSearchParams: () => new URLSearchParams(),
}));

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

describe('RegulationRequestDetail', () => {
  it('mostra cidadão mascarado, histórico, pendências, capacidade e o aviso REG-009', async () => {
    const request = regulationRequests[0]!;
    const { container } = renderWithProviders(<RegulationRequestDetail requestId={request.id} />);

    expect(
      await screen.findByText(/exclusiva do sistema oficial de regulação/, {}, { timeout: 5000 }),
    ).toBeInTheDocument();
    expect(await screen.findByText('Ecocardiografia transtorácica')).toBeInTheDocument();
    // CitizenHeader com identificadores mascarados.
    expect((await screen.findAllByText(/\*\*\*\.\*\*\*\.\*\*\*-\d{2}/)).length).toBeGreaterThan(0);
    expect(screen.queryByText(/^\d{3}\.\d{3}\.\d{3}-\d{2}$/)).not.toBeInTheDocument();

    const timeline = screen.getByRole('region', { name: 'Linha do tempo da situação' });
    expect(within(timeline).getByText('Aguardando documentos')).toBeInTheDocument();
    expect(within(timeline).getByText('Faltam exames complementares')).toBeInTheDocument();
    expect(screen.getByText(/Anexar ECG recente/)).toBeInTheDocument();
    expect(screen.getByText(/1 pendências abertas/)).toBeInTheDocument();
    expect(
      await screen.findByRole('table', { name: 'Capacidade do prestador para o serviço' }),
    ).toBeInTheDocument();

    // Sem decidir, negar, priorizar ou agendar.
    expect(
      screen.queryByRole('button', { name: /autorizar|negar|prioridade|agendar|decidir/i }),
    ).toBeNull();
    expect(await axe(container)).toHaveNoViolations();
  });

  it('abre pendência com tipo e descrição validada (mín. 10 caracteres)', async () => {
    const request = regulationRequests[0]!;
    const before = request.issues?.length ?? 0;
    renderWithProviders(<RegulationRequestDetail requestId={request.id} />);
    await screen.findByText('Ecocardiografia transtorácica', {}, { timeout: 5000 });

    await userEvent.click(screen.getByRole('button', { name: 'Registrar pendência' }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText(/exclusiva do sistema oficial/)).toBeInTheDocument();

    await userEvent.click(within(dialog).getByRole('combobox', { name: /Tipo de pendência/ }));
    await userEvent.click(await screen.findByRole('option', { name: 'Campo obrigatório ausente' }));
    const description = within(dialog).getByLabelText(/Descrição/);
    await userEvent.type(description, 'curta');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Registrar pendência' }));
    expect(within(dialog).getByRole('alert')).toHaveTextContent('Entre 10 e 500 caracteres');

    await userEvent.clear(description);
    await userEvent.type(description, 'Informar CID principal e data do encaminhamento.');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Registrar pendência' }));

    await waitFor(() => expect(screen.getByText('Pendência registrada.')).toBeInTheDocument(), {
      timeout: 5000,
    });
    expect(request.issues).toHaveLength(before + 1);
    expect(
      await screen.findByText('Informar CID principal e data do encaminhamento.'),
    ).toBeInTheDocument();
    expect(screen.getByText(/2 pendências abertas/)).toBeInTheDocument();
  });
});
