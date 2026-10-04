import type { AgentRunRecord, ToolCallRecord } from '@sus-nexus/api-client';
import {
  actionStatusLabels,
  type AgentDecision,
  type AgentPlannedAction,
  type AgentToolCall,
} from '@sus-nexus/domain-components';

/** Serializa um valor JSON (já minimizado pelo ai-service) para exibição compacta. */
export function stringifyJson(value: unknown): string {
  if (value === undefined || value === null) return '—';
  if (typeof value === 'string') return value;
  if (typeof value === 'number' || typeof value === 'boolean') return String(value);
  try {
    return JSON.stringify(value) ?? '—';
  } catch {
    return '[não serializável]';
  }
}

function toolStatus(status: ToolCallRecord['status']): AgentToolCall['status'] {
  switch (status) {
    case 'executed':
      return 'ok';
    case 'denied':
      return 'denied';
    case 'requires_approval':
      return 'requires_approval';
    default:
      return 'error';
  }
}

/** Converte `AgentRunRecord` (ai-service) no modelo do `AgentDecisionTrace`. */
export function toAgentDecision(run: AgentRunRecord): AgentDecision {
  const inputs = Object.fromEntries(
    Object.entries(run.minimized_context).map(([k, v]) => [k, stringifyJson(v)]),
  );
  const tools: AgentToolCall[] = run.tools_called.map((c) => ({
    name: c.tool,
    action_class: c.decision.action_class ?? 'auto',
    status: toolStatus(c.status),
    started_at: c.called_at,
    duration_ms: c.duration_ms,
    summary: c.error ?? (c.approved_by ? `Aprovada por ${c.approved_by}` : undefined),
    policy: {
      allow: c.decision.allow,
      requires_approval: c.decision.requires_approval,
      reasons: c.decision.reasons,
    },
    args: Object.fromEntries(Object.entries(c.args_masked).map(([k, v]) => [k, stringifyJson(v)])),
  }));
  const actions: AgentPlannedAction[] = run.actions.map((a) => ({
    id: a.id,
    tool: a.tool,
    action_class: a.action_class,
    status: a.status,
    statusLabel: actionStatusLabels[a.status].label,
    statusTone: actionStatusLabels[a.status].tone,
    reasons: a.reasons,
    approver: a.approver ?? undefined,
    justification: a.justification ?? undefined,
    decided_at: a.decided_at ?? undefined,
    error: a.error ?? undefined,
  }));
  const requiresApproval = run.actions.some((a) => a.action_class === 'requires_approval');
  const decided = run.actions.find((a) => a.approver);
  const ruleEntries = Object.entries(run.rule_versions);
  return {
    run_id: run.id,
    agent_id: run.agent_id,
    agent_version: run.agent_version,
    prompt_version: run.prompt_version,
    model: run.model,
    tenant: run.tenant,
    trigger: run.trigger.ref ? `${run.trigger.kind} (${run.trigger.ref})` : run.trigger.kind,
    started_at: run.started_at,
    finished_at: run.finished_at ?? undefined,
    inputs,
    tools,
    output: run.output ? JSON.stringify(run.output, null, 2) : '',
    rule_id: ruleEntries[0]?.[0],
    rule_version: ruleEntries[0]?.[1],
    actions,
    approval: {
      required: requiresApproval,
      approved_by: decided?.approver ?? undefined,
      approved_at: decided?.decided_at ?? undefined,
      decision: decided ? (decided.status === 'rejected' ? 'rejected' : 'approved') : undefined,
    },
    error: run.error ?? undefined,
  };
}
