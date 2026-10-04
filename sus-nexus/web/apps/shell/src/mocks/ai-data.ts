import type {
  AgentAction,
  AgentApproval,
  AgentDescriptor,
  AgentRunRecord,
  KillSwitchState,
  ToolCallRecord,
  ToolDescriptor,
} from '@sus-nexus/api-client';
import { citizens, hoursAgo, int, iso, mergeCases, pick, ulid } from './data';
import { regulationRequests } from './regulation-data';

/**
 * Dados sintéticos do ai-service (`GET /agents`, `/tools`, `/runs`, `/approvals`,
 * `/admin/kill-switch`). Shapes seguem `contracts/openapi/ai-service.yaml` (tipos gerados em `@sus-nexus/api-client`).
 * Os registros nunca contêm PII: contexto minimizado e argumentos mascarados.
 */

const TENANT = 'ibge_3143302';

export const aiTools: ToolDescriptor[] = [
  {
    name: 'core.get_citizen_summary',
    description: 'Resumo operacional do cidadão (sem PII).',
    risk: 'low',
    action_class: 'auto',
    scope: 'core:read',
    kind: 'read',
    owner: 'core',
    stub: false,
    input_schema: {},
    output_schema: {},
  },
  {
    name: 'core.get_regulation_request',
    description: 'Lê uma solicitação de regulação.',
    risk: 'low',
    action_class: 'auto',
    scope: 'core:read',
    kind: 'read',
    owner: 'core',
    stub: false,
    input_schema: {},
    output_schema: {},
  },
  {
    name: 'core.list_merge_case',
    description: 'Lê um caso de duplicidade do MPI.',
    risk: 'low',
    action_class: 'auto',
    scope: 'core:read',
    kind: 'read',
    owner: 'core',
    stub: false,
    input_schema: {},
    output_schema: {},
  },
  {
    name: 'core.create_task',
    description: 'Cria tarefa operacional para a equipe.',
    risk: 'low',
    action_class: 'auto',
    scope: 'core:write',
    kind: 'write',
    owner: 'core',
    stub: false,
    input_schema: {},
    output_schema: {},
  },
  {
    name: 'core.create_pending_issue',
    description: 'Registra pendência documental na regulação.',
    risk: 'medium',
    action_class: 'requires_approval',
    scope: 'core:write',
    kind: 'write',
    owner: 'core',
    stub: false,
    input_schema: {},
    output_schema: {},
  },
  {
    name: 'core.send_message',
    description: 'Envia mensagem ao cidadão (SMS/WhatsApp).',
    risk: 'medium',
    action_class: 'requires_approval',
    scope: 'communication',
    kind: 'write',
    owner: 'core',
    stub: true,
    input_schema: {},
    output_schema: {},
  },
  {
    name: 'mpi.merge',
    description: 'Fusão de cadastros — proibida para agentes.',
    risk: 'high',
    action_class: 'forbidden',
    scope: 'mpi:write',
    kind: 'write',
    owner: 'core',
    stub: false,
    input_schema: {},
    output_schema: {},
  },
  {
    name: 'regulation.decide',
    description: 'Decisão regulatória — proibida (REG-009).',
    risk: 'high',
    action_class: 'forbidden',
    scope: 'regulation:decide',
    kind: 'write',
    owner: 'official',
    stub: false,
    input_schema: {},
    output_schema: {},
  },
  {
    name: 'regulation.change_priority',
    description: 'Alteração de prioridade — proibida (REG-009).',
    risk: 'high',
    action_class: 'forbidden',
    scope: 'regulation:decide',
    kind: 'write',
    owner: 'official',
    stub: false,
    input_schema: {},
    output_schema: {},
  },
];

export const aiAgents: AgentDescriptor[] = [
  {
    id: 'regulation_completeness',
    version: '1.0.0',
    prompt_version: 'v1',
    description:
      'Verifica completude da solicitação de regulação e sugere pendências; a pendência só é registrada com aprovação humana.',
    tools: ['core.get_regulation_request', 'core.create_pending_issue'],
    rule_versions: { completeness: 'regulation_completeness_v1' },
    input_schema: { type: 'object' },
    output_schema: { type: 'object' },
  },
  {
    id: 'post_discharge_followup',
    version: '1.0.0',
    prompt_version: 'v1',
    description:
      'Classifica risco pós-alta por regra versionada e cria tarefa de seguimento para a equipe de referência.',
    tools: ['core.get_citizen_summary', 'core.create_task'],
    rule_versions: { risk: 'post_discharge_risk_v1' },
    input_schema: { type: 'object' },
    output_schema: { type: 'object' },
  },
  {
    id: 'mpi_duplicate_suggestion',
    version: '1.0.0',
    prompt_version: 'v1',
    description:
      'Sugere se um caso de duplicidade do MPI é a mesma pessoa. Somente sugestão: não executa ações.',
    tools: ['core.list_merge_case'],
    rule_versions: { heuristics: 'duplicate_heuristics_v1' },
    input_schema: { type: 'object' },
    output_schema: { type: 'object' },
  },
];

const hash = () => Array.from({ length: 64 }, () => '0123456789abcdef'[int(0, 15)]).join('');

function tool(
  name: string,
  status: ToolCallRecord['status'],
  args: Record<string, string>,
  calledAt: string,
  extra: Partial<ToolCallRecord> = {},
): ToolCallRecord {
  const spec = aiTools.find((t) => t.name === name);
  const allow = status !== 'denied';
  return {
    tool: name,
    status,
    args_masked: args,
    decision: {
      allow,
      action_class: spec?.action_class ?? 'auto',
      requires_approval: status === 'requires_approval',
      reasons: allow ? [] : ['forbidden_action_class', 'tool_not_granted'],
    },
    result_hash: status === 'executed' ? hash() : undefined,
    called_at: calledAt,
    duration_ms: int(40, 900),
    ...extra,
  };
}

function baseRun(
  agentId: string,
  startedHoursAgo: number,
  trigger: AgentRunRecord['trigger'],
): AgentRunRecord {
  const agent = aiAgents.find((a) => a.id === agentId)!;
  const started = hoursAgo(startedHoursAgo);
  return {
    id: `run_${ulid()}`,
    agent_id: agentId,
    agent_version: agent.version,
    prompt_version: agent.prompt_version,
    model: 'claude-sonnet-4-5',
    model_params: { temperature: 0, max_tokens: 1024 },
    rule_versions: agent.rule_versions,
    tenant: TENANT,
    trigger,
    input_ref: {
      hash: hash(),
      ref: `s3://sus-nexus-ai/inputs/${ulid().toLowerCase()}.json`,
      kind: 'event',
    },
    minimized_context: {},
    tools_called: [],
    output: null,
    validation_status: 'valid',
    validation_attempts: 1,
    status: 'completed',
    actions: [],
    started_at: started,
    finished_at: iso(new Date(new Date(started).getTime() + int(3, 20) * 1000)),
    cost_estimate: Math.round(int(1, 9) * 0.0013 * 1000) / 1000,
    tokens_in: int(900, 2400),
    tokens_out: int(120, 600),
    error: null,
  };
}

function pendingAction(toolName: string, args: Record<string, string>): AgentAction {
  return {
    id: `act_${ulid()}`,
    tool: toolName,
    action_class: 'requires_approval',
    status: 'pending_approval',
    args,
    reasons: ['requires_approval'],
    result_hash: null,
    approver: null,
    justification: null,
    decided_at: null,
    error: null,
  };
}

export const aiRuns: AgentRunRecord[] = [];

// 1–3: completude regulatória com ação pendente de aprovação (vinculadas a solicitações reais).
for (let i = 0; i < 3; i++) {
  const req = regulationRequests[i]!;
  const run = baseRun('regulation_completeness', 2 + i * 5, {
    kind: 'event',
    ref: `evt_${ulid()}`,
  });
  const args = {
    request_id: req.id,
    kind: 'missing_document',
    description: 'Anexar ECG recente (até 6 meses) ao encaminhamento.',
  };
  run.minimized_context = {
    request_id: req.id,
    specialty: req.specialty ?? null,
    priority: req.priority ?? null,
    attached_documents_count: req.attached_documents_count ?? 0,
    justification_present: req.justification_present ?? false,
    citizen: 'pseudonym:ctz_' + ulid().slice(0, 6),
    rules_version: 'regulation_completeness_v1',
  };
  run.tools_called = [
    tool('core.get_regulation_request', 'executed', { request_id: req.id }, run.started_at),
    tool('core.create_pending_issue', 'requires_approval', args, run.started_at),
  ];
  run.output = {
    complete: false,
    suggested_issues: [
      { kind: 'missing_document', description: args.description, confidence: 0.86 },
    ],
    summary: 'Encaminhamento sem ECG recente; prioridade compatível com a justificativa.',
  };
  run.actions = [pendingAction('core.create_pending_issue', args)];
  aiRuns.push(run);
}

// 4–7: pós-alta com tarefa criada automaticamente.
for (let i = 0; i < 4; i++) {
  const c = citizens[(i * 3) % citizens.length]!;
  const run = baseRun('post_discharge_followup', 6 + i * 9, {
    kind: 'event',
    ref: `evt_${ulid()}`,
  });
  run.minimized_context = {
    citizen: 'pseudonym:ctz_' + ulid().slice(0, 6),
    age_band: '60-69',
    care_lines: ['hipertensão'],
    discharge_days_ago: int(0, 2),
    open_tasks: int(0, 2),
    contact_valid: true,
    risk_assessment: {
      level: pick(['high', 'medium', 'low']),
      rules_version: 'post_discharge_risk_v1',
    },
  };
  const taskArgs = {
    citizen_id: c.id,
    task_type: 'generic',
    title: 'Contato pós-alta hospitalar em 72h',
    priority: 'high',
    assignee: `team:team_${c.team_ine ?? ''}`,
  };
  run.tools_called = [
    tool(
      'core.get_citizen_summary',
      'executed',
      { citizen_id: c.id, purpose: 'care_coordination' },
      run.started_at,
    ),
    tool('core.create_task', 'executed', taskArgs, run.started_at),
  ];
  run.output = {
    risk_level: 'high',
    recommended_followup_hours: 72,
    rules_version: 'post_discharge_risk_v1',
  };
  run.actions = [
    {
      id: `act_${ulid()}`,
      tool: 'core.create_task',
      action_class: 'auto',
      status: 'executed',
      args: taskArgs,
      result_hash: hash(),
      reasons: [],
      approver: null,
      justification: null,
      decided_at: null,
      error: null,
    },
  ];
  aiRuns.push(run);
}

// 8–9: sugestão de duplicidade (somente sugestão).
for (let i = 0; i < 2; i++) {
  const mc = mergeCases[i]!;
  const run = baseRun('mpi_duplicate_suggestion', 20 + i * 7, {
    kind: 'user',
    ref: 'user_cadastradora_02',
  });
  run.minimized_context = {
    case_id: mc.id,
    score: mc.score ?? null,
    conflicts: mc.conflicts ?? [],
    rules_version: 'duplicate_heuristics_v1',
  };
  run.tools_called = [tool('core.list_merge_case', 'executed', { case_id: mc.id }, run.started_at)];
  run.output = {
    same_person: i === 0,
    confidence: i === 0 ? 0.91 : 0.42,
    rationale:
      'Nome, data de nascimento e nome da mãe coincidem; CNS distintos sugerem cadastro duplicado.',
  };
  aiRuns.push(run);
}

// 10: negada pelo OPA (tentou mpi.merge — proibido).
{
  const run = baseRun('mpi_duplicate_suggestion', 40, { kind: 'eval', ref: 'eval_batch_17' });
  run.status = 'denied';
  run.error = 'ferramenta negada: mpi.merge (forbidden_action_class, tool_not_granted)';
  run.minimized_context = { case_id: mergeCases[2]!.id, score: 0.66 };
  run.tools_called = [
    tool('core.list_merge_case', 'executed', { case_id: mergeCases[2]!.id }, run.started_at),
    tool('mpi.merge', 'denied', { case_id: mergeCases[2]!.id }, run.started_at),
  ];
  run.actions = [
    {
      id: `act_${ulid()}`,
      tool: 'mpi.merge',
      action_class: 'forbidden',
      status: 'denied',
      args: { case_id: mergeCases[2]!.id },
      reasons: ['forbidden_action_class', 'tool_not_granted'],
      result_hash: null,
      approver: null,
      justification: null,
      decided_at: null,
      error: 'ferramenta negada: mpi.merge',
    },
  ];
  aiRuns.push(run);
}

// 11: saída inválida após 2 tentativas.
{
  const run = baseRun('regulation_completeness', 52, { kind: 'event', ref: `evt_${ulid()}` });
  run.status = 'invalid_output';
  run.validation_status = 'invalid_output';
  run.validation_attempts = 2;
  run.error = 'saída não conforme ao schema após 2 tentativas';
  run.minimized_context = { request_id: regulationRequests[5]!.id };
  run.tools_called = [
    tool(
      'core.get_regulation_request',
      'executed',
      { request_id: regulationRequests[5]!.id },
      run.started_at,
    ),
  ];
  aiRuns.push(run);
}

// 12: em execução.
{
  const run = baseRun('post_discharge_followup', 0.05, { kind: 'event', ref: `evt_${ulid()}` });
  run.status = 'running';
  run.finished_at = null;
  run.validation_status = 'not_run';
  run.validation_attempts = 0;
  run.minimized_context = { citizen: 'pseudonym:ctz_' + ulid().slice(0, 6), discharge_days_ago: 0 };
  aiRuns.push(run);
}

aiRuns.sort((a, b) => (a.started_at < b.started_at ? 1 : -1));

export const aiApprovals: AgentApproval[] = aiRuns.flatMap((run) =>
  run.actions
    .filter((a) => a.status === 'pending_approval')
    .map((a) => ({
      id: `apr_${ulid()}`,
      run_id: run.id,
      action_id: a.id,
      agent_id: run.agent_id,
      tenant: run.tenant,
      tool: a.tool,
      status: 'pending' as const,
      requested_at: run.finished_at ?? run.started_at,
      decided_at: null,
      approver: null,
      justification: null,
    })),
);

export const killSwitchAdmin: KillSwitchState = {
  global: false,
  agents: [],
  tools: [],
  tenants: [],
};
/** Estado vindo de arquivo/variável de ambiente (não editável pela UI). */
export const killSwitchEnv: KillSwitchState = {
  global: false,
  agents: [],
  tools: ['core.send_message'],
  tenants: [],
};

export function mergeKillSwitch(a: KillSwitchState, b: KillSwitchState): KillSwitchState {
  const u = (x: string[], y: string[]) => [...new Set([...x, ...y])].sort();
  return {
    global: a.global || b.global,
    agents: u(a.agents, b.agents),
    tools: u(a.tools, b.tools),
    tenants: u(a.tenants, b.tenants),
  };
}
