import { z } from 'zod';

/**
 * Tipos do ai-service (`sus-nexus/ai-service/sus_nexus_ai/api/routes.py` e
 * `persistence/schemas.py`). Mantidos manualmente com zod enquanto o ai-service não publica
 * um contrato OpenAPI próprio em `contracts/openapi/`; quando publicar, este módulo deve ser
 * substituído por tipos gerados (`openapi-typescript`) como `generated/core.d.ts`.
 *
 * Os schemas validam a resposta em tempo de execução: um shape inesperado falha cedo
 * (`AiContractError`) em vez de propagar `undefined` pela UI.
 */

export const actionClassSchema = z.enum(['auto', 'requires_approval', 'forbidden']);
export type ActionClass = z.infer<typeof actionClassSchema>;

export const runStatusSchema = z.enum([
  'running',
  'completed',
  'invalid_output',
  'failed',
  'denied',
]);
export type RunStatus = z.infer<typeof runStatusSchema>;

export const validationStatusSchema = z.enum(['not_run', 'valid', 'invalid_output']);
export type ValidationStatus = z.infer<typeof validationStatusSchema>;

export const actionStatusSchema = z.enum([
  'executed',
  'pending_approval',
  'approved',
  'rejected',
  'blocked',
  'denied',
  'failed',
]);
export type ActionStatus = z.infer<typeof actionStatusSchema>;

export const approvalStatusSchema = z.enum(['pending', 'approved', 'rejected']);
export type ApprovalStatus = z.infer<typeof approvalStatusSchema>;

export const triggerSchema = z.object({
  kind: z.enum(['event', 'user', 'workflow', 'eval', 'manual']).default('manual'),
  ref: z.string().nullish(),
  on_behalf_of: z.string().nullish(),
});
export type Trigger = z.infer<typeof triggerSchema>;

export const inputRefSchema = z.object({
  hash: z.string(),
  ref: z.string().nullish(),
  kind: z.string().nullish(),
});
export type InputRef = z.infer<typeof inputRefSchema>;

/** Valor JSON genérico (argumentos mascarados, contexto minimizado, saída estruturada). */
export const jsonValueSchema: z.ZodType<JsonValue> = z.lazy(() =>
  z.union([
    z.string(),
    z.number(),
    z.boolean(),
    z.null(),
    z.array(jsonValueSchema),
    z.record(jsonValueSchema),
  ]),
);
export type JsonValue = string | number | boolean | null | JsonValue[] | { [key: string]: JsonValue };
export type JsonObject = Record<string, JsonValue>;

export const agentActionSchema = z.object({
  id: z.string(),
  tool: z.string(),
  action_class: actionClassSchema,
  status: actionStatusSchema,
  /** Argumentos mascarados (sem PII). */
  args: z.record(jsonValueSchema).default({}),
  result_hash: z.string().nullish(),
  approver: z.string().nullish(),
  justification: z.string().nullish(),
  decided_at: z.string().nullish(),
  error: z.string().nullish(),
  reasons: z.array(z.string()).default([]),
});
export type AgentAction = z.infer<typeof agentActionSchema>;

/** Decisão OPA anexada a cada chamada de ferramenta (`PolicyDecision`). */
export const policyDecisionSchema = z.object({
  allow: z.boolean(),
  action_class: actionClassSchema,
  requires_approval: z.boolean().default(false),
  reasons: z.array(z.string()).default([]),
});
export type PolicyDecision = z.infer<typeof policyDecisionSchema>;

/** `ToolCallRecord.to_dict()` em `tools/executor.py`. */
export const toolCallRecordSchema = z.object({
  tool: z.string(),
  status: z.enum(['executed', 'denied', 'requires_approval', 'error']),
  args_masked: z.record(jsonValueSchema).default({}),
  decision: policyDecisionSchema.partial().default({}),
  result_hash: z.string().nullish(),
  error: z.string().nullish(),
  approved_by: z.string().nullish(),
  called_at: z.string(),
  duration_ms: z.number().default(0),
});
export type ToolCallRecord = z.infer<typeof toolCallRecordSchema>;

export const agentRunRecordSchema = z.object({
  id: z.string(),
  agent_id: z.string(),
  agent_version: z.string(),
  prompt_version: z.string(),
  model: z.string(),
  model_params: z.record(jsonValueSchema).default({}),
  rule_versions: z.record(z.string()).default({}),
  tenant: z.string(),
  trigger: triggerSchema,
  input_ref: inputRefSchema,
  minimized_context: z.record(jsonValueSchema).default({}),
  tools_called: z.array(toolCallRecordSchema).default([]),
  output: z.record(jsonValueSchema).nullish(),
  validation_status: validationStatusSchema.default('not_run'),
  validation_attempts: z.number().default(0),
  status: runStatusSchema.default('running'),
  actions: z.array(agentActionSchema).default([]),
  started_at: z.string(),
  finished_at: z.string().nullish(),
  cost_estimate: z.number().default(0),
  tokens_in: z.number().default(0),
  tokens_out: z.number().default(0),
  error: z.string().nullish(),
});
export type AgentRunRecord = z.infer<typeof agentRunRecordSchema>;

export const agentApprovalSchema = z.object({
  id: z.string(),
  run_id: z.string(),
  action_id: z.string(),
  agent_id: z.string(),
  tenant: z.string(),
  tool: z.string(),
  status: approvalStatusSchema.default('pending'),
  requested_at: z.string(),
  decided_at: z.string().nullish(),
  approver: z.string().nullish(),
  justification: z.string().nullish(),
});
export type AgentApproval = z.infer<typeof agentApprovalSchema>;

/** Nível de autonomia derivado das ferramentas concedidas (não existe campo no ai-service). */
export const autonomyLevelSchema = z.enum(['suggest_only', 'approval_required', 'autonomous']);
export type AutonomyLevel = z.infer<typeof autonomyLevelSchema>;

/** `GET /agents` — item do catálogo. */
export const agentDescriptorSchema = z.object({
  id: z.string(),
  version: z.string(),
  prompt_version: z.string(),
  description: z.string().default(''),
  tools: z.array(z.string()).default([]),
  rule_versions: z.record(z.string()).default({}),
  output_schema: z.record(jsonValueSchema).optional(),
});
export type AgentDescriptor = z.infer<typeof agentDescriptorSchema>;

/** `GET /tools` — `ToolSpec.describe()`. */
export const toolDescriptorSchema = z.object({
  name: z.string(),
  description: z.string().default(''),
  risk: z.enum(['low', 'medium', 'high']).catch('medium'),
  action_class: actionClassSchema,
  scope: z.string().nullish(),
  kind: z.string().nullish(),
  owner: z.string().nullish(),
  stub: z.boolean().default(false),
});
export type ToolDescriptor = z.infer<typeof toolDescriptorSchema>;

/** `KillSwitchState` (alias `global` → `global_` no Pydantic; o JSON usa `global`). */
export const killSwitchStateSchema = z.object({
  global: z.boolean().default(false),
  agents: z.array(z.string()).default([]),
  tools: z.array(z.string()).default([]),
  tenants: z.array(z.string()).default([]),
});
export type KillSwitchState = z.infer<typeof killSwitchStateSchema>;

export const killSwitchResponseSchema = z.object({
  /** União de arquivo + variável de ambiente + estado administrativo. */
  effective: killSwitchStateSchema,
  /** Somente o estado definido via `POST /admin/kill-switch`. */
  admin: killSwitchStateSchema,
});
export type KillSwitchResponse = z.infer<typeof killSwitchResponseSchema>;

export const decisionRequestSchema = z.object({
  justification: z.string().min(10).max(1000),
});
export type DecisionRequest = z.infer<typeof decisionRequestSchema>;

/** Deriva o nível de autonomia a partir das ferramentas concedidas. */
export function deriveAutonomy(
  agent: Pick<AgentDescriptor, 'tools'>,
  tools: Pick<ToolDescriptor, 'name' | 'action_class'>[] = [],
): AutonomyLevel {
  const classes = agent.tools.map(
    (name) => tools.find((t) => t.name === name)?.action_class ?? inferActionClass(name),
  );
  if (classes.some((c) => c === 'auto')) return 'autonomous';
  if (classes.some((c) => c === 'requires_approval')) return 'approval_required';
  return 'suggest_only';
}

/** Heurística para ferramentas desconhecidas: leitura = auto; escrita = aprovação. */
function inferActionClass(toolName: string): ActionClass {
  if (/\.(get|list|search|read)_/.test(toolName)) return 'auto';
  if (/\.(merge|decide|change_priority|transmit)/.test(toolName)) return 'forbidden';
  return 'requires_approval';
}
