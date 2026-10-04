import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { server } from '@/mocks/server';
import { mergeCases } from '@/mocks/data';
import { MergeCaseDetail } from '@/features/cadastro/MergeCaseDetail';
import { renderWithProviders } from './test-utils';

vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), refresh: vi.fn() }),
  usePathname: () => '/cadastro',
  useSearchParams: () => new URLSearchParams(),
}));

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

describe('MergeCaseDetail', () => {
  it('mostra candidatos lado a lado e funde com justificativa obrigatória', async () => {
    const open = mergeCases.find((c) => c.status === 'open')!;
    renderWithProviders(<MergeCaseDetail caseId={open.id} />);
    const table = await screen.findByRole(
      'table',
      { name: 'Cadastros candidatos' },
      { timeout: 5000 },
    );
    expect(table).toBeInTheDocument();
    expect(screen.getAllByRole('columnheader').length).toBeGreaterThanOrEqual(3);

    const mergeBtn = screen.getByRole('button', { name: 'Fundir cadastros' });
    await userEvent.click(mergeBtn);
    expect(screen.getByRole('alert')).toHaveTextContent('ao menos 10 caracteres');

    await userEvent.type(
      screen.getByLabelText(/Justificativa/),
      'Cidadão confirmou presencialmente os dois cadastros',
    );
    await userEvent.click(mergeBtn);
    await waitFor(() => expect(screen.getByText('Fundido')).toBeInTheDocument(), { timeout: 5000 });
    expect(screen.getByText('Este caso já foi decidido.')).toBeInTheDocument();
    expect(open.status).toBe('merged');
  });
});
