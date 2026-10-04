import { HttpResponse, http, type DefaultBodyType, type PathParams } from 'msw';
import type { MergeCase, Purpose, Task, TaskStatus } from '@sus-nexus/api-client';
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

/**
 * Handlers MSW para todos os endpoints usados pelo shell. Mantêm estado em memória
 * (reprocessar, fundir, rejeitar, transições de tarefa) para uma demonstração realista.
 * O padrão `*` casa com qualquer base (`http://localhost:8080`, `/api/core`, ...).
 */

const PAGE_DEFAULT = 50;

function problem(
  status: number,
  title: string,
  detail?: string,
  errors?: { field: string; message: string }[],
) {
  return HttpResponse.json(
    {
      type: 'about:blank',
      title,
      status,
      detail,
      correlation_id: `corr-mock-${Date.now()}`,
      errors,
    },
    { status, headers: { 'content-type': 'application/problem+json' } },
  );
}

function requirePurpose(request: Request): Purpose | Response {
  const purpose = request.headers.get('X-Purpose-Of-Use');
  if (!purpose)
    return problem(400, 'Finalidade ausente', 'Cabeçalho X-Purpose-Of-Use é obrigatório.');
  return purpose as Purpose;
}

/** Paginação por cursor opaco (índice codificado em base64). */
function paginate<T>(items: T[], url: URL): { items: T[]; next_cursor: string | null } {
  const limit = Math.min(Number(url.searchParams.get('limit') ?? PAGE_DEFAULT), 200);
  const cursor = url.searchParams.get('cursor');
  const start = cursor ? Number(atob(cursor)) || 0 : 0;
  const page = items.slice(start, start + limit);
  const next = start + limit < items.length ? btoa(String(start + limit)) : null;
  return { items: page, next_cursor: next };
}

function normalize(s: string): string {
  return s.normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase();
}

async function readJson<T>(request: Request): Promise<T | undefined> {
  try {
    return (await request.json()) as T;
  } catch {
    return undefined;
  }
}

const delay = () => new Promise((r) => setTimeout(r, process.env.NODE_ENV === 'test' ? 0 : 150));

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
];
