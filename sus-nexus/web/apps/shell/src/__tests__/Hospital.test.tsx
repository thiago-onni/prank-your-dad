import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { axe } from 'vitest-axe';
import { server } from '@/mocks/server';
import { RISK_RULE_VERSION, hospitalEpisodes } from '@/mocks/care-data';
import { HospitalPage } from '@/features/hospital/HospitalPage';
import { HospitalEpisodeDetail } from '@/features/hospital/HospitalEpisodeDetail';
import { mockSession, renderWithProviders } from './test-utils';

vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), refresh: vi.fn() }),
  usePathname: () => '/hospital',
  useSearchParams: () => new URLSearchParams(),
}));

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

const bodyRows = (container: HTMLElement) =>
  Array.from(container.querySelectorAll<HTMLTableRowElement>('tbody tr'));

describe('HospitalPage (/hospital)', () => {
  it('exige finalidade', () => {
    renderWithProviders(<HospitalPage />, { purpose: undefined });
    expect(screen.getByText(/Selecione a finalidade do acesso/)).toBeInTheDocument();
  });

  it('lista episódios com risco (versão da regra) e filtra por risco alto', async () => {
    const { container } = renderWithProviders(<HospitalPage />, { purpose: 'care_coordination' });
    await waitFor(() => expect(bodyRows(container).length).toBe(hospitalEpisodes.length), {
      timeout: 5000,
    });
    // Risco variado e versão da regra no nome acessível do gatilho do tooltip.
    const risks = new Set(bodyRows(container).map((r) => r.dataset.risk));
    expect(risks).toEqual(new Set(['high', 'medium', 'low', 'none']));
    expect(
      screen.getAllByRole('button', {
        name: `Risco alto. Classificado pela regra ${RISK_RULE_VERSION}`,
      }).length,
    ).toBeGreaterThan(0);
    expect(screen.getAllByText('Reinternação 30d').length).toBeGreaterThan(0);

    await userEvent.click(screen.getByRole('combobox', { name: 'Risco' }));
    await userEvent.click(await screen.findByRole('option', { name: 'Risco alto' }));
    await waitFor(() => {
      const rows = bodyRows(container);
      expect(rows.length).toBe(hospitalEpisodes.filter((e) => e.risk_level === 'high').length);
      for (const r of rows) expect(r.dataset.risk).toBe('high');
    });
    expect(screen.getByRole('status')).toHaveTextContent(/alto risco/);
    expect(await axe(container)).toHaveNoViolations();
  });

  it('usa a UBS de lotação do usuário como filtro padrão de referência', async () => {
    const cnes = hospitalEpisodes[0]!.reference_health_unit_cnes!;
    const { container } = renderWithProviders(<HospitalPage />, {
      purpose: 'care_coordination',
      session: { ...mockSession, user: { ...mockSession.user, cnes: [cnes] } },
    });
    const expected = hospitalEpisodes.filter((e) => e.reference_health_unit_cnes === cnes).length;
    await waitFor(() => expect(bodyRows(container).length).toBe(expected), { timeout: 5000 });
    expect(expected).toBeLessThan(hospitalEpisodes.length);
  });
});

describe('HospitalEpisodeDetail (/hospital/[id])', () => {
  it('não exibe CID quando ausente no payload e sinaliza reinternação', async () => {
    const ep = hospitalEpisodes.find(
      (e) => e.readmission_within_30d && !e.principal_diagnosis_cid,
    )!;
    renderWithProviders(<HospitalEpisodeDetail episodeId={ep.id} />, {
      purpose: 'care_coordination',
    });
    const discharge = await screen.findByRole(
      'region',
      { name: 'Dados da alta' },
      { timeout: 5000 },
    );
    expect(within(discharge).getByText(/Não informado no payload/)).toBeInTheDocument();
    expect(within(discharge).queryByText(/^[A-Z]\d{2}\.\d$/)).not.toBeInTheDocument();
    const data = screen.getByRole('region', { name: 'Dados do episódio' });
    expect(within(data).getByText('Sim')).toBeInTheDocument();
    expect(within(data).getByRole('link', { name: 'Abrir episódio' })).toHaveAttribute(
      'href',
      `/hospital/${ep.previous_episode_id}`,
    );
  });

  it('mostra tarefa vinculada com SLA estourado e registra o desfecho do contato', async () => {
    const ep = hospitalEpisodes[0]!;
    expect(ep.followup?.status).toBe('pending');
    const { container } = renderWithProviders(<HospitalEpisodeDetail episodeId={ep.id} />, {
      purpose: 'care_coordination',
    });
    const panel = await screen.findByRole(
      'region',
      { name: 'Acompanhamento pós-alta' },
      { timeout: 5000 },
    );
    // CID presente no payload é exibido como veio.
    expect(screen.getByText(ep.principal_diagnosis_cid!)).toBeInTheDocument();
    await waitFor(() => expect(within(panel).getByText(/SLA estourado/)).toBeInTheDocument());
    expect(await axe(container)).toHaveNoViolations();

    // Sem desfecho → erro associado ao campo.
    await userEvent.click(within(panel).getByRole('button', { name: 'Registrar desfecho' }));
    expect(await within(panel).findByRole('alert')).toHaveTextContent('Desfecho');

    await userEvent.click(within(panel).getByRole('combobox', { name: /Desfecho/ }));
    await userEvent.click(await screen.findByRole('option', { name: 'Consulta agendada na UBS' }));
    await userEvent.type(
      within(panel).getByLabelText('Observação'),
      'Consulta marcada para quinta-feira.',
    );
    await userEvent.click(within(panel).getByRole('button', { name: 'Registrar desfecho' }));
    await waitFor(() =>
      expect(screen.getByText('Desfecho pós-alta registrado.')).toBeInTheDocument(),
    );
    await waitFor(() => expect(within(panel).getAllByText('Consulta agendada').length).toBe(1));
    expect(
      within(panel).getByText('Consulta agendada na UBS', { selector: 'dd' }),
    ).toBeInTheDocument();
    // A tarefa vinculada foi concluída pelo core (mock) e o SLA deixa de estourar.
    await waitFor(() => expect(within(panel).getByText('Encerrada')).toBeInTheDocument());
  });
});
