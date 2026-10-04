import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http } from 'msw';
import { axe } from 'vitest-axe';
import type { PublicSession } from '@sus-nexus/auth/client';
import { server } from '@/mocks/server';
import { citizens } from '@/mocks/data';
import { fakeCnsOf, fakeCpfOf } from '@/mocks/fhir-handlers';
import { CitizenPage } from '@/features/cidadaos/CitizenPage';
import { mockSession, renderWithProviders } from './test-utils';

vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), refresh: vi.fn() }),
  usePathname: () => '/cidadaos/x',
  useSearchParams: () => new URLSearchParams(),
}));

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

const as = (...roles: string[]): PublicSession => ({ ...mockSession, roles });
const citizen = citizens[0]!;
const formattedCpf = fakeCpfOf(citizen).replace(/(\d{3})(\d{3})(\d{3})(\d{2})/, '$1.$2.$3-$4');

describe('Cidadão — aba FHIR (FHIRResourceViewer)', () => {
  it('lista o compartimento do paciente com resumo por tipo e CPF/CNS mascarados no JSON', async () => {
    const user = userEvent.setup();
    const purposes: (string | null)[] = [];
    server.events.on('request:start', ({ request }) => {
      if (request.url.includes('$everything'))
        purposes.push(request.headers.get('X-Purpose-Of-Use'));
    });
    const { container } = renderWithProviders(
      <CitizenPage citizenId={citizen.id} initialTab="fhir" />,
      { session: as('medico'), purpose: 'care_coordination' },
    );
    const section = await screen.findByRole(
      'region',
      { name: 'Recursos FHIR do paciente' },
      { timeout: 5000 },
    );
    expect(
      await within(section).findByRole('heading', { name: /^Paciente: / }),
    ).toBeInTheDocument();
    for (const label of [
      'Atendimento',
      'Observação',
      'Laudo',
      'Solicitação',
      'Agendamento',
      'Plano de cuidado',
      'Documento',
      'Tarefa',
      'Condição',
    ]) {
      expect(
        within(section).getByRole('heading', { name: new RegExp(`^${label}: `) }),
      ).toBeInTheDocument();
    }
    expect(
      within(section).getByRole('heading', { name: /Observação: Glicose/ }),
    ).toBeInTheDocument();
    expect(within(section).getByText('126 mg/dL')).toBeInTheDocument();
    expect(purposes.every((p) => p === 'care_coordination')).toBe(true);
    expect(purposes.length).toBeGreaterThan(0);
    server.events.removeAllListeners();

    // Abre o JSON de todos os recursos: nenhum CPF/CNS em claro (nem no texto livre).
    for (const btn of within(section).getAllByRole('button', { name: 'Mostrar JSON' })) {
      await user.click(btn);
    }
    expect(within(section).getAllByLabelText('JSON do recurso')).toHaveLength(10);
    const text = container.textContent ?? '';
    expect(text).not.toContain(fakeCpfOf(citizen));
    expect(text).not.toContain(fakeCnsOf(citizen));
    expect(text).not.toContain(formattedCpf);
    expect(text).toContain(`***.***.***-${fakeCpfOf(citizen).slice(-2)}`);
    expect(text).toContain(`*** **** **** ${fakeCnsOf(citizen).slice(-4)}`);
    expect(await axe(container)).toHaveNoViolations();
  });

  it('papéis não clínicos (cadastrador) não veem a aba FHIR nem consultam o gateway', async () => {
    const calls: string[] = [];
    server.use(
      http.get(/\$everything/, ({ request }) => {
        calls.push(request.url);
        return new Response(null, { status: 500 });
      }),
    );
    renderWithProviders(<CitizenPage citizenId={citizen.id} initialTab="fhir" />, {
      session: as('cadastrador'),
      purpose: 'identity_management',
    });
    await screen.findByRole('tab', { name: 'Resumo operacional' }, { timeout: 5000 });
    expect(screen.queryByRole('tab', { name: 'FHIR' })).not.toBeInTheDocument();
    expect(calls).toHaveLength(0);
  });
});
