import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { axe } from 'vitest-axe';
import type { PublicSession } from '@sus-nexus/auth/client';
import { server } from '@/mocks/server';
import { protocols } from '@/mocks/care-data';
import { ProtocolsPage } from '@/features/admin/ProtocolsPage';
import { parseTestCases } from '@/features/admin/ProtocolEditorDialog';
import { mockSession, renderWithProviders } from './test-utils';

vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), refresh: vi.fn() }),
  usePathname: () => '/admin/protocolos',
  useSearchParams: () => new URLSearchParams(),
}));

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

const gestor: PublicSession = { ...mockSession, roles: ['gestor'] };

describe('ProtocolsPage (/admin/protocolos)', () => {
  it('restringe a gestores/admin (usabilidade; autorização real no backend)', () => {
    renderWithProviders(<ProtocolsPage />, { session: { ...mockSession, roles: ['acs'] } });
    expect(
      screen.getByText('Você não tem permissão para acessar este módulo.'),
    ).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Novo protocolo' })).not.toBeInTheDocument();
  });

  it('lista linhas com versão vigente e bloqueia aprovação sem casos de teste', async () => {
    const { container } = renderWithProviders(<ProtocolsPage />, { session: gestor });
    const has = await screen.findByRole('region', { name: /Hipertensão/ }, { timeout: 5000 });
    expect(within(has).getByText(/Versão vigente: v1\.4\.0/)).toBeInTheDocument();
    expect(screen.getAllByText('Vigente')).toHaveLength(
      protocols.filter((p) => p.status === 'active').length,
    );
    expect(await axe(container)).toHaveNoViolations();

    await userEvent.click(within(has).getByRole('button', { name: 'Aprovar: v1.5.0' }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByRole('alert')).toHaveTextContent(
      'A aprovação exige casos de teste anexados',
    );
    const approve = within(dialog).getByRole('button', { name: 'Aprovar' });
    expect(approve).toBeDisabled();
    expect(approve).toHaveAccessibleDescription(/exige casos de teste/);
    await userEvent.click(approve);
    expect(protocols.find((p) => p.id === 'prot_has' && p.version === '1.5.0')?.status).toBe(
      'in_review',
    );
    expect(await axe(dialog)).toHaveNoViolations();
  });

  it('submit → approve → activate com justificativa', async () => {
    renderWithProviders(<ProtocolsPage />, { session: gestor });
    const dm = await screen.findByRole('region', { name: /Diabetes/ }, { timeout: 5000 });

    const step = async (button: string, confirm: string, justification?: string) => {
      await userEvent.click(within(dm).getByRole('button', { name: button }));
      const dialog = await screen.findByRole('dialog');
      if (justification)
        await userEvent.type(within(dialog).getByLabelText(/Justificativa/), justification);
      await userEvent.click(within(dialog).getByRole('button', { name: confirm }));
      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    };

    await step('Enviar para revisão: v1.3.0', 'Enviar para revisão');
    await within(dm).findByRole('button', { name: 'Aprovar: v1.3.0' });
    // Aprovar exige justificativa mínima.
    await userEvent.click(within(dm).getByRole('button', { name: 'Aprovar: v1.3.0' }));
    const dialog = await screen.findByRole('dialog');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Aprovar' }));
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Entre 10 e 500');
    await userEvent.type(
      within(dialog).getByLabelText(/Justificativa/),
      'Revisado pela coordenação APS.',
    );
    await userEvent.click(within(dialog).getByRole('button', { name: 'Aprovar' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());

    await within(dm).findByRole('button', { name: 'Ativar: v1.3.0' });
    await step('Ativar: v1.3.0', 'Ativar');
    await waitFor(() =>
      expect(within(dm).getByText(/Versão vigente: v1\.3\.0/)).toBeInTheDocument(),
    );
    expect(protocols.find((p) => p.id === 'prot_dm' && p.version === '1.2.0')?.status).toBe(
      'revoked',
    );
  });

  it('cria nova versão (rascunho) com casos de teste em JSON validados', async () => {
    renderWithProviders(<ProtocolsPage />, { session: gestor });
    const pn = await screen.findByRole('region', { name: /Pré-natal/ }, { timeout: 5000 });
    await userEvent.click(within(pn).getByRole('button', { name: 'Nova versão' }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByLabelText(/Linha de cuidado/)).toHaveValue('pre_natal');
    const tests = within(dialog).getByLabelText('Casos de teste (JSON)');
    await userEvent.click(tests);
    await userEvent.paste('{"nao": "lista"}');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Criar rascunho' }));
    expect(await within(dialog).findByText(/JSON inválido/)).toBeInTheDocument();
    await userEvent.clear(tests);
    await userEvent.click(tests);
    await userEvent.paste('[{"input": {"sexo": "F", "idade": 25}, "expected": {"itens": 6}}]');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Criar rascunho' }));
    await waitFor(() =>
      expect(screen.getByText('Rascunho de protocolo criado.')).toBeInTheDocument(),
    );
    const created = protocols.find((p) => p.care_line === 'pre_natal' && p.status === 'draft');
    expect(created?.version).toBe('2.3.0');
    expect(created?.test_cases_count).toBe(1);
  });

  it('parseTestCases aceita apenas lista de objetos', () => {
    expect(parseTestCases('')).toEqual([]);
    expect(parseTestCases('[{"a":1}]')).toEqual([{ a: 1 }]);
    expect(parseTestCases('[1]')).toBeNull();
    expect(parseTestCases('{')).toBeNull();
  });
});
