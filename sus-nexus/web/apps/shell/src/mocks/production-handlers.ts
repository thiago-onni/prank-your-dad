import { HttpResponse, http, type DefaultBodyType, type PathParams } from 'msw';
import type {
  ProductionBatch,
  ProductionBatchCreate,
  ProductionCorrection,
  ProductionExportLayout,
  ProductionIssue,
  ProductionOutcomeRegistration,
  ProductionOutcomeResult,
  ProductionRecord,
} from '@sus-nexus/api-client';
import { iso } from './data';
import {
  computeSummary,
  deadlinesWithCounts,
  fakeSha256,
  newBatchId,
  newIssueId,
  productionBatches,
  productionRecords,
  PRODUCTION_RULE_VERSION,
} from './production-data';
import { delay, paginate, problem, readJson, requirePurpose } from './http-utils';

/**
 * Handlers MSW (stateful) da produção e pré-auditoria (PRO-001..010). Validam o mesmo que o
 * contrato exige (justificativa ≥ 10, avisos dispensáveis e erros não, lote só com `validated`,
 * exportação só após aprovação) e simulam a revalidação do core. A autorização por papel é do
 * core/OPA — o mock não a reproduz.
 */

const MOCK_ACTOR = 'usuario.mock';
const nowIso = () => iso(new Date());
const CORRECTABLE: ProductionRecord['status'][] = ['pending', 'validated', 'rejected'];
const COMPETENCE_RE = /^[0-9]{4}(0[1-9]|1[0-2])$/;

function allIssues(): ProductionIssue[] {
  return productionRecords.flatMap((r) =>
    (r.issues ?? []).map((i) => ({ ...i, record_status: r.status })),
  );
}

/** Revalida após correção: erros cujo campo mudou são resolvidos; sem pendência aberta → `validated`. */
function revalidate(record: ProductionRecord, changed: string[]) {
  for (const i of record.issues ?? []) {
    if (i.status !== 'open') continue;
    if (i.field && changed.includes(i.field)) {
      i.status = 'resolved';
      i.resolved_at = nowIso();
      i.resolution_note = 'Campo corrigido e revalidado.';
    }
  }
  const open = (record.issues ?? []).filter((i) => i.status === 'open');
  const from = record.status;
  record.status = open.length === 0 ? 'validated' : 'pending';
  record.rule_version = PRODUCTION_RULE_VERSION;
  if (record.status === 'validated') record.validated_at = nowIso();
  return from;
}

export const productionHandlers = [
  http.get('*/api/v1/production/records', async ({ request }) => {
    await delay();
    const purpose = requirePurpose(request);
    if (purpose instanceof Response) return purpose;
    const url = new URL(request.url);
    const q = (k: string) => url.searchParams.get(k);
    let list = productionRecords;
    if (q('competence')) list = list.filter((r) => r.competence === q('competence'));
    if (q('cnes')) list = list.filter((r) => r.cnes === q('cnes'));
    if (q('kind')) list = list.filter((r) => r.kind === q('kind'));
    if (q('status')) list = list.filter((r) => r.status === q('status'));
    if (q('procedure_code')) list = list.filter((r) => r.procedure_code === q('procedure_code'));
    if (q('citizen_id')) list = list.filter((r) => r.citizen_id === q('citizen_id'));
    if (q('batch_id')) list = list.filter((r) => r.batch_id === q('batch_id'));
    const sorted = [...list].sort((a, b) =>
      a.competence === b.competence
        ? a.attendance_date < b.attendance_date
          ? 1
          : -1
        : a.competence < b.competence
          ? 1
          : -1,
    );
    return HttpResponse.json(paginate(sorted, url));
  }),

  http.get('*/api/v1/production/records/:recordId', async ({ request, params }) => {
    await delay();
    const purpose = requirePurpose(request);
    if (purpose instanceof Response) return purpose;
    const record = productionRecords.find((r) => r.id === params.recordId);
    if (!record) return problem(404, 'Registro não encontrado');
    return HttpResponse.json(record);
  }),

  http.post<PathParams, DefaultBodyType>(
    '*/api/v1/production/records/:recordId/corrections',
    async ({ request, params }) => {
      await delay();
      const record = productionRecords.find((r) => r.id === params.recordId);
      if (!record) return problem(404, 'Registro não encontrado');
      const body = await readJson<ProductionCorrection>(request);
      const justification = body?.justification?.trim() ?? '';
      if (justification.length < 10 || justification.length > 1000)
        return problem(422, 'Justificativa inválida', 'Informe entre 10 e 1000 caracteres.', [
          { field: 'justification', message: 'Entre 10 e 1000 caracteres' },
        ]);
      if (!CORRECTABLE.includes(record.status))
        return problem(
          409,
          'Registro não corrigível',
          'Registro já exportado/transmitido: corrija no sistema oficial.',
        );
      const waive = body?.waive_issue_ids ?? [];
      for (const id of waive) {
        const target = (record.issues ?? []).find((i) => i.id === id);
        if (!target)
          return problem(422, 'Pendência inexistente', `Pendência ${id} não pertence ao registro.`);
        if (target.severity === 'error')
          return problem(422, 'Erro não dispensável', 'Somente avisos podem ser dispensados.');
      }
      const changes = (body?.changes ?? {}) as Record<string, unknown>;
      const changed: string[] = [];
      const rec = record as unknown as Record<string, unknown>;
      for (const [k, v] of Object.entries(changes)) {
        if (v === undefined || v === null || v === '') continue;
        if (k === 'professional_cns') {
          const digits = typeof v === 'string' ? v.replace(/\D/g, '') : '';
          record.professional_cns_masked = `*** **** **** ${digits.slice(-4)}`;
          changed.push(k);
          continue;
        }
        if (k === 'citizen_ref') {
          record.citizen_identifier_masked = '*** **** **** 0000';
          changed.push(k);
          continue;
        }
        rec[k] = v;
        changed.push(k);
      }
      if (changed.includes('quantity') && record.unit_value !== undefined)
        record.estimated_value = Math.round(record.unit_value * record.quantity * 100) / 100;
      for (const i of record.issues ?? []) {
        if (waive.includes(i.id) && i.status === 'open') {
          i.status = 'waived';
          i.resolved_at = nowIso();
          i.resolution_note = justification;
        }
      }
      const from = revalidate(record, changed);
      record.correction_count = (record.correction_count ?? 0) + 1;
      record.version = (record.version ?? 1) + 1;
      record.history = [
        ...(record.history ?? []),
        {
          action: waive.length > 0 && changed.length === 0 ? 'issue_waived' : 'corrected',
          from_status: from,
          to_status: record.status,
          actor_id: MOCK_ACTOR,
          justification,
          rule_version: PRODUCTION_RULE_VERSION,
          occurred_at: nowIso(),
        },
      ];
      return HttpResponse.json(record);
    },
  ),

  http.get('*/api/v1/production/issues', async ({ request }) => {
    await delay();
    const url = new URL(request.url);
    const q = (k: string) => url.searchParams.get(k);
    const status = q('status') ?? 'open';
    let list = allIssues().filter((i) => i.status === status);
    if (q('severity')) list = list.filter((i) => i.severity === q('severity'));
    if (q('rule')) list = list.filter((i) => i.rule_id === q('rule'));
    if (q('competence')) list = list.filter((i) => i.competence === q('competence'));
    if (q('cnes')) list = list.filter((i) => i.cnes === q('cnes'));
    if (q('kind')) list = list.filter((i) => i.kind === q('kind'));
    if (q('record_id')) list = list.filter((i) => i.production_record_id === q('record_id'));
    const sorted = [...list].sort((a, b) =>
      a.severity === b.severity
        ? a.created_at < b.created_at
          ? -1
          : 1
        : a.severity === 'error'
          ? -1
          : 1,
    );
    return HttpResponse.json(paginate(sorted, url));
  }),

  http.get('*/api/v1/production/batches', async ({ request }) => {
    await delay();
    const url = new URL(request.url);
    const q = (k: string) => url.searchParams.get(k);
    let list = productionBatches;
    if (q('competence')) list = list.filter((b) => b.competence === q('competence'));
    if (q('cnes')) list = list.filter((b) => b.cnes === q('cnes'));
    if (q('status')) list = list.filter((b) => b.status === q('status'));
    const sorted = [...list].sort((a, b) => (a.created_at < b.created_at ? 1 : -1));
    return HttpResponse.json(paginate(sorted, url));
  }),

  http.post<PathParams, DefaultBodyType>('*/api/v1/production/batches', async ({ request }) => {
    await delay();
    const body = await readJson<ProductionBatchCreate>(request);
    if (!body?.competence || !COMPETENCE_RE.test(body.competence) || !body.cnes || !body.kind)
      return problem(400, 'Requisição inválida', 'Informe competência, CNES e instrumento.');
    const eligible = productionRecords.filter(
      (r) =>
        r.status === 'validated' &&
        !r.batch_id &&
        r.competence === body.competence &&
        r.cnes === body.cnes &&
        r.kind === body.kind,
    );
    if (eligible.length === 0)
      return problem(
        422,
        'Sem registros elegíveis',
        'Nenhum registro validado fora de lote para a competência/unidade/instrumento.',
      );
    const batch: ProductionBatch = {
      id: newBatchId(),
      competence: body.competence,
      cnes: body.cnes,
      kind: body.kind,
      status: 'draft',
      records_count: eligible.length,
      total_quantity: eligible.reduce((s, r) => s + r.quantity, 0),
      estimated_value:
        Math.round(eligible.reduce((s, r) => s + (r.estimated_value ?? 0), 0) * 100) / 100,
      record_ids: eligible.map((r) => r.id),
      created_by: MOCK_ACTOR,
      created_at: nowIso(),
      version: 1,
    };
    for (const r of eligible) r.batch_id = batch.id;
    productionBatches.unshift(batch);
    return HttpResponse.json(batch, { status: 201 });
  }),

  http.get('*/api/v1/production/batches/:batchId', async ({ params }) => {
    await delay();
    const batch = productionBatches.find((b) => b.id === params.batchId);
    if (!batch) return problem(404, 'Lote não encontrado');
    return HttpResponse.json(batch);
  }),

  http.post<PathParams, DefaultBodyType>(
    '*/api/v1/production/batches/:batchId/approve',
    async ({ request, params }) => {
      await delay();
      const batch = productionBatches.find((b) => b.id === params.batchId);
      if (!batch) return problem(404, 'Lote não encontrado');
      const body = await readJson<{ justification?: string }>(request);
      const justification = body?.justification?.trim() ?? '';
      if (justification.length < 10 || justification.length > 1000)
        return problem(422, 'Justificativa inválida', 'Informe entre 10 e 1000 caracteres.');
      if (batch.status !== 'draft')
        return problem(
          409,
          'Lote fora de rascunho',
          'Somente lotes em rascunho podem ser aprovados.',
        );
      batch.status = 'approved';
      batch.approved_by = MOCK_ACTOR;
      batch.approved_at = nowIso();
      batch.approval_justification = justification;
      batch.version = (batch.version ?? 1) + 1;
      return HttpResponse.json(batch);
    },
  ),

  http.post<PathParams, DefaultBodyType>(
    '*/api/v1/production/batches/:batchId/export',
    async ({ request, params }) => {
      await delay();
      const batch = productionBatches.find((b) => b.id === params.batchId);
      if (!batch) return problem(404, 'Lote não encontrado');
      if (batch.status !== 'approved')
        return problem(409, 'Lote não aprovado', 'Exportação exige lote aprovado.');
      const body = await readJson<{ layout?: ProductionExportLayout }>(request);
      const layout =
        body?.layout ??
        (batch.kind === 'bpa_c' || batch.kind === 'bpa_i' ? 'bpa_mag_ref_v1' : 'csv_ref_v1');
      if (layout === 'bpa_mag_ref_v1' && batch.kind !== 'bpa_c' && batch.kind !== 'bpa_i')
        return problem(422, 'Layout incompatível', 'BPA-Mag aceita apenas BPA-C/BPA-I.');
      const ext = layout === 'csv_ref_v1' ? 'csv' : 'txt';
      batch.status = 'exported';
      batch.export = {
        layout,
        file_ref: `exports/ibge_3143302/${batch.competence}/${batch.kind}_${batch.cnes}_${batch.id}.${ext}`,
        sha256: fakeSha256(batch.id + layout),
        size_bytes: 330 * (batch.records_count + 1),
        lines: batch.records_count + 1,
        lines_missing_identifiers: batch.kind === 'bpa_c' ? 0 : 1,
        exported_by: MOCK_ACTOR,
        exported_at: nowIso(),
      };
      batch.version = (batch.version ?? 1) + 1;
      for (const r of productionRecords) if (r.batch_id === batch.id) r.status = 'exported';
      return HttpResponse.json(batch);
    },
  ),

  http.post<PathParams, DefaultBodyType>('*/api/v1/production/outcomes', async ({ request }) => {
    await delay();
    const body = await readJson<ProductionOutcomeRegistration>(request);
    if (!body?.source?.source_record_id || !body.outcome || !body.processed_at)
      return problem(
        400,
        'Requisição inválida',
        'Informe origem, retorno e data de processamento.',
      );
    const scopes = [body.production_record_id, body.record_source, body.batch_id].filter(Boolean);
    if (scopes.length !== 1)
      return problem(
        422,
        'Escopo inválido',
        'Informe exatamente um entre registro, origem e lote.',
      );
    if ((body.outcome === 'rejected' || body.outcome === 'paid') && body.batch_id)
      return problem(422, 'Escopo inválido', 'Rejeição e pagamento exigem registro.');
    const targets = body.batch_id
      ? productionRecords.filter((r) => r.batch_id === body.batch_id)
      : productionRecords.filter((r) => r.id === body.production_record_id);
    if (targets.length === 0) return problem(404, 'Registro ou lote não encontrado');
    const status: ProductionRecord['status'] =
      body.outcome === 'accepted' ? 'approved' : body.outcome;
    for (const r of targets) {
      const from = r.status;
      r.status = status;
      if (body.outcome === 'paid') {
        r.paid_amount = body.paid_amount;
        r.approved_quantity = body.approved_quantity;
      }
      if (body.outcome === 'rejected') {
        r.outcome_reason_code = body.reason_code;
        r.outcome_reason = body.reason;
        r.issues = [
          ...(r.issues ?? []),
          {
            id: newIssueId(),
            production_record_id: r.id,
            rule_id: 'official_rejection',
            rule_version: PRODUCTION_RULE_VERSION,
            severity: 'error',
            message: `Rejeitado no sistema oficial: ${body.reason ?? 'motivo não informado'}${body.reason_code ? ` (motivo ${body.reason_code})` : ''}.`,
            status: 'open',
            origin: 'official_return',
            created_at: nowIso(),
            competence: r.competence,
            cnes: r.cnes,
            kind: r.kind,
            procedure_code: r.procedure_code,
          },
        ];
      }
      r.history = [
        ...(r.history ?? []),
        {
          action: `outcome_${body.outcome}`,
          from_status: from,
          to_status: r.status,
          actor_id: MOCK_ACTOR,
          justification: body.reason,
          occurred_at: body.processed_at,
        },
      ];
    }
    if (body.batch_id) {
      const batch = productionBatches.find((b) => b.id === body.batch_id);
      if (batch) {
        batch.status = body.outcome === 'transmitted' ? 'transmitted' : 'processed';
        if (body.protocol_number) batch.protocol_number = body.protocol_number;
      }
    }
    const result: ProductionOutcomeResult = {
      outcome: body.outcome,
      batch_id: body.batch_id,
      unchanged: false,
      affected: targets.map((r) => ({ production_record_id: r.id, status: r.status })),
    };
    return HttpResponse.json(result);
  }),

  http.get('*/api/v1/production/summary', async ({ request }) => {
    await delay();
    const url = new URL(request.url);
    const competence = url.searchParams.get('competence');
    if (!competence || !COMPETENCE_RE.test(competence))
      return problem(400, 'Competência obrigatória', 'Informe a competência no formato AAAAMM.');
    return HttpResponse.json(computeSummary(competence, url.searchParams.get('cnes')));
  }),

  http.get('*/api/v1/production/deadlines', async ({ request }) => {
    await delay();
    const url = new URL(request.url);
    const from = url.searchParams.get('from');
    const to = url.searchParams.get('to');
    const items = deadlinesWithCounts().filter(
      (d) => (!from || d.competence >= from) && (!to || d.competence <= to),
    );
    return HttpResponse.json({ items });
  }),
];
