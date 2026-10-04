import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { axe } from 'vitest-axe';
import { server } from '@/mocks/server';
import { careTasks } from '@/mocks/data';
import { CareWorkbench } from '@/features/cuidado/CareWorkbench';
import { renderWithProviders } from './test-utils';

vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), refresh: vi.fn() }),
  usePathname: () => '/cuidado',
  useSearchParams: () => new URLSearchParams(),
}));

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

describe('CareWorkbench', () => {
  it('exige finalidade', () => {
    renderWithProviders(<CareWorkbench />, { purpose: undefined });
    expect(screen.getByText(/Selecione a finalidade do acesso/)).toBeInTheDocument();
  });

  it('agrupa tarefas abertas por tipo com SLA, atalhos e filtro por microárea', async () => {
    const { container } = renderWithProviders(<CareWorkbench />, { purpose: 'care_coordination' });
    const group = await screen.findByRole(
      'region',
      { name: /Recuperação de falta/ },
      { timeout: 5000 },
    );
    expect(within(group).getAllByRole('listitem').length).toBeGreaterThan(0);
    expect(within(group).getAllByRole('link', { name: 'Abrir cidadão' }).length).toBeGreaterThan(0);
    expect(within(group).getAllByRole('group', { name: /Ações:/ }).length).toBeGreaterThan(0);
    // Concluídas/canceladas ficam ocultas por padrão.
    expect(screen.queryByText('Concluída')).not.toBeInTheDocument();

    // Microáreas resolvidas a partir dos cidadãos das tarefas visíveis.
    const micro = screen.getByRole('combobox', { name: 'Microárea' });
    await waitFor(() => expect(micro).not.toHaveAccessibleDescription(/Carregando/), {
      timeout: 8000,
    });
    await userEvent.click(micro);
    const option = await screen.findByRole('option', { name: 'Microárea 01' });
    await userEvent.click(option);
    await waitFor(() => {
      for (const item of screen.getAllByText(/Microárea \d{2}/, { selector: 'p' })) {
        expect(item).toHaveTextContent('Microárea 01');
      }
    });
    expect(await axe(container)).toHaveNoViolations();
  });

  it('executa transição (assumir) a partir do workbench', async () => {
    const open = careTasks.find((task) => task.status === 'open')!;
    renderWithProviders(<CareWorkbench />, { purpose: 'care_coordination' });
    await screen.findByRole('region', { name: /Recuperação de falta/ }, { timeout: 5000 });
    expect(screen.getAllByRole('group', { name: `Ações: ${open.title}` }).length).toBeGreaterThan(
      0,
    );
    await userEvent.click(screen.getAllByRole('button', { name: 'Assumir' })[0]!);
    await waitFor(() => expect(screen.getByText('Tarefa atualizada.')).toBeInTheDocument(), {
      timeout: 5000,
    });
  });
});
