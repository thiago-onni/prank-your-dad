import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { axe } from 'vitest-axe';
import { server } from '@/mocks/server';
import { carePlans } from '@/mocks/care-data';
import { citizens } from '@/mocks/data';
import { CarePlansTab } from '@/features/cidadaos/CarePlansTab';
import { SummaryPanel } from '@/features/cidadaos/SummaryPanel';
import { renderWithProviders } from './test-utils';

vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), refresh: vi.fn() }),
  usePathname: () => '/cidadaos/x',
  useSearchParams: () => new URLSearchParams(),
}));

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

const plan = carePlans.find(
  (p) => p.care_line === 'hipertensao' && p.items.some((i) => i.status !== 'done' && i.overdue),
)!;
const citizen = citizens.find((c) => c.id === plan.citizen_id)!;
const item = plan.items.find((i) => i.status !== 'done' && i.overdue)!;

describe('Plano de cuidado do cidadão', () => {
  it('lista planos com itens e atualiza um item para realizado', async () => {
    const { container } = renderWithProviders(<CarePlansTab citizen={citizen} />, {
      purpose: 'care_coordination',
    });
    const section = await screen.findByRole('region', { name: /Hipertensão/ }, { timeout: 5000 });
    const row = within(section).getByText(item.title).closest('tr')!;
    expect(within(row).getByText('Em atraso')).toBeInTheDocument();
    expect(await axe(container)).toHaveNoViolations();

    await userEvent.click(within(row).getByRole('button', { name: `Atualizar: ${item.title}` }));
    const dialog = await screen.findByRole('dialog', { name: 'Atualizar item do plano' });
    // "Realizado" é a situação padrão do diálogo.
    expect(within(dialog).getByRole('combobox', { name: /Situação/ })).toHaveTextContent(
      'Realizado',
    );
    await userEvent.type(within(dialog).getByLabelText('Referência de evidência'), 'fhir://aps/1');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Salvar' }));
    await waitFor(() => expect(screen.getByText('Item do plano atualizado.')).toBeInTheDocument());
    await waitFor(() => {
      const updated = within(section).getByText(item.title).closest('tr')!;
      expect(within(updated).getByText('Realizado')).toBeInTheDocument();
      expect(within(updated).queryByText('Em atraso')).not.toBeInTheDocument();
    });
    expect(plan.items.find((i) => i.id === item.id)?.status).toBe('done');
  });

  it('encerrar plano exige motivo (5–500 caracteres)', async () => {
    renderWithProviders(<CarePlansTab citizen={citizen} />, { purpose: 'care_coordination' });
    const section = await screen.findByRole('region', { name: /Hipertensão/ }, { timeout: 5000 });
    await userEvent.click(within(section).getByRole('button', { name: 'Encerrar plano' }));
    const dialog = await screen.findByRole('dialog', { name: 'Encerrar plano de cuidado' });
    await userEvent.click(within(dialog).getByRole('button', { name: 'Encerrar plano' }));
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Entre 5 e 500');
    await userEvent.type(within(dialog).getByLabelText(/Motivo/), 'Metas atingidas.');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Encerrar plano' }));
    await waitFor(() => expect(screen.getByText('Plano encerrado.')).toBeInTheDocument());
    await waitFor(() => expect(within(section).getByText('Concluído')).toBeInTheDocument());
  });

  it('cria plano somente a partir de protocolo vigente elegível', async () => {
    renderWithProviders(<CarePlansTab citizen={citizen} />, { purpose: 'care_coordination' });
    await screen.findByRole('region', { name: /Hipertensão/ }, { timeout: 5000 });
    await userEvent.click(
      screen.getByRole('button', { name: 'Criar plano a partir de protocolo' }),
    );
    const dialog = await screen.findByRole('dialog', { name: 'Novo plano de cuidado' });
    const select = await within(dialog).findByRole('combobox', { name: /Protocolo vigente/ });
    await userEvent.click(select);
    const options = (await screen.findAllByRole('option')).map((o) => o.textContent ?? '');
    // Sem rascunhos/aprovadas não vigentes; pré-natal só para elegíveis (sexo/idade).
    expect(options.every((o) => !o.includes('1.5.0') && !o.includes('1.3.0'))).toBe(true);
    if (citizen.sex === 'male') expect(options.some((o) => o.startsWith('Pré-natal'))).toBe(false);
    await userEvent.click(screen.getAllByRole('option')[0]!);
    await userEvent.click(
      within(dialog).getByRole('button', { name: 'Criar plano a partir de protocolo' }),
    );
    await waitFor(() => expect(screen.getByText('Plano de cuidado criado.')).toBeInTheDocument());
  });
});

describe('Resumo do cidadão — cartões de continuidade', () => {
  it('mostra última alta, linhas de cuidado, lacunas e contato válido', async () => {
    const { container } = renderWithProviders(<SummaryPanel citizen={citizen} />, {
      purpose: 'care_coordination',
    });
    const lines = await screen.findByRole(
      'region',
      { name: 'Linhas de cuidado' },
      { timeout: 5000 },
    );
    expect(
      within(lines).getAllByText(/Hipertensão|Diabetes|Pré-natal|Nenhum/).length,
    ).toBeGreaterThan(0);
    expect(screen.getByRole('region', { name: 'Última alta hospitalar' })).toBeInTheDocument();
    expect(screen.getByRole('region', { name: 'Lacunas de cuidado' })).toBeInTheDocument();
    expect(screen.getByRole('region', { name: 'Contato válido' })).toBeInTheDocument();
    expect(await axe(container)).toHaveNoViolations();
  });
});
