import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { HttpResponse, http } from 'msw';
import { axe } from 'vitest-axe';
import type { PublicSession } from '@sus-nexus/auth/client';
import { server } from '@/mocks/server';
import {
  CURRENT_COMPETENCE,
  PRODUCTION_RULE_VERSION,
  productionBatches,
  productionRecords,
} from '@/mocks/production-data';
import { ProductionPage } from '@/features/producao/ProductionPage';
import { ProductionRecordDetail } from '@/features/producao/ProductionRecordDetail';
import { ProductionBatchDetail } from '@/features/producao/ProductionBatchDetail';
import { BatchesTab } from '@/features/producao/BatchesTab';
import { hasAnyProductionAction, productionPermissions } from '@/features/producao/permissions';
import { deadlineAlert, safeMaskedIdentifier } from '@/features/producao/format';
import { visibleNavItems } from '@/components/layout/nav';
import { mockSession, renderWithProviders } from './test-utils';

const push = vi.fn();
vi.mock('next/navigation', () => ({
  useRouter: () => ({ push, replace: vi.fn(), refresh: vi.fn() }),
  usePathname: () => '/producao',
  useSearchParams: () => new URLSearchParams(),
}));

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

const as = (...roles: string[]): PublicSession => ({ ...mockSession, roles });
const opts = (...roles: string[]) => ({
  purpose: 'production_audit' as const,
  session: as(...roles),
});

describe('ProductionPage (/producao) — painel', () => {
  it('exige finalidade', () => {
    renderWithProviders(<ProductionPage />, { purpose: undefined, session: as('auditor') });
    expect(screen.getByText(/Selecione a finalidade do acesso/)).toBeInTheDocument();
  });

  it('renderiza resumo da competência e prazos com destaque D-5 e vencido', async () => {
    const { container } = renderWithProviders(<ProductionPage />, opts('auditor'));
    const summary = await screen.findByRole(
      'region',
      { name: 'Resumo da competência 09/2026' },
      { timeout: 5000 },
    );
    const total = productionRecords.filter((r) => r.competence === CURRENT_COMPETENCE).length;
    expect(
      within(summary).getByText('Registros', { selector: 'dt' }).nextSibling,
    ).toHaveTextContent(String(total));
    expect(within(summary).getByText('Perda evitável estimada', { exact: false })).toBeVisible();
    // Destaque D-5 na competência corrente (badge + alerta).
    expect(within(summary).getByRole('alert')).toHaveTextContent(/D-5/);
    expect(within(summary).getByText('cbo_incompatible')).toBeInTheDocument();

    const deadlines = screen.getByRole('table', { name: 'Prazos de apresentação' });
    const rows = within(deadlines).getAllByRole('row').slice(1);
    const levels = rows.map((r) => r.dataset.alert);
    expect(levels).toEqual(['overdue', 'overdue', 'd5', 'ok']);
    expect(within(rows[1]!).getByText('Vencido')).toBeInTheDocument();
    expect(await axe(container)).toHaveNoViolations();
  });

  it('papel sem permissão de ação vê aviso de somente leitura', async () => {
    renderWithProviders(<ProductionPage />, opts('admin_municipal'));
    expect(await screen.findByRole('note')).toHaveTextContent(/somente leitura/);
  });

  it('lista registros, filtra por instrumento e pagina por cursor', async () => {
    renderWithProviders(<ProductionPage initialTab="registros" />, opts('auditor'));
    const table = await screen.findByRole(
      'table',
      { name: 'Registros de produção' },
      { timeout: 5000 },
    );
    expect(within(table).getAllByRole('row').length - 1).toBe(20);
    await userEvent.click(screen.getByRole('button', { name: 'Próxima página' }));
    await waitFor(() =>
      expect(
        within(screen.getByRole('table', { name: 'Registros de produção' })).getAllByRole('row')
          .length - 1,
      ).toBe(productionRecords.length - 20),
    );

    await userEvent.click(screen.getByRole('combobox', { name: 'Instrumento' }));
    await userEvent.click(await screen.findByRole('option', { name: 'AIH' }));
    await waitFor(() => {
      const rows = within(screen.getByRole('table', { name: 'Registros de produção' }))
        .getAllByRole('row')
        .slice(1);
      expect(rows.length).toBe(productionRecords.filter((r) => r.kind === 'aih').length);
      for (const r of rows) expect(r).toHaveTextContent('AIH');
    });
  });

  it('fila de pendências mostra erros e avisos com versão da regra', async () => {
    renderWithProviders(<ProductionPage initialTab="pendencias" />, opts('auditor'));
    const table = await screen.findByRole(
      'table',
      { name: 'Fila de pendências' },
      { timeout: 5000 },
    );
    const rows = within(table).getAllByRole('row').slice(1);
    expect(rows.some((r) => r.dataset.severity === 'error')).toBe(true);
    expect(rows.some((r) => r.dataset.severity === 'warning')).toBe(true);
    // Erros primeiro.
    expect(rows[0]!.dataset.severity).toBe('error');
    expect(within(table).getAllByText(PRODUCTION_RULE_VERSION).length).toBe(rows.length);
  });
});

describe('ProductionRecordDetail — correção', () => {
  it('corrige registro dispensando aviso com justificativa e revalida', async () => {
    const record = productionRecords.find(
      (r) =>
        r.status === 'pending' &&
        (r.issues ?? []).length > 0 &&
        (r.issues ?? []).every((i) => i.severity === 'warning'),
    )!;
    const { container } = renderWithProviders(
      <ProductionRecordDetail recordId={record.id} />,
      opts('auditor'),
    );
    const form = await screen.findByRole(
      'region',
      { name: 'Corrigir e revalidar' },
      { timeout: 5000 },
    );
    const issues = screen.getByRole('region', { name: 'Pendências de pré-auditoria' });
    expect(within(issues).getByText(`Versão das regras: ${PRODUCTION_RULE_VERSION}`)).toBeVisible();
    expect(within(issues).getByText('Aviso')).toBeInTheDocument();
    expect(await axe(container)).toHaveNoViolations();

    const warning = record.issues![0]!;
    // Justificativa curta é recusada no cliente.
    await userEvent.click(
      within(form).getByRole('checkbox', { name: `Dispensar aviso: ${warning.message}` }),
    );
    await userEvent.type(within(form).getByLabelText(/Justificativa/), 'curta');
    await userEvent.click(within(form).getByRole('button', { name: 'Corrigir e revalidar' }));
    expect(await within(form).findByRole('alert')).toHaveTextContent(/entre 10 e 1000/);

    await userEvent.clear(within(form).getByLabelText(/Justificativa/));
    await userEvent.type(
      within(form).getByLabelText(/Justificativa/),
      'Mutirão de glicemia na unidade confirmado pela coordenação.',
    );
    await userEvent.click(within(form).getByRole('button', { name: 'Corrigir e revalidar' }));
    expect(await screen.findByText('Registro corrigido e revalidado.')).toBeInTheDocument();
    await waitFor(() => {
      const item = within(screen.getByRole('region', { name: 'Pendências de pré-auditoria' }))
        .getByText(warning.message)
        .closest('li')!;
      expect(within(item).getByText('Dispensada')).toBeInTheDocument();
    });
    const history = screen.getByRole('table', { name: 'Histórico' });
    expect(within(history).getByText(/Mutirão de glicemia/)).toBeInTheDocument();
    expect(within(history).getByText('Com pendência → Validado')).toBeInTheDocument();
  });

  it('erro não pode ser dispensado (sem caixa de dispensa) e só aviso é oferecido', async () => {
    const record = productionRecords.find(
      (r) =>
        (r.issues ?? []).some((i) => i.severity === 'error') &&
        (r.issues ?? []).some((i) => i.severity === 'warning'),
    )!;
    renderWithProviders(<ProductionRecordDetail recordId={record.id} />, opts('auditor'));
    const form = await screen.findByRole(
      'region',
      { name: 'Corrigir e revalidar' },
      { timeout: 5000 },
    );
    const checkboxes = within(form).getAllByRole('checkbox');
    expect(checkboxes).toHaveLength(1);
    const err = record.issues!.find((i) => i.severity === 'error')!;
    expect(within(form).queryByRole('checkbox', { name: new RegExp(err.message) })).toBeNull();
    expect(within(form).getByText(/Erros não podem ser dispensados/)).toBeInTheDocument();
  });

  it('mascara CNS do cidadão e do profissional (mesmo se o core enviar em claro)', async () => {
    const record = productionRecords.find(
      (r) => r.kind === 'bpa_i' && r.citizen_identifier_masked,
    )!;
    renderWithProviders(<ProductionRecordDetail recordId={record.id} />, opts('auditor'));
    const data = await screen.findByRole(
      'region',
      { name: 'Dados do registro' },
      { timeout: 5000 },
    );
    expect(within(data).getByText(record.citizen_identifier_masked!)).toBeInTheDocument();
    expect(within(data).getByText(record.professional_cns_masked!)).toBeInTheDocument();
    expect(document.body.textContent).not.toMatch(/\d{15}/);

    // Defesa em profundidade: valor em claro vindo por engano é mascarado na tela.
    server.use(
      http.get('*/api/v1/production/records/:id', () =>
        HttpResponse.json({
          ...record,
          id: 'prod_LEAK',
          citizen_identifier_masked: '898001160123456',
          professional_cns_masked: '700 0000 0000 7788',
        }),
      ),
    );
    renderWithProviders(<ProductionRecordDetail recordId="prod_LEAK" />, opts('auditor'));
    await screen.findByText('*** **** **** 3456', undefined, { timeout: 5000 });
    expect(screen.getByText('*** **** **** 7788')).toBeInTheDocument();
    expect(document.body.textContent).not.toContain('898001160123456');
    expect(document.body.textContent).not.toContain('700 0000 0000 7788');
  });
});

describe('ProductionBatchDetail — aprovação e exportação', () => {
  it('aprovação exige justificativa (gestor) e exportação fica oculta ao gestor', async () => {
    const batch = productionBatches.find((b) => b.status === 'draft')!;
    const { container } = renderWithProviders(
      <ProductionBatchDetail batchId={batch.id} />,
      opts('gestor'),
    );
    await userEvent.click(
      await screen.findByRole('button', { name: 'Aprovar lote' }, { timeout: 5000 }),
    );
    const dialog = await screen.findByRole('dialog');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Aprovar lote' }));
    expect(await within(dialog).findByRole('alert')).toHaveTextContent(/entre 10 e 1000/);
    expect(batch.status).toBe('draft');

    await userEvent.type(
      within(dialog).getByLabelText(/Justificativa/),
      'Conferido com o relatório do e-SUS APS da competência.',
    );
    await userEvent.click(within(dialog).getByRole('button', { name: 'Aprovar lote' }));
    expect(await screen.findByText('Lote aprovado.')).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Aprovar lote' })).toBeNull());
    expect(
      screen.getByText('Conferido com o relatório do e-SUS APS da competência.'),
    ).toBeVisible();
    // Gestor aprova, mas não exporta.
    expect(screen.queryByRole('button', { name: 'Exportar arquivo' })).toBeNull();
    expect(await axe(container)).toHaveNoViolations();
  });

  it('auditor exporta lote aprovado e vê SHA-256 e aviso de diretório restrito', async () => {
    const batch = productionBatches.find((b) => b.status === 'approved' && !b.export)!;
    renderWithProviders(<ProductionBatchDetail batchId={batch.id} />, opts('auditor'));
    const exportSection = await screen.findByRole(
      'region',
      { name: 'Exportação' },
      { timeout: 5000 },
    );
    expect(within(exportSection).getByRole('note')).toHaveTextContent(/diretório restrito/);
    await userEvent.click(within(exportSection).getByRole('button', { name: 'Exportar arquivo' }));
    expect(await screen.findByText('Lote exportado.')).toBeInTheDocument();
    const sha = await within(exportSection).findByTestId('export-sha256');
    expect(sha.textContent).toMatch(/^[0-9a-f]{64}$/);
    expect(within(exportSection).getByRole('note')).toHaveTextContent(/identificadores/);
  });
});

describe('retorno oficial', () => {
  it('auditor registra rejeição oficial (motivo obrigatório) e reabre pendência', async () => {
    const record = productionRecords.find((r) => r.status === 'exported')!;
    renderWithProviders(<ProductionRecordDetail recordId={record.id} />, opts('auditor'));
    // Registro exportado não aceita correção no barramento.
    expect(
      await screen.findByText(/não aceita correção nesta situação/, undefined, { timeout: 5000 }),
    ).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Registrar retorno oficial' }));
    const dialog = await screen.findByRole('dialog');
    await userEvent.click(within(dialog).getByRole('combobox', { name: /Retorno/ }));
    await userEvent.click(await screen.findByRole('option', { name: 'Rejeitado/glosado' }));
    await userEvent.click(
      within(dialog).getByRole('button', { name: 'Registrar retorno oficial' }),
    );
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Informe o motivo');
    await userEvent.type(
      within(dialog).getByLabelText(/^Motivo/),
      'Procedimento incompatível com o CBO',
    );
    await userEvent.click(
      within(dialog).getByRole('button', { name: 'Registrar retorno oficial' }),
    );
    expect(await screen.findByText(/Retorno oficial registrado \(1 registro/)).toBeInTheDocument();
    await waitFor(() =>
      expect(
        within(screen.getByRole('region', { name: 'Pendências de pré-auditoria' })).getByText(
          'official_rejection',
        ),
      ).toBeInTheDocument(),
    );
  });
});

describe('ações escondidas por papel', () => {
  it('agente de IA não vê correção, retorno oficial, aprovação nem geração de lote', async () => {
    const record = productionRecords.find((r) => r.status === 'pending')!;
    const agent = opts('agente_ia', 'auditor', 'gestor');
    renderWithProviders(<ProductionRecordDetail recordId={record.id} />, agent);
    await screen.findByRole('region', { name: 'Dados do registro' }, { timeout: 5000 });
    expect(screen.queryByRole('region', { name: 'Corrigir e revalidar' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Registrar retorno oficial' })).toBeNull();

    const draft = { ...productionBatches[0]!, id: 'pbt_DRAFT', status: 'draft' as const };
    delete draft.export;
    server.use(http.get('*/api/v1/production/batches/:id', () => HttpResponse.json(draft)));
    renderWithProviders(<ProductionBatchDetail batchId={draft.id} />, agent);
    await screen.findByRole('region', { name: 'Exportação' }, { timeout: 5000 });
    expect(screen.queryByRole('button', { name: 'Aprovar lote' })).toBeNull();

    renderWithProviders(<BatchesTab />, agent);
    await screen.findByRole('table', { name: 'Lotes de produção' }, { timeout: 5000 });
    expect(screen.queryByRole('button', { name: 'Gerar lote' })).toBeNull();
  });

  it('operador de integração: leitura sem ações; auditor gera lote', async () => {
    renderWithProviders(<BatchesTab />, opts('operador_integracao'));
    await screen.findByRole('table', { name: 'Lotes de produção' }, { timeout: 5000 });
    expect(screen.queryByRole('button', { name: 'Gerar lote' })).toBeNull();
  });

  it('auditor gera lote somente com registros validados', async () => {
    renderWithProviders(<BatchesTab />, opts('auditor'));
    const card = await screen.findByRole('region', { name: 'Gerar lote' }, { timeout: 5000 });
    await userEvent.click(within(card).getByRole('button', { name: 'Gerar lote' }));
    expect(await within(card).findByRole('alert')).toHaveTextContent(/Selecione competência/);

    const eligible = productionRecords.filter(
      (r) =>
        r.status === 'validated' &&
        !r.batch_id &&
        r.competence === CURRENT_COMPETENCE &&
        r.cnes === '2126699' &&
        r.kind === 'bpa_i',
    );
    expect(eligible.length).toBeGreaterThan(0);
    await userEvent.click(within(card).getByRole('combobox', { name: /Competência/ }));
    await userEvent.click(await screen.findByRole('option', { name: '09/2026' }));
    await userEvent.click(within(card).getByRole('combobox', { name: /Unidade/ }));
    await userEvent.click(await screen.findByRole('option', { name: /2126699/ }));
    await userEvent.click(within(card).getByRole('combobox', { name: /Instrumento/ }));
    await userEvent.click(await screen.findByRole('option', { name: 'BPA-I' }));
    await userEvent.click(within(card).getByRole('button', { name: 'Gerar lote' }));
    expect(
      await screen.findByText(new RegExp(`gerado com ${eligible.length} registros`)),
    ).toBeInTheDocument();
    expect(push).toHaveBeenCalledWith(expect.stringMatching(/^\/producao\/lotes\/pbt_/));
  });

  it('matriz de permissões (usabilidade)', () => {
    expect(productionPermissions(['auditor'])).toMatchObject({
      canCorrect: true,
      canCreateBatch: true,
      canApprove: true,
      canExport: true,
      canRegisterOutcome: true,
    });
    expect(productionPermissions(['gestor'])).toMatchObject({
      canCorrect: false,
      canCreateBatch: false,
      canApprove: true,
      canExport: false,
      canRegisterOutcome: false,
    });
    const agent = productionPermissions(['agente_ia', 'admin']);
    expect(Object.values({ ...agent, isAgent: false }).every((v) => v === false)).toBe(true);
    expect(productionPermissions(['operador_integracao'])).toMatchObject({
      canApprove: false,
      canRegisterOutcome: true,
    });
    expect(hasAnyProductionAction(productionPermissions(['admin_municipal', 'admin']))).toBe(false);
    expect(visibleNavItems(['auditor']).map((i) => i.href)).toContain('/producao');
    expect(visibleNavItems(['acs']).map((i) => i.href)).not.toContain('/producao');
  });
});

describe('utilitários', () => {
  it('safeMaskedIdentifier mascara CNS/CPF em claro e preserva máscara do core', () => {
    expect(safeMaskedIdentifier('898 0011 6012 3456')).toBe('*** **** **** 3456');
    expect(safeMaskedIdentifier('12345678901')).toBe('***.***.***-01');
    expect(safeMaskedIdentifier('*** **** **** 1234')).toBe('*** **** **** 1234');
    expect(safeMaskedIdentifier(undefined)).toBe('—');
  });

  it('deadlineAlert classifica D-5, D-1 e vencido', () => {
    const at = '2026-10-10T23:59:00-03:00';
    expect(deadlineAlert({ deadline_at: at, status: 'open', days_remaining: 12 }).level).toBe('ok');
    expect(deadlineAlert({ deadline_at: at, status: 'closing', days_remaining: 5 }).level).toBe(
      'd5',
    );
    expect(deadlineAlert({ deadline_at: at, status: 'closing', days_remaining: 1 }).level).toBe(
      'd1',
    );
    expect(deadlineAlert({ deadline_at: at, status: 'closed', days_remaining: -3 }).level).toBe(
      'overdue',
    );
  });
});
