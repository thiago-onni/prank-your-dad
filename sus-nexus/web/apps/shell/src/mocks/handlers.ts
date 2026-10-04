import { HttpResponse, http, type DefaultBodyType, type PathParams } from 'msw';
import type {
  KillSwitchState,
  MergeCase,
  RegulationIssue,
  RegulationQueueGroupBy,
  Task,
  TaskStatus,
  ToolCallRecord,
} from '@sus-nexus/api-client';
import {
  aiAgents,
  aiApprovals,
  aiRuns,
  aiTools,
  killSwitchAdmin,
  killSwitchEnv,
  mergeKillSwitch,
} from './ai-data';
import { examOrders, signedDocument } from './exams-data';
import { providerCapacity, queueSummary, regulationRequests } from './regulation-data';
import {
  citizens,
  connectors,
  deadLetters,
  fakeRevealValue,
  healthUnits,
  integrationMessages,
  mergeCases,
  reconciliation,
  ruleSets,
  summaries,
  tasks,
  timelineByCitizen,
  toSummary,
} from './data';
import { careHandlers } from './care-handlers';
import { productionHandlers } from './production-handlers';
import { delay, normalize, paginate, problem, readJson, requirePurpose } from './http-utils';

/**
 * Handlers MSW para todos os endpoints usados pelo shell. Mantêm estado em memória
 * (reprocessar, fundir, rejeitar, transições de tarefa) para uma demonstração realista.
 * O padrão `*` casa com qualquer base (`http://localhost:8080`, `/api/core`, ...).
 */

export const handlers = [
  // ---------- citizens ----------
  http.get('*/api/v1/citizens', async ({ request }) => {
    await delay();
    const purpose = requirePurpose(request);
    if (purpose instanceof Response) return purpose;
    const url = new URL(request.url);
    const q = url.searchParams.get('q');
    const identifier = url.searchParams.get('identifier');
    const birthdate = url.searchParams.get('birthdate');
    const state = url.searchParams.get('registration_state');
    let result = citizens;
    if (q) {
      const nq = normalize(q);
      result = result.filter(
        (c) =>
          normalize(c.legal_name ?? '').includes(nq) ||
          normalize(c.social_name ?? '').includes(nq) ||
          normalize(c.mother_name ?? '').includes(nq),
      );
    }
    if (identifier) {
      const [system, value] = identifier.split('|');
      if (!system || !value)
        return problem(400, 'Identificador inválido', 'Use o formato system|value.');
      // No mock, casa pelos últimos dígitos do valor mascarado.
      const tail = system === 'CPF' ? value.slice(-2) : value.slice(-4);
      result = result.filter((c) =>
        c.identifiers.some((i) => i.system === system && i.value_masked.endsWith(tail)),
      );
    }
    if (birthdate) result = result.filter((c) => c.birthdate === birthdate);
    if (state) result = result.filter((c) => c.registration_state === state);
    return HttpResponse.json(paginate(result.map(toSummary), url));
  }),

  http.get('*/api/v1/citizens/:citizenId', async ({ request, params }) => {
    await delay();
    const purpose = requirePurpose(request);
    if (purpose instanceof Response) return purpose;
    const c = citizens.find((x) => x.id === params.citizenId);
    if (!c) return problem(404, 'Cidadão não encontrado');
    return HttpResponse.json(c, { headers: { ETag: `"${c.version}"` } });
  }),

  http.get('*/api/v1/citizens/:citizenId/summary', async ({ request, params }) => {
    await delay();
    const purpose = requirePurpose(request);
    if (purpose instanceof Response) return purpose;
    const s = summaries.get(String(params.citizenId));
    if (!s) return problem(404, 'Cidadão não encontrado');
    return HttpResponse.json(s);
  }),

  http.get('*/api/v1/citizens/:citizenId/timeline', async ({ request, params }) => {
    await delay();
    const purpose = requirePurpose(request);
    if (purpose instanceof Response) return purpose;
    const url = new URL(request.url);
    let events = timelineByCitizen.get(String(params.citizenId));
    if (!events) return problem(404, 'Cidadão não encontrado');
    const from = url.searchParams.get('from');
    const to = url.searchParams.get('to');
    const domains = url.searchParams.get('domain')?.split(',').filter(Boolean);
    const cnes = url.searchParams.get('cnes');
    const status = url.searchParams.get('status');
    if (from) events = events.filter((e) => e.occurred_at >= from);
    if (to) events = events.filter((e) => e.occurred_at <= to);
    if (domains && domains.length > 0) events = events.filter((e) => domains.includes(e.domain));
    if (cnes) events = events.filter((e) => e.cnes === cnes);
    if (status) events = events.filter((e) => e.status === status || e.confidence === status);
    return HttpResponse.json(paginate(events, url));
  }),

  http.post<PathParams, { purpose?: string; justification?: string }>(
    '*/api/v1/citizens/:citizenId/identifiers/:identifierId/reveal',
    async ({ request, params }) => {
      await delay();
      const body = await readJson<{ purpose?: string; justification?: string }>(request);
      if (!body?.purpose || !body.justification || body.justification.length < 10) {
        return problem(
          422,
          'Requisição inválida',
          'Finalidade e justificativa (mín. 10 caracteres) são obrigatórias.',
          [{ field: 'justification', message: 'mínimo 10 caracteres' }],
        );
      }
      const c = citizens.find((x) => x.id === params.citizenId);
      const ident = c?.identifiers.find((i) => i.id === params.identifierId);
      if (!c || !ident) return problem(404, 'Identificador não encontrado');
      return HttpResponse.json({
        system: ident.system,
        value: fakeRevealValue(ident.system, ident.value_masked),
      });
    },
  ),

  // ---------- mpi ----------
  http.get('*/api/v1/mpi/cases', async ({ request }) => {
    await delay();
    const url = new URL(request.url);
    const status = url.searchParams.get('status');
    const list = status ? mergeCases.filter((c) => c.status === status) : mergeCases;
    return HttpResponse.json(paginate(list, url));
  }),

  http.get('*/api/v1/mpi/cases/:caseId', async ({ params }) => {
    await delay();
    const c = mergeCases.find((x) => x.id === params.caseId);
    return c ? HttpResponse.json(c) : problem(404, 'Caso não encontrado');
  }),

  http.post<PathParams, DefaultBodyType>(
    '*/api/v1/mpi/cases/:caseId/merge',
    async ({ request, params }) => {
      await delay();
      const body = await readJson<{ surviving_citizen_id?: string; reason?: string }>(request);
      const c = mergeCases.find((x) => x.id === params.caseId);
      if (!c) return problem(404, 'Caso não encontrado');
      if (c.status !== 'open' && c.status !== 'in_review') return problem(409, 'Caso já decidido');
      if (!body?.reason || body.reason.length < 10)
        return problem(422, 'Justificativa obrigatória (mín. 10 caracteres)');
      if (!c.candidates.some((cand) => cand.id === body.surviving_citizen_id)) {
        return problem(
          422,
          'Cadastro sobrevivente inválido',
          'Deve ser um dos candidatos do caso.',
        );
      }
      Object.assign(c, {
        status: 'merged',
        decided_at: new Date().toISOString(),
        decided_by: 'user_mock',
        decision_reason: body.reason,
        merge_id: `merge_${Date.now()}`,
      } satisfies Partial<MergeCase>);
      return HttpResponse.json(c);
    },
  ),

  http.post<PathParams, DefaultBodyType>(
    '*/api/v1/mpi/cases/:caseId/reject',
    async ({ request, params }) => {
      await delay();
      const body = await readJson<{ reason?: string }>(request);
      const c = mergeCases.find((x) => x.id === params.caseId);
      if (!c) return problem(404, 'Caso não encontrado');
      if (c.status !== 'open' && c.status !== 'in_review') return problem(409, 'Caso já decidido');
      if (!body?.reason || body.reason.length < 10)
        return problem(422, 'Justificativa obrigatória (mín. 10 caracteres)');
      Object.assign(c, {
        status: 'rejected',
        decided_at: new Date().toISOString(),
        decided_by: 'user_mock',
        decision_reason: body.reason,
      });
      return HttpResponse.json(c);
    },
  ),

  http.post('*/api/v1/mpi/merges/:mergeId/unmerge', async ({ params }) => {
    await delay();
    const c = mergeCases.find((x) => x.merge_id === params.mergeId);
    if (!c) return problem(404, 'Fusão não encontrada');
    Object.assign(c, { status: 'unmerged', decided_at: new Date().toISOString() });
    return HttpResponse.json(c);
  }),

  // ---------- tasks ----------
  http.get('*/api/v1/tasks', async ({ request }) => {
    await delay();
    const url = new URL(request.url);
    const p = url.searchParams;
    let list = tasks;
    const status = p.get('status');
    if (status) list = list.filter((t) => t.status === status);
    const type = p.get('task_type');
    if (type) list = list.filter((t) => t.task_type === type);
    const assigneeKind = p.get('assignee_kind');
    if (assigneeKind) list = list.filter((t) => t.assignee?.kind === assigneeKind);
    const assigneeId = p.get('assignee_id');
    if (assigneeId) list = list.filter((t) => t.assignee?.id === assigneeId);
    const citizenId = p.get('citizen_id');
    if (citizenId) list = list.filter((t) => t.citizen_id === citizenId);
    if (p.get('overdue') === 'true') list = list.filter((t) => t.overdue);
    const sorted = [...list].sort((a, b) => ((a.due_at ?? '') < (b.due_at ?? '') ? -1 : 1));
    return HttpResponse.json(paginate(sorted, url));
  }),

  http.get('*/api/v1/tasks/:taskId', async ({ params }) => {
    await delay();
    const t = tasks.find((x) => x.id === params.taskId);
    return t ? HttpResponse.json(t) : problem(404, 'Tarefa não encontrada');
  }),

  http.post<PathParams, DefaultBodyType>('*/api/v1/tasks', async ({ request }) => {
    await delay();
    const body = await readJson<Partial<Task>>(request);
    if (!body?.task_type || !body.title || !body.priority)
      return problem(422, 'Campos obrigatórios ausentes');
    const t: Task = {
      id: `task_${Date.now()}`,
      task_type: body.task_type,
      status: 'open',
      priority: body.priority,
      title: body.title,
      description: body.description,
      citizen_id: body.citizen_id,
      assignee: body.assignee,
      due_at: body.due_at,
      sla_policy_id: body.sla_policy_id,
      overdue: false,
      origin: body.origin ?? { kind: 'user', id: 'user_mock' },
      created_at: new Date().toISOString(),
      updated_at: new Date().toISOString(),
      version: 1,
    };
    tasks.unshift(t);
    return HttpResponse.json(t, { status: 201 });
  }),

  http.post<PathParams, DefaultBodyType>(
    '*/api/v1/tasks/:taskId/transition',
    async ({ request, params }) => {
      await delay();
      const body = await readJson<{
        action?: string;
        assignee?: Task['assignee'];
        outcome?: string;
        reason?: string;
      }>(request);
      const t = tasks.find((x) => x.id === params.taskId);
      if (!t) return problem(404, 'Tarefa não encontrada');
      const transitions: Record<string, { from: TaskStatus[]; to: TaskStatus }> = {
        assign: { from: ['open', 'assigned', 'escalated'], to: 'assigned' },
        start: { from: ['open', 'assigned', 'escalated'], to: 'in_progress' },
        complete: { from: ['assigned', 'in_progress', 'escalated'], to: 'completed' },
        cancel: { from: ['open', 'assigned', 'in_progress', 'escalated'], to: 'cancelled' },
        escalate: { from: ['open', 'assigned', 'in_progress'], to: 'escalated' },
      };
      const tr = body?.action ? transitions[body.action] : undefined;
      if (!tr) return problem(422, 'Ação inválida');
      if (!tr.from.includes(t.status))
        return problem(
          409,
          'Transição inválida',
          `Tarefa em "${t.status}" não aceita "${body?.action}".`,
        );
      if ((body?.action === 'cancel' || body?.action === 'escalate') && !body.reason) {
        return problem(422, 'Motivo obrigatório', 'Cancelar/escalonar exige motivo.');
      }
      t.status = tr.to;
      if (body?.assignee) t.assignee = body.assignee;
      if (body?.action === 'assign' && !body.assignee)
        t.assignee = { kind: 'user', id: 'user_mock' };
      if (body?.outcome) t.outcome = body.outcome;
      if (t.status === 'completed' || t.status === 'cancelled') t.overdue = false;
      t.updated_at = new Date().toISOString();
      t.version = (t.version ?? 1) + 1;
      return HttpResponse.json(t);
    },
  ),

  // ---------- integration ----------
  http.get('*/api/v1/integration/connectors', async () => {
    await delay();
    return HttpResponse.json(connectors);
  }),

  http.get('*/api/v1/integration/messages', async ({ request }) => {
    await delay();
    const url = new URL(request.url);
    const p = url.searchParams;
    let list = integrationMessages;
    const connector = p.get('connector_id');
    if (connector) list = list.filter((m) => m.connector_id === connector);
    const status = p.get('status');
    if (status) list = list.filter((m) => m.status === status);
    const from = p.get('from');
    if (from) list = list.filter((m) => m.received_at >= from);
    const to = p.get('to');
    if (to) list = list.filter((m) => m.received_at <= to);
    return HttpResponse.json(paginate(list, url));
  }),

  http.get('*/api/v1/integration/messages/:messageId', async ({ params }) => {
    await delay();
    const m = integrationMessages.find((x) => x.id === params.messageId);
    return m ? HttpResponse.json(m) : problem(404, 'Mensagem não encontrada');
  }),

  http.post('*/api/v1/integration/messages/:messageId/reprocess', async ({ params }) => {
    await delay();
    const m = integrationMessages.find((x) => x.id === params.messageId);
    if (!m) return problem(404, 'Mensagem não encontrada');
    if (m.status === 'reprocessing') return problem(409, 'Já em reprocessamento');
    m.status = 'reprocessing';
    m.attempts = (m.attempts ?? 0) + 1;
    // Simula conclusão assíncrona.
    setTimeout(() => {
      m.status = 'processed';
      m.processed_at = new Date().toISOString();
      m.last_error = undefined;
    }, 4000);
    return new HttpResponse(null, { status: 202 });
  }),

  http.get('*/api/v1/integration/dlq', async ({ request }) => {
    await delay();
    return HttpResponse.json(paginate(deadLetters, new URL(request.url)));
  }),

  http.get('*/api/v1/integration/reconciliation', async ({ request }) => {
    await delay();
    const url = new URL(request.url);
    const connector = url.searchParams.get('connector_id');
    const list = connector
      ? reconciliation.filter((r) => r.connector_id === connector)
      : reconciliation;
    return HttpResponse.json(paginate(list, url));
  }),

  // ---------- appointments ----------
  http.get('*/api/v1/appointments', async ({ request }) => {
    await delay();
    return HttpResponse.json(paginate([], new URL(request.url)));
  }),

  http.get('*/api/v1/appointments/duplicates', async ({ request }) => {
    await delay();
    return HttpResponse.json(paginate([], new URL(request.url)));
  }),

  // ---------- reference / terminology / audit / admin ----------
  http.get('*/api/v1/reference/health-units', async ({ request }) => {
    await delay();
    const url = new URL(request.url);
    const q = url.searchParams.get('q');
    const cnes = url.searchParams.get('cnes');
    let list = healthUnits;
    if (q) list = list.filter((h) => normalize(h.name).includes(normalize(q)));
    if (cnes) list = list.filter((h) => h.cnes === cnes);
    return HttpResponse.json(paginate(list, url));
  }),

  http.get('*/api/v1/terminology/:system/codes', async ({ request, params }) => {
    await delay();
    const url = new URL(request.url);
    const q = normalize(url.searchParams.get('q') ?? '');
    const sample = [
      {
        system: String(params.system),
        code: '0301010072',
        display: 'Consulta médica em atenção primária',
      },
      {
        system: String(params.system),
        code: '0211020036',
        display: 'Ecocardiografia transtorácica',
      },
      { system: String(params.system), code: '0202020380', display: 'Hemograma completo' },
    ];
    return HttpResponse.json(
      paginate(
        sample.filter((c) => !q || normalize(c.display).includes(q) || c.code.includes(q)),
        url,
      ),
    );
  }),

  http.get('*/api/v1/audit/access', async ({ request }) => {
    await delay();
    return HttpResponse.json(paginate([], new URL(request.url)));
  }),

  http.get('*/api/v1/admin/rules', async () => {
    await delay();
    return HttpResponse.json(ruleSets);
  }),

  // ---------- regulation ----------
  http.get('*/api/v1/regulation/requests', async ({ request }) => {
    await delay();
    const purpose = requirePurpose(request);
    if (purpose instanceof Response) return purpose;
    const url = new URL(request.url);
    const p = url.searchParams;
    let list = regulationRequests;
    const status = p.get('status');
    if (status) list = list.filter((r) => r.status === status);
    const priority = p.get('priority');
    if (priority) list = list.filter((r) => r.priority === priority);
    const specialty = p.get('specialty');
    if (specialty) list = list.filter((r) => normalize(r.specialty ?? '') === normalize(specialty));
    const service = p.get('service_code');
    if (service) list = list.filter((r) => r.requested_service_code === service);
    const reqCnes = p.get('requesting_cnes');
    if (reqCnes) list = list.filter((r) => r.requesting_cnes === reqCnes);
    const provCnes = p.get('provider_cnes');
    if (provCnes) list = list.filter((r) => r.provider_cnes === provCnes);
    const citizenId = p.get('citizen_id');
    if (citizenId) list = list.filter((r) => r.citizen_id === citizenId);
    const issue = p.get('issue');
    if (issue) {
      const openKinds = (r: (typeof regulationRequests)[number]) =>
        (r.issues ?? []).filter((i) => i.status === 'open').map((i) => i.kind);
      list = list.filter((r) => {
        switch (issue) {
          case 'incomplete':
            return (
              r.status === 'pending_documents' ||
              openKinds(r).some((k) => k === 'missing_document' || k === 'missing_field')
            );
          case 'returned':
            return r.status === 'returned';
          case 'expired':
            return r.status === 'expired' || openKinds(r).includes('expired');
          case 'duplicate':
            return openKinds(r).includes('duplicate');
          case 'no_capacity':
            return openKinds(r).includes('no_capacity');
          case 'sla_breached':
            return Boolean(r.sla_breached);
          default:
            return true;
        }
      });
    }
    const sort = p.get('sort') ?? 'waiting_time_desc';
    const rank: Record<string, number> = { emergency: 0, urgent: 1, priority: 2, elective: 3 };
    const sorted = [...list].sort((a, b) => {
      if (sort === 'priority_desc') {
        const d = (rank[a.priority ?? 'elective'] ?? 3) - (rank[b.priority ?? 'elective'] ?? 3);
        return d !== 0 ? d : b.waiting_days - a.waiting_days;
      }
      if (sort === 'created_at_asc') return a.requested_at < b.requested_at ? -1 : 1;
      return b.waiting_days - a.waiting_days;
    });
    return HttpResponse.json(paginate(sorted, url));
  }),

  http.get('*/api/v1/regulation/requests/:requestId', async ({ request, params }) => {
    await delay();
    const purpose = requirePurpose(request);
    if (purpose instanceof Response) return purpose;
    const r = regulationRequests.find((x) => x.id === params.requestId);
    return r
      ? HttpResponse.json(r, { headers: { ETag: `"${r.version}"` } })
      : problem(404, 'Solicitação não encontrada');
  }),

  http.post<PathParams, DefaultBodyType>(
    '*/api/v1/regulation/requests/:requestId/issues',
    async ({ request, params }) => {
      await delay();
      const r = regulationRequests.find((x) => x.id === params.requestId);
      if (!r) return problem(404, 'Solicitação não encontrada');
      const body = await readJson<{
        kind?: RegulationIssue['kind'];
        description?: string;
        origin?: RegulationIssue['origin'];
      }>(request);
      const kinds: RegulationIssue['kind'][] = [
        'missing_document',
        'missing_field',
        'clinical_justification',
        'duplicate',
        'other',
      ];
      if (!body?.kind || !kinds.includes(body.kind))
        return problem(422, 'Tipo de pendência inválido', undefined, [
          { field: 'kind', message: 'valor inválido' },
        ]);
      if (!body.description || body.description.trim().length < 10)
        return problem(422, 'Descrição obrigatória (mín. 10 caracteres)', undefined, [
          { field: 'description', message: 'mínimo 10 caracteres' },
        ]);
      const issue: RegulationIssue = {
        id: `iss_${Date.now()}`,
        kind: body.kind,
        description: body.description.trim(),
        status: 'open',
        created_at: new Date().toISOString(),
        origin: body.origin ?? { kind: 'user', id: 'user_mock' },
      };
      r.issues = [issue, ...(r.issues ?? [])];
      if (r.status === 'requested' || r.status === 'under_review') r.status = 'pending_documents';
      r.version = (r.version ?? 1) + 1;
      return HttpResponse.json(r, { status: 201 });
    },
  ),

  http.get('*/api/v1/regulation/capacity', async ({ request }) => {
    await delay();
    const url = new URL(request.url);
    const p = url.searchParams;
    let list = providerCapacity;
    const prov = p.get('provider_cnes');
    if (prov) list = list.filter((c) => c.provider_cnes === prov);
    const service = p.get('service_code');
    if (service) list = list.filter((c) => c.service_code === service);
    const competence = p.get('competence');
    if (competence) list = list.filter((c) => c.competence === competence);
    const sorted = [...list].sort((a, b) =>
      a.competence === b.competence
        ? (a.provider_name ?? '').localeCompare(b.provider_name ?? '')
        : a.competence < b.competence
          ? 1
          : -1,
    );
    return HttpResponse.json(paginate(sorted, url));
  }),

  http.get('*/api/v1/regulation/queues/summary', async ({ request }) => {
    await delay();
    const url = new URL(request.url);
    const groupBy = (url.searchParams.get('group_by') ?? 'specialty') as RegulationQueueGroupBy;
    const allowed: RegulationQueueGroupBy[] = [
      'service_code',
      'specialty',
      'provider_cnes',
      'requesting_cnes',
      'priority',
    ];
    if (!allowed.includes(groupBy)) return problem(400, 'group_by inválido');
    return HttpResponse.json({ items: queueSummary(groupBy) });
  }),

  // ---------- exams ----------
  http.get('*/api/v1/exams/orders', async ({ request }) => {
    await delay();
    const purpose = requirePurpose(request);
    if (purpose instanceof Response) return purpose;
    const url = new URL(request.url);
    const p = url.searchParams;
    let list = examOrders;
    const status = p.get('status');
    if (status) list = list.filter((o) => o.status === status);
    const issue = p.get('issue');
    if (issue) list = list.filter((o) => (o.issues ?? []).some((i) => i === issue));
    const citizenId = p.get('citizen_id');
    if (citizenId) list = list.filter((o) => o.citizen_id === citizenId);
    const cnes = p.get('requesting_cnes');
    if (cnes) list = list.filter((o) => o.requesting_cnes === cnes);
    const sorted = [...list].sort((a, b) => (a.requested_at < b.requested_at ? 1 : -1));
    return HttpResponse.json(paginate(sorted, url));
  }),

  http.get('*/api/v1/exams/orders/:orderId', async ({ request, params }) => {
    await delay();
    const purpose = requirePurpose(request);
    if (purpose instanceof Response) return purpose;
    const o = examOrders.find((x) => x.id === params.orderId);
    return o ? HttpResponse.json(o) : problem(404, 'Pedido de exame não encontrado');
  }),

  http.get(
    '*/api/v1/exams/orders/:orderId/results/:resultId/document',
    async ({ request, params }) => {
      await delay();
      const purpose = requirePurpose(request);
      if (purpose instanceof Response) return purpose;
      const o = examOrders.find((x) => x.id === params.orderId);
      const r = o?.results?.find((x) => x.id === params.resultId);
      if (!o || !r) return problem(404, 'Resultado não encontrado');
      if (!r.has_document) return problem(404, 'Resultado sem documento vinculado');
      // No core real: gera access_log (quem, quando, finalidade) e URL assinada curta.
      return HttpResponse.json(signedDocument(r.id));
    },
  ),

  // ---------- hospital / plano de cuidado / lacunas / protocolos ----------
  ...careHandlers,
  ...productionHandlers,

  // ---------- ai-service ----------
  http.get('*/agents', async () => {
    await delay();
    return HttpResponse.json(aiAgents);
  }),

  http.get('*/tools', async () => {
    await delay();
    return HttpResponse.json(aiTools);
  }),

  http.get('*/runs', async ({ request }) => {
    await delay();
    const url = new URL(request.url);
    const agentId = url.searchParams.get('agent_id');
    const status = url.searchParams.get('status');
    const limit = Math.min(Number(url.searchParams.get('limit') ?? 50), 200);
    let list = aiRuns;
    if (agentId) list = list.filter((r) => r.agent_id === agentId);
    if (status) list = list.filter((r) => r.status === status);
    return HttpResponse.json(list.slice(0, limit));
  }),

  http.get('*/runs/:runId', async ({ params }) => {
    await delay();
    const r = aiRuns.find((x) => x.id === params.runId);
    return r ? HttpResponse.json(r) : problem(404, 'run não encontrado');
  }),

  ...(['approve', 'reject'] as const).map((decision) =>
    http.post<PathParams, DefaultBodyType>(
      `*/runs/:runId/actions/:actionId/${decision}`,
      async ({ request, params }) => {
        await delay();
        const body = await readJson<{ justification?: string }>(request);
        const just = body?.justification?.trim() ?? '';
        if (just.length < 10 || just.length > 1000)
          return problem(422, 'Unprocessable Entity', 'justification: 10–1000 caracteres');
        const run = aiRuns.find((x) => x.id === params.runId);
        if (!run) return problem(404, 'run não encontrado');
        const action = run.actions.find((a) => a.id === params.actionId);
        if (!action) return problem(404, 'ação não encontrada');
        if (action.status !== 'pending_approval')
          return problem(409, 'Conflict', `ação em "${action.status}" não aceita decisão`);
        const now = new Date().toISOString();
        action.status = decision === 'approve' ? 'approved' : 'rejected';
        action.approver = 'user_mock';
        action.justification = just;
        action.decided_at = now;
        if (decision === 'approve') {
          action.result_hash = `sha256:${Date.now().toString(16)}`;
          const rec: ToolCallRecord = {
            tool: action.tool,
            status: 'executed',
            args_masked: action.args,
            decision: {
              allow: true,
              action_class: 'requires_approval',
              requires_approval: true,
              reasons: [],
            },
            result_hash: action.result_hash,
            approved_by: 'user_mock',
            called_at: now,
            duration_ms: 120,
          };
          run.tools_called = [...run.tools_called, rec];
        }
        const apr = aiApprovals.find((a) => a.action_id === action.id);
        if (apr) {
          apr.status = decision === 'approve' ? 'approved' : 'rejected';
          apr.approver = 'user_mock';
          apr.justification = just;
          apr.decided_at = now;
        }
        return HttpResponse.json(run);
      },
    ),
  ),

  http.get('*/approvals', async ({ request }) => {
    await delay();
    const url = new URL(request.url);
    const status = url.searchParams.get('status') ?? 'pending';
    const agentId = url.searchParams.get('agent_id');
    let list = aiApprovals.filter((a) => a.status === status);
    if (agentId) list = list.filter((a) => a.agent_id === agentId);
    return HttpResponse.json(list);
  }),

  http.get('*/admin/kill-switch', async () => {
    await delay();
    return HttpResponse.json({
      effective: mergeKillSwitch(killSwitchEnv, killSwitchAdmin),
      admin: killSwitchAdmin,
    });
  }),

  http.post<PathParams, DefaultBodyType>('*/admin/kill-switch', async ({ request }) => {
    await delay();
    const body = await readJson<Partial<KillSwitchState>>(request);
    const isList = (v: unknown): v is string[] =>
      Array.isArray(v) && v.every((x) => typeof x === 'string');
    if (
      !body ||
      (body.global !== undefined && typeof body.global !== 'boolean') ||
      (body.agents !== undefined && !isList(body.agents)) ||
      (body.tools !== undefined && !isList(body.tools)) ||
      (body.tenants !== undefined && !isList(body.tenants))
    ) {
      return problem(422, 'Unprocessable Entity', 'corpo inválido');
    }
    killSwitchAdmin.global = body.global ?? false;
    killSwitchAdmin.agents = [...(body.agents ?? [])].sort();
    killSwitchAdmin.tools = [...(body.tools ?? [])].sort();
    killSwitchAdmin.tenants = [...(body.tenants ?? [])].sort();
    return HttpResponse.json({
      effective: mergeKillSwitch(killSwitchEnv, killSwitchAdmin),
      admin: killSwitchAdmin,
    });
  }),
];
