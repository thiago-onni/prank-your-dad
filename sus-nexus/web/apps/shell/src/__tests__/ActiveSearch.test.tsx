import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { axe } from 'vitest-axe';
import type { PublicSession } from '@sus-nexus/auth/client';
import { server } from '@/mocks/server';
import { careGaps } from '@/mocks/care-data';
import { CareWorkbench } from '@/features/cuidado/CareWorkbench';
import { mockSession, renderWithProviders } from './test-utils';

vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), refresh: vi.fn() }),
  usePathname: () => '/cuidado',
  useSearchParams: () => new URLSearchParams(),
}));

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

const acsSession: PublicSession = {
  ...mockSession,
  user: { ...mockSession.user, name: 'Joana ACS', microareas: ['01'] },
  roles: ['acs'],
};
const gestorSession: PublicSession = { ...mockSession, roles: ['gestor'] };

const bodyRows = () => Array.from(document.querySelectorAll<HTMLTableRowElement>('tbody tr'));
/** Coluna "Microárea" (4ª) da tabela de busca ativa. */
const microareaOf = (row: HTMLTableRowElement) => row.cells[3]?.textContent;

describe('Busca ativa (/cuidado?aba=busca-ativa)', () => {
  it('ACS vê apenas lacunas da própria microárea, sem opção "todas"', async () => {
    const expected = careGaps.filter((g) => g.status === 'open' && g.microarea === '01').length;
    expect(expected).toBeGreaterThan(0);
    const { container } = renderWithProviders(<CareWorkbench initialTab="busca-ativa" />, {
      purpose: 'care_coordination',
      session: acsSession,
    });
    await waitFor(() => expect(bodyRows().length).toBe(expected), { timeout: 5000 });
    for (const row of bodyRows()) expect(microareaOf(row)).toBe('01');
    expect(screen.getByText(/Como ACS, você vê apenas/)).toHaveTextContent('01');

    await userEvent.click(screen.getByRole('combobox', { name: 'Microárea' }));
    const listbox = await screen.findByRole('listbox');
    expect(within(listbox).queryByRole('option', { name: 'Todas as microáreas' })).toBeNull();
    expect(within(listbox).getByRole('option', { name: 'Minhas microáreas' })).toBeInTheDocument();
    expect(
      within(listbox)
        .getAllByRole('option')
        .map((o) => o.textContent),
    ).toEqual(['Minhas microáreas', 'Microárea 01']);
    await userEvent.keyboard('{Escape}');
    expect(await axe(container)).toHaveNoViolations();
  });

  it('gestor vê todas as microáreas, filtra por microárea e mostra contato inválido', async () => {
    renderWithProviders(<CareWorkbench initialTab="busca-ativa" />, {
      purpose: 'care_coordination',
      session: gestorSession,
    });
    const open = careGaps.filter((g) => g.status === 'open');
    await waitFor(() => expect(bodyRows().length).toBe(open.length), { timeout: 5000 });
    expect(new Set(bodyRows().map(microareaOf)).size).toBeGreaterThan(1);
    expect(screen.getAllByText('Inválido').length).toBe(
      open.filter((g) => g.contact_valid === false).length,
    );

    await userEvent.click(screen.getByRole('combobox', { name: 'Microárea' }));
    await userEvent.click(await screen.findByRole('option', { name: 'Microárea 03' }));
    await waitFor(() => {
      expect(bodyRows().length).toBe(open.filter((g) => g.microarea === '03').length);
      for (const row of bodyRows()) expect(microareaOf(row)).toBe('03');
    });
  });

  it('registra o desfecho da busca ativa e remove a lacuna da lista', async () => {
    renderWithProviders(<CareWorkbench initialTab="busca-ativa" />, {
      purpose: 'care_coordination',
      session: acsSession,
    });
    await waitFor(() => expect(bodyRows().length).toBeGreaterThan(0), { timeout: 5000 });
    const before = bodyRows().length;
    await userEvent.click(screen.getAllByRole('button', { name: /^Registrar desfecho:/ })[0]!);
    const dialog = await screen.findByRole('dialog');
    await userEvent.click(within(dialog).getByRole('combobox', { name: /Desfecho/ }));
    await userEvent.click(await screen.findByRole('option', { name: 'Agendado' }));
    await userEvent.click(within(dialog).getByRole('button', { name: 'Registrar desfecho' }));
    await waitFor(() =>
      expect(screen.getByText('Desfecho registrado; lacuna resolvida.')).toBeInTheDocument(),
    );
    await waitFor(() => expect(bodyRows().length).toBe(before - 1));
  });
});

describe('Linhas de cuidado (/cuidado?aba=linhas)', () => {
  it('agrupa planos ativos por linha com % em dia/atrasados e lacunas', async () => {
    const { container } = renderWithProviders(<CareWorkbench initialTab="linhas" />, {
      purpose: 'care_coordination',
      session: gestorSession,
    });
    const has = await screen.findByRole('region', { name: /Hipertensão/ }, { timeout: 5000 });
    expect(within(has).getByText(/% dos itens em dia · \d+% atrasados/)).toBeInTheDocument();
    expect(screen.getByRole('region', { name: /Pré-natal/ })).toBeInTheDocument();
    expect(screen.getByRole('region', { name: /Diabetes/ })).toBeInTheDocument();
    await waitFor(() => expect(within(has).getAllByRole('article').length).toBeGreaterThan(0));
    expect(await axe(container)).toHaveNoViolations();
  });
});
