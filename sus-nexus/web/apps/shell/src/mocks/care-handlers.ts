import { HttpResponse, http, type DefaultBodyType, type PathParams } from 'msw';
import type {
  CareGap,
  CareGapResolution,
  CarePlan,
  CarePlanCreate,
  CarePlanItemStatus,
  DischargeFollowupOutcome,
  FollowupStatus,
  Protocol,
  ProtocolCreate,
  ProtocolTransitionAction,
  Task,
} from '@sus-nexus/api-client';
import {
  buildPlanItems,
  careGaps,
  carePlans,
  daysOverdue,
  gapItemLink,
  hospitalEpisodes,
  isEligible,
  protocols,
  refreshSummary,
} from './care-data';
import { NOW, citizens, iso, tasks } from './data';
import { delay, paginate, problem, readJson, requirePurpose } from './http-utils';

/**
 * Handlers MSW (stateful) da Fase 3: episódios hospitalares (HOS), planos de cuidado,
 * lacunas/busca ativa e protocolos (CUI). Validam o mesmo que o contrato exige e simulam
 * os efeitos colaterais do core (tarefa pós-alta concluída, lacuna resolvida, resumo).
 */

const FOLLOWUP_OUTCOMES: Record<DischargeFollowupOutcome, FollowupStatus> = {
  contact_made: 'contacted',
  appointment_scheduled: 'scheduled',
  deceased: 'closed',
  moved: 'closed',
  refused: 'closed',
  not_found: 'escalated',
};
const ITEM_STATUSES: CarePlanItemStatus[] = ['planned', 'scheduled', 'done', 'missed', 'cancelled'];
const GAP_RESOLUTIONS: CareGapResolution[] = [
  'performed',
  'scheduled',
  'contact_made',
  'refused',
  'moved',
  'deceased',
  'not_found',
  'cancelled',
];
const nowIso = () => new Date().toISOString();

function touchTask(taskId: string | undefined, status: Task['status'], outcome?: string) {
  const task = tasks.find((x) => x.id === taskId);
  if (!task) return;
  task.status = status;
  if (outcome) task.outcome = outcome;
  if (status === 'completed' || status === 'cancelled') task.overdue = false;
  task.updated_at = nowIso();
  task.version = (task.version ?? 1) + 1;
}

function resolveGap(gap: CareGap, resolution: CareGapResolution) {
  gap.status = 'resolved';
  gap.resolution = resolution;
  gap.resolved_at = nowIso();
  const plan = carePlans.find((p) => p.id === gap.care_plan_id);
  if (plan)
    plan.open_gaps = careGaps.filter(
      (g) => g.care_plan_id === plan.id && g.status === 'open',
    ).length;
}

function bumpVersion(p: { minor: number; major: number }) {
  return `${p.major}.${p.minor + 1}.0`;
}

function nextVersion(careLine: string): string {
  const versions = protocols
    .filter((p) => p.care_line === careLine)
    .map((p) => p.version.split('.').map(Number))
    .map(([major = 1, minor = 0]) => ({ major, minor }))
    .sort((a, b) => (a.major - b.major) * 1000 + (a.minor - b.minor));
  const last = versions.at(-1);
  return last ? bumpVersion(last) : '1.0.0';
}

const PROTOCOL_TRANSITIONS: Record<
  ProtocolTransitionAction,
  { from: Protocol['status'][]; to: Protocol['status'] }
> = {
  submit: { from: ['draft'], to: 'in_review' },
  approve: { from: ['in_review'], to: 'approved' },
  activate: { from: ['approved'], to: 'active' },
  revoke: { from: ['draft', 'in_review', 'approved', 'active'], to: 'revoked' },
};

export const careHandlers = [
  // ---------- hospital ----------
  http.get('*/api/v1/hospital/episodes', async ({ request }) => {
    await delay();
    const purpose = requirePurpose(request);
    if (purpose instanceof Response) return purpose;
    const url = new URL(request.url);
    const p = url.searchParams;
    let list = hospitalEpisodes;
    const citizenId = p.get('citizen_id');
    if (citizenId) list = list.filter((e) => e.citizen_id === citizenId);
    const hospital = p.get('hospital_cnes');
    if (hospital) list = list.filter((e) => e.hospital_cnes === hospital);
    const status = p.get('status');
    if (status) list = list.filter((e) => e.status === status);
    const reference = p.get('reference_cnes');
    if (reference) list = list.filter((e) => e.reference_health_unit_cnes === reference);
    const followup = p.get('followup_status');
    if (followup) list = list.filter((e) => e.followup?.status === followup);
    const from = p.get('discharged_from');
    if (from) list = list.filter((e) => Boolean(e.discharged_at) && e.discharged_at! >= from);
    const to = p.get('discharged_to');
    if (to) list = list.filter((e) => Boolean(e.discharged_at) && e.discharged_at! <= to);
    const sorted = [...list].sort((a, b) =>
      (a.discharged_at ?? a.admitted_at) < (b.discharged_at ?? b.admitted_at) ? 1 : -1,
    );
    return HttpResponse.json(paginate(sorted, url));
  }),

  http.get('*/api/v1/hospital/episodes/:episodeId', async ({ request, params }) => {
    await delay();
    const purpose = requirePurpose(request);
    if (purpose instanceof Response) return purpose;
    const ep = hospitalEpisodes.find((e) => e.id === params.episodeId);
    return ep ? HttpResponse.json(ep) : problem(404, 'Episódio não encontrado');
  }),

  http.post<PathParams, DefaultBodyType>(
    '*/api/v1/hospital/episodes/:episodeId/followup',
    async ({ request, params }) => {
      await delay();
      const body = await readJson<{ outcome?: string; note?: string; contacted_at?: string }>(
        request,
      );
      const ep = hospitalEpisodes.find((e) => e.id === params.episodeId);
      if (!ep) return problem(404, 'Episódio não encontrado');
      if (!ep.discharged_at || !ep.followup)
        return problem(409, 'Sem acompanhamento pós-alta', 'O episódio não tem alta registrada.');
      const outcome = body?.outcome as DischargeFollowupOutcome | undefined;
      if (!outcome || !(outcome in FOLLOWUP_OUTCOMES))
        return problem(422, 'Desfecho inválido', undefined, [
          { field: 'outcome', message: 'Informe um desfecho válido.' },
        ]);
      if (body?.note && body.note.length > 500)
        return problem(422, 'Nota muito longa', undefined, [
          { field: 'note', message: 'Máximo de 500 caracteres.' },
        ]);
      if (ep.followup.status === 'closed')
        return problem(409, 'Acompanhamento encerrado', 'O pós-alta já foi encerrado.');
      const status = FOLLOWUP_OUTCOMES[outcome];
      ep.followup = {
        ...ep.followup,
        status,
        outcome,
        contacted_at: body?.contacted_at ?? nowIso(),
      };
      ep.version = (ep.version ?? 1) + 1;
      touchTask(ep.followup.task_id, status === 'escalated' ? 'escalated' : 'completed', outcome);
      if (status !== 'escalated') {
        for (const gap of careGaps) {
          if (
            gap.status === 'open' &&
            gap.gap_kind === 'post_discharge_no_contact' &&
            gap.citizen_id === ep.citizen_id
          )
            resolveGap(gap, outcome === 'appointment_scheduled' ? 'scheduled' : 'contact_made');
        }
      }
      refreshSummary(ep.citizen_id);
      return HttpResponse.json(ep);
    },
  ),

  // ---------- planos de cuidado ----------
  http.get('*/api/v1/careplans', async ({ request }) => {
    await delay();
    const purpose = requirePurpose(request);
    if (purpose instanceof Response) return purpose;
    const url = new URL(request.url);
    const p = url.searchParams;
    let list = carePlans;
    const citizenId = p.get('citizen_id');
    if (citizenId) list = list.filter((x) => x.citizen_id === citizenId);
    const line = p.get('care_line');
    if (line) list = list.filter((x) => x.care_line === line);
    const status = p.get('status');
    if (status) list = list.filter((x) => x.status === status);
    const team = p.get('team_ine');
    if (team) list = list.filter((x) => x.team_ine === team);
    const cnes = p.get('cnes');
    if (cnes) list = list.filter((x) => x.health_unit_cnes === cnes);
    const sorted = [...list].sort((a, b) => (a.created_at < b.created_at ? 1 : -1));
    return HttpResponse.json(paginate(sorted, url));
  }),

  http.get('*/api/v1/careplans/:carePlanId', async ({ request, params }) => {
    await delay();
    const purpose = requirePurpose(request);
    if (purpose instanceof Response) return purpose;
    const plan = carePlans.find((x) => x.id === params.carePlanId);
    return plan ? HttpResponse.json(plan) : problem(404, 'Plano de cuidado não encontrado');
  }),

  http.post<PathParams, DefaultBodyType>('*/api/v1/careplans', async ({ request }) => {
    await delay();
    const body = await readJson<Partial<CarePlanCreate>>(request);
    if (!body?.citizen_id || !body.protocol_id)
      return problem(422, 'Campos obrigatórios ausentes', 'citizen_id e protocol_id.');
    const citizen = citizens.find((c) => c.id === body.citizen_id);
    if (!citizen) return problem(404, 'Cidadão não encontrado');
    const protocol = protocols.find(
      (p) =>
        p.id === body.protocol_id &&
        (body.protocol_version ? p.version === body.protocol_version : p.status === 'active'),
    );
    if (protocol?.status !== 'active')
      return problem(422, 'Protocolo não vigente', 'Planos só podem usar a versão vigente.');
    if (!isEligible(citizen, protocol.eligibility))
      return problem(422, 'Cidadão não elegível', 'Critérios de elegibilidade do protocolo.');
    if (
      carePlans.some(
        (p) =>
          p.citizen_id === citizen.id &&
          p.care_line === protocol.care_line &&
          p.status === 'active',
      )
    )
      return problem(409, 'Plano já existente', 'Já há plano ativo para esta linha de cuidado.');
    const start = body.start_at ?? iso(NOW);
    const plan: CarePlan = {
      id: `cp_${Date.now().toString(36)}`,
      citizen_id: citizen.id,
      care_line: protocol.care_line,
      status: 'active',
      protocol_id: protocol.id,
      protocol_version: protocol.version,
      health_unit_cnes: body.health_unit_cnes ?? citizen.health_unit_cnes,
      team_ine: body.team_ine ?? citizen.team_ine,
      responsible_professional_id: body.responsible_professional_id ?? 'user_mock',
      origin: body.origin ?? { kind: 'professional', id: 'user_mock' },
      items: buildPlanItems(protocol, start, false).map(({ gap_after_days: _g, ...i }) => i),
      open_gaps: 0,
      created_at: nowIso(),
      updated_at: nowIso(),
      version: 1,
    };
    carePlans.unshift(plan);
    refreshSummary(citizen.id);
    return HttpResponse.json(plan, { status: 201 });
  }),

  http.post<PathParams, DefaultBodyType>(
    '*/api/v1/careplans/:carePlanId/items/:itemId',
    async ({ request, params }) => {
      await delay();
      const body = await readJson<{
        status?: string;
        performed_at?: string;
        evidence_ref?: string;
        note?: string;
      }>(request);
      const plan = carePlans.find((x) => x.id === params.carePlanId);
      if (!plan) return problem(404, 'Plano de cuidado não encontrado');
      if (plan.status !== 'active')
        return problem(409, 'Plano encerrado', 'Itens de planos encerrados não podem mudar.');
      const item = plan.items.find((x) => x.id === params.itemId);
      if (!item) return problem(404, 'Item não encontrado');
      const status = body?.status as CarePlanItemStatus | undefined;
      if (!status || !ITEM_STATUSES.includes(status))
        return problem(422, 'Situação inválida', undefined, [
          { field: 'status', message: 'Informe uma situação válida.' },
        ]);
      if (body?.note && body.note.length > 500)
        return problem(422, 'Nota muito longa', undefined, [
          { field: 'note', message: 'Máximo de 500 caracteres.' },
        ]);
      item.status = status;
      item.performed_at = status === 'done' ? (body?.performed_at ?? nowIso()) : undefined;
      item.evidence_ref = body?.evidence_ref || item.evidence_ref;
      item.overdue =
        (status === 'planned' || status === 'scheduled' || status === 'missed') &&
        daysOverdue(item.expected_by) > 0;
      if (status === 'done' || status === 'scheduled' || status === 'cancelled') {
        for (const [gapId, link] of gapItemLink) {
          if (link.planId !== plan.id || link.itemId !== item.id) continue;
          const gap = careGaps.find((g) => g.id === gapId);
          if (gap?.status === 'open')
            resolveGap(
              gap,
              status === 'done' ? 'performed' : status === 'scheduled' ? 'scheduled' : 'cancelled',
            );
        }
      }
      plan.updated_at = nowIso();
      plan.version = (plan.version ?? 1) + 1;
      refreshSummary(plan.citizen_id);
      return HttpResponse.json(plan);
    },
  ),

  http.post<PathParams, DefaultBodyType>(
    '*/api/v1/careplans/:carePlanId/close',
    async ({ request, params }) => {
      await delay();
      const body = await readJson<{ status?: string; reason?: string }>(request);
      const plan = carePlans.find((x) => x.id === params.carePlanId);
      if (!plan) return problem(404, 'Plano de cuidado não encontrado');
      if (body?.status !== 'completed' && body?.status !== 'cancelled')
        return problem(422, 'Situação inválida', 'Use completed ou cancelled.');
      const reason = body.reason?.trim() ?? '';
      if (reason.length < 5 || reason.length > 500)
        return problem(422, 'Motivo obrigatório', undefined, [
          { field: 'reason', message: 'Entre 5 e 500 caracteres.' },
        ]);
      if (plan.status !== 'active' && plan.status !== 'on_hold')
        return problem(409, 'Plano já encerrado');
      plan.status = body.status;
      plan.closed_reason = reason;
      plan.updated_at = nowIso();
      plan.version = (plan.version ?? 1) + 1;
      for (const gap of careGaps)
        if (gap.care_plan_id === plan.id && gap.status === 'open') resolveGap(gap, 'cancelled');
      refreshSummary(plan.citizen_id);
      return HttpResponse.json(plan);
    },
  ),

  // ---------- lacunas / busca ativa ----------
  http.get('*/api/v1/caregaps', async ({ request }) => {
    await delay();
    const purpose = requirePurpose(request);
    if (purpose instanceof Response) return purpose;
    const url = new URL(request.url);
    const p = url.searchParams;
    const status = p.get('status') ?? 'open';
    let list = careGaps.filter((g) => g.status === status);
    const line = p.get('care_line');
    if (line) list = list.filter((g) => g.care_line === line);
    const kind = p.get('gap_kind');
    if (kind) list = list.filter((g) => g.gap_kind === kind);
    const cnes = p.get('cnes');
    if (cnes) list = list.filter((g) => g.health_unit_cnes === cnes);
    const team = p.get('team_ine');
    if (team) list = list.filter((g) => g.team_ine === team);
    const micro = p.get('microarea');
    if (micro) list = list.filter((g) => g.microarea === micro);
    const minDays = Number(p.get('min_days_overdue') ?? '');
    if (Number.isFinite(minDays) && minDays > 0)
      list = list.filter((g) => (g.days_overdue ?? 0) >= minDays);
    const sorted = [...list].sort((a, b) => (b.days_overdue ?? 0) - (a.days_overdue ?? 0));
    return HttpResponse.json(paginate(sorted, url));
  }),

  http.post<PathParams, DefaultBodyType>(
    '*/api/v1/caregaps/:careGapId/resolve',
    async ({ request, params }) => {
      await delay();
      const body = await readJson<{ resolution?: string; note?: string }>(request);
      const gap = careGaps.find((g) => g.id === params.careGapId);
      if (!gap) return problem(404, 'Lacuna não encontrada');
      const resolution = body?.resolution as CareGapResolution | undefined;
      if (!resolution || !GAP_RESOLUTIONS.includes(resolution))
        return problem(422, 'Desfecho inválido', undefined, [
          { field: 'resolution', message: 'Informe um desfecho válido.' },
        ]);
      if (body?.note && body.note.length > 500)
        return problem(422, 'Nota muito longa', undefined, [
          { field: 'note', message: 'Máximo de 500 caracteres.' },
        ]);
      if (gap.status !== 'open') return problem(409, 'Lacuna já resolvida');
      resolveGap(gap, resolution);
      if (gap.task_id) touchTask(gap.task_id, 'completed', resolution);
      refreshSummary(gap.citizen_id);
      return HttpResponse.json(gap);
    },
  ),

  // ---------- protocolos ----------
  http.get('*/api/v1/protocols', async ({ request }) => {
    await delay();
    const p = new URL(request.url).searchParams;
    let list = protocols;
    const line = p.get('care_line');
    if (line) list = list.filter((x) => x.care_line === line);
    const status = p.get('status');
    if (status) list = list.filter((x) => x.status === status);
    return HttpResponse.json(list);
  }),

  http.post<PathParams, DefaultBodyType>('*/api/v1/protocols', async ({ request }) => {
    await delay();
    const body = await readJson<Partial<ProtocolCreate>>(request);
    const careLine = body?.care_line?.trim();
    const name = body?.name?.trim();
    if (!careLine || !name || !Array.isArray(body?.items) || body.items.length === 0)
      return problem(
        422,
        'Campos obrigatórios ausentes',
        'Linha de cuidado, nome e ao menos 1 item.',
      );
    for (const [i, item] of body.items.entries()) {
      if (!item.title?.trim() || !Number.isInteger(item.due_in_days) || item.due_in_days < 0)
        return problem(422, 'Item inválido', undefined, [
          { field: `items[${i}]`, message: 'Título e prazo (dias ≥ 0) são obrigatórios.' },
        ]);
    }
    const existing = protocols.find((p) => p.care_line === careLine);
    const created: Protocol = {
      id: existing?.id ?? `prot_${careLine}`,
      care_line: careLine,
      name,
      version: nextVersion(careLine),
      status: 'draft',
      description: body.description,
      eligibility: body.eligibility,
      items: body.items,
      lost_to_followup_days: body.lost_to_followup_days,
      test_cases_count: Array.isArray(body.test_cases) ? body.test_cases.length : 0,
      created_at: nowIso(),
    };
    protocols.push(created);
    return HttpResponse.json(created, { status: 201 });
  }),

  http.post<PathParams, DefaultBodyType>(
    '*/api/v1/protocols/:protocolId/versions/:version/transition',
    async ({ request, params }) => {
      await delay();
      const body = await readJson<{
        action?: string;
        justification?: string;
        effective_from?: string;
      }>(request);
      const protocol = protocols.find(
        (p) => p.id === params.protocolId && p.version === params.version,
      );
      if (!protocol) return problem(404, 'Versão de protocolo não encontrada');
      const action = body?.action as ProtocolTransitionAction | undefined;
      const tr = action ? PROTOCOL_TRANSITIONS[action] : undefined;
      if (!action || !tr) return problem(422, 'Ação inválida');
      if (!tr.from.includes(protocol.status))
        return problem(
          409,
          'Transição inválida',
          `Versão em "${protocol.status}" não aceita "${action}".`,
        );
      if (body?.justification && body.justification.length > 500)
        return problem(422, 'Justificativa muito longa');
      if (
        (action === 'approve' || action === 'revoke') &&
        (body?.justification?.trim().length ?? 0) < 10
      )
        return problem(422, 'Justificativa obrigatória', 'Mínimo de 10 caracteres.');
      if (action === 'approve' && !protocol.test_cases_count)
        return problem(
          422,
          'Casos de teste obrigatórios',
          'A aprovação exige casos de teste anexados à versão (plano 8.3).',
        );
      if (action === 'activate') {
        for (const p of protocols)
          if (p.id === protocol.id && p.status === 'active') p.status = 'revoked';
        protocol.effective_from = body?.effective_from ?? nowIso();
      }
      if (action === 'approve') protocol.approved_by = 'user_mock';
      protocol.status = tr.to;
      return HttpResponse.json(protocol);
    },
  ),
];
