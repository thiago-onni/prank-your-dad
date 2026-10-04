import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { axe } from 'vitest-axe';
import { server } from '@/mocks/server';
import { killSwitchAdmin } from '@/mocks/ai-data';
import { KillSwitchPage } from '@/features/agentes/KillSwitchPage';
import { mockSession, renderWithProviders } from './test-utils';

vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), refresh: vi.fn() }),
  usePathname: () => '/agentes/kill-switch',
  useSearchParams: () => new URLSearchParams(),
}));

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => {
  server.close();
  killSwitchAdmin.global = false;
  killSwitchAdmin.agents = [];
  killSwitchAdmin.tools = [];
  killSwitchAdmin.tenants = [];
});

describe('KillSwitchPage', () => {
  it('bloqueia papéis que não são DPO/admin sem consultar o serviço', () => {
    renderWithProviders(<KillSwitchPage />, { session: { ...mockSession, roles: ['gestor'] } });
    expect(
      screen.getByText('Somente DPO ou administração municipal podem operar o kill switch.'),
    ).toBeInTheDocument();
    expect(screen.queryByText('Estado administrativo (editável)')).not.toBeInTheDocument();
  });

  it('DPO altera o estado com dupla confirmação (revisão + palavra CONFIRMAR)', async () => {
    const { container } = renderWithProviders(<KillSwitchPage />, {
      session: { ...mockSession, roles: ['dpo'] },
    });
    const effective = await screen.findByRole(
      'region',
      { name: 'Estado efetivo (arquivo + ambiente + administrativo)' },
      { timeout: 5000 },
    );
    // Ferramenta bloqueada por variável de ambiente aparece no efetivo, não no administrativo.
    expect(within(effective).getByText('core.send_message')).toBeInTheDocument();

    const editor = screen.getByRole('region', { name: 'Estado administrativo (editável)' });
    const review = within(editor).getByRole('button', { name: 'Revisar alterações' });
    expect(review).toBeDisabled();

    await userEvent.click(within(editor).getByRole('checkbox', { name: /Bloqueio global/ }));
    const toolsInput = within(editor).getByRole('combobox', { name: /ferramentas bloqueadas/i });
    await userEvent.type(toolsInput, 'core.create_task{enter}');
    expect(within(editor).getByText('core.create_task')).toBeInTheDocument();
    expect(within(editor).getByRole('status')).toHaveTextContent('Há alterações não aplicadas.');
    expect(review).toBeEnabled();

    await userEvent.click(review);
    const dialog = await screen.findByRole('dialog');
    expect(dialog).toHaveTextContent('Etapa 1 de 2');
    expect(within(dialog).getByText('Bloqueio global')).toBeInTheDocument();
    expect(within(dialog).queryByLabelText(/Digite CONFIRMAR/)).not.toBeInTheDocument();
    await userEvent.click(within(dialog).getByRole('button', { name: 'Confirmar' }));
    expect(dialog).toHaveTextContent('Etapa 2 de 2');

    await userEvent.click(within(dialog).getByRole('button', { name: 'Aplicar alterações' }));
    expect(within(dialog).getByRole('alert')).toHaveTextContent('Digite exatamente CONFIRMAR');
    expect(killSwitchAdmin.global).toBe(false);

    await userEvent.type(within(dialog).getByLabelText(/Digite CONFIRMAR/), 'CONFIRMAR');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Aplicar alterações' }));
    await waitFor(() => expect(screen.getByText('Kill switch atualizado.')).toBeInTheDocument(), {
      timeout: 5000,
    });
    expect(killSwitchAdmin.global).toBe(true);
    expect(killSwitchAdmin.tools).toEqual(['core.create_task']);
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(await axe(container)).toHaveNoViolations();
  });
});
