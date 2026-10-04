import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { axe } from 'vitest-axe';
import { server } from '@/mocks/server';
import { citizens } from '@/mocks/data';
import { CitizenSearch } from '@/features/cadastro/CitizenSearch';
import { renderWithProviders } from './test-utils';

vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), refresh: vi.fn() }),
  usePathname: () => '/cadastro',
  useSearchParams: () => new URLSearchParams(),
}));

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

describe('CitizenSearch', () => {
  it('exige finalidade selecionada', () => {
    renderWithProviders(<CitizenSearch />, { purpose: undefined });
    expect(screen.getByText(/Selecione a finalidade do acesso/)).toBeInTheDocument();
  });

  it('busca por nome e exibe identificadores mascarados', async () => {
    const target = citizens[0]!;
    const { container } = renderWithProviders(<CitizenSearch />);
    const input = screen.getByLabelText(/Nome, nome social ou nome da mãe/);
    await userEvent.type(input, target.legal_name!.split(' ')[0]!);
    await userEvent.click(screen.getByRole('button', { name: 'Buscar' }));
    const table = await screen.findByRole('table', { name: 'Resultados' }, { timeout: 5000 });
    expect(table).toBeInTheDocument();
    expect(screen.getAllByText(/\*\*\*\.\*\*\*\.\*\*\*-\d{2}/).length).toBeGreaterThan(0);
    expect(screen.queryByText(/^\d{3}\.\d{3}\.\d{3}-\d{2}$/)).not.toBeInTheDocument();
    expect(await axe(container)).toHaveNoViolations();
  });

  it('valida CPF antes de buscar', async () => {
    renderWithProviders(<CitizenSearch />);
    await userEvent.click(screen.getByRole('combobox', { name: /Buscar por/ }));
    await userEvent.click(await screen.findByRole('option', { name: 'CPF' }));
    const input = screen.getByLabelText(/CPF \(11 dígitos\)/);
    await userEvent.type(input, '123456');
    expect(input).toHaveValue('123.456');
    await userEvent.click(screen.getByRole('button', { name: 'Buscar' }));
    await waitFor(() =>
      expect(screen.getByRole('alert')).toHaveTextContent('CPF deve ter 11 dígitos.'),
    );
  });
});
