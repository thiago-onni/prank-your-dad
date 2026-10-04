import type { components, paths } from '../generated/ai';

/**
 * Tipos do ai-service derivados do contrato `contracts/openapi/ai-service.yaml`
 * (`pnpm generate:ai` → `src/generated/ai.d.ts`).
 *
 * O contrato (exportado do FastAPI/Pydantic) deixa alguns campos opcionais ou com tipo aberto
 * (`tools_called: object[]`, `ToolDescriptor.risk: string`). Aqui definimos os tipos
 * *normalizados* que a UI consome: o cliente (`createAiClient`) aplica os valores padrão do
 * Pydantic e estreita os enums conhecidos, para que as telas nunca lidem com `undefined`.
 */

export type AiPaths = paths;
type Schemas = components['schemas'];

export type ActionClass = Schemas['AgentAction']['action_class'];
export type ActionStatus = Schemas['AgentAction']['status'];
export type RunStatus = Schemas['AgentRunRecord']['status'];
export type ValidationStatus = Schemas['AgentRunRecord']['validation_status'];
export type ApprovalStatus = Schemas['AgentApproval']['status'];
export type TriggerKind = Schemas['Trigger']['kind'];
export type Trigger = Schemas['Trigger'];
export type InputRef = Schemas['InputRef'];
export type DecisionRequest = Schemas['DecisionRequest'];
export type RunRequest = Schemas['RunRequest'];
export type HealthResponse = Schemas['HealthResponse'];

/** Objeto JSON livre (argumentos mascarados, contexto minimizado, saída estruturada). */
export type JsonObject = Record<string, unknown>;

/** Shapes brutos do contrato (campos opcionais quando o Pydantic tem `default`). */
export type AgentActionRaw = Schemas['AgentAction'];
export type AgentRunRecordRaw = Schemas['AgentRunRecord'];
export type AgentApprovalRaw = Schemas['AgentApproval'];
export type AgentDescriptorRaw = Schemas['AgentDescriptor'];
export type ToolDescriptorRaw = Schemas['ToolDescriptor'];
export type KillSwitchStateRaw = Schemas['KillSwitchState'];
export type KillSwitchResponseRaw = Schemas['KillSwitchResponse'];

export type AgentAction = Omit<AgentActionRaw, 'args' | 'reasons'> & {
  /** Argumentos mascarados (sem PII). */
  args: JsonObject;
  reasons: string[];
};

/** Decisão OPA anexada a cada chamada de ferramenta (`PolicyDecision`). */
export interface PolicyDecision {
  allow?: boolean;
  action_class?: ActionClass;
  requires_approval?: boolean;
  reasons?: string[];
}

export type ToolCallStatus = 'executed' | 'denied' | 'requires_approval' | 'error';

/**
 * `ToolCallRecord.to_dict()` (`tools/executor.py`). O contrato publica `tools_called` como
 * lista de objetos livres; este é o shape documentado, estreitado por `normalizeToolCall`.
 */
export interface ToolCallRecord {
  tool: string;
  status: ToolCallStatus;
  args_masked: JsonObject;
  decision: PolicyDecision;
  result_hash?: string | null;
  error?: string | null;
  approved_by?: string | null;
  called_at: string;
  duration_ms: number;
}

export type AgentRunRecord = Omit<
  AgentRunRecordRaw,
  'actions' | 'tools_called' | 'minimized_context' | 'model_params' | 'rule_versions' | 'output'
> & {
  actions: AgentAction[];
  tools_called: ToolCallRecord[];
  minimized_context: JsonObject;
  model_params: JsonObject;
  rule_versions: Record<string, string>;
  output: JsonObject | null;
};

export type AgentApproval = AgentApprovalRaw;
export type AgentDescriptor = AgentDescriptorRaw;

export type ToolRisk = 'low' | 'medium' | 'high';
export type ToolDescriptor = Omit<ToolDescriptorRaw, 'risk' | 'action_class'> & {
  risk: ToolRisk;
  action_class: ActionClass;
};

/** `KillSwitchState` com todas as listas presentes (o JSON usa `global`, não `global_`). */
export type KillSwitchState = Required<KillSwitchStateRaw>;

export interface KillSwitchResponse {
  /** União de arquivo + variável de ambiente + estado administrativo. */
  effective: KillSwitchState;
  /** Somente o estado definido via `POST /admin/kill-switch`. */
  admin: KillSwitchState;
}

/** Nível de autonomia derivado das ferramentas concedidas (não existe campo no ai-service). */
export type AutonomyLevel = 'suggest_only' | 'approval_required' | 'autonomous';

// ---------- normalização (defaults do Pydantic + estreitamento de enums) ----------

const ACTION_CLASSES: readonly ActionClass[] = ['auto', 'requires_approval', 'forbidden'];
const TOOL_RISKS: readonly ToolRisk[] = ['low', 'medium', 'high'];
const TOOL_CALL_STATUSES: readonly ToolCallStatus[] = [
  'executed',
  'denied',
  'requires_approval',
  'error',
];

function isObject(value: unknown): value is JsonObject {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function asString(value: unknown, fallback = ''): string {
  return typeof value === 'string' ? value : fallback;
}

function asStringArray(value: unknown): string[] {
  return Array.isArray(value) ? value.filter((v): v is string => typeof v === 'string') : [];
}

function oneOf<T extends string>(value: unknown, allowed: readonly T[], fallback: T): T {
  return typeof value === 'string' && (allowed as readonly string[]).includes(value)
    ? (value as T)
    : fallback;
}

export function normalizeToolCall(raw: unknown): ToolCallRecord {
  const o = isObject(raw) ? raw : {};
  const decision = isObject(o.decision) ? o.decision : {};
  return {
    tool: asString(o.tool, 'desconhecida'),
    status: oneOf(o.status, TOOL_CALL_STATUSES, 'error'),
    args_masked: isObject(o.args_masked) ? o.args_masked : {},
    decision: {
      allow: typeof decision.allow === 'boolean' ? decision.allow : undefined,
      action_class:
        decision.action_class === undefined
          ? undefined
          : oneOf(decision.action_class, ACTION_CLASSES, 'auto'),
      requires_approval:
        typeof decision.requires_approval === 'boolean' ? decision.requires_approval : undefined,
      reasons: asStringArray(decision.reasons),
    },
    result_hash: typeof o.result_hash === 'string' ? o.result_hash : null,
    error: typeof o.error === 'string' ? o.error : null,
    approved_by: typeof o.approved_by === 'string' ? o.approved_by : null,
    called_at: asString(o.called_at),
    duration_ms: typeof o.duration_ms === 'number' ? o.duration_ms : 0,
  };
}

export function normalizeAction(raw: AgentActionRaw): AgentAction {
  return { ...raw, args: raw.args ?? {}, reasons: raw.reasons ?? [] };
}

export function normalizeRun(raw: AgentRunRecordRaw): AgentRunRecord {
  return {
    ...raw,
    actions: (raw.actions ?? []).map(normalizeAction),
    tools_called: (raw.tools_called ?? []).map(normalizeToolCall),
    minimized_context: raw.minimized_context ?? {},
    model_params: raw.model_params ?? {},
    rule_versions: raw.rule_versions ?? {},
    output: raw.output ?? null,
  };
}

export function normalizeTool(raw: ToolDescriptorRaw): ToolDescriptor {
  return {
    ...raw,
    risk: oneOf(raw.risk, TOOL_RISKS, 'medium'),
    action_class: oneOf(raw.action_class, ACTION_CLASSES, 'requires_approval'),
  };
}

export function normalizeKillSwitchState(raw: KillSwitchStateRaw): KillSwitchState {
  return {
    global: raw.global ?? false,
    agents: raw.agents ?? [],
    tools: raw.tools ?? [],
    tenants: raw.tenants ?? [],
  };
}

export function normalizeKillSwitch(raw: KillSwitchResponseRaw): KillSwitchResponse {
  return {
    effective: normalizeKillSwitchState(raw.effective),
    admin: normalizeKillSwitchState(raw.admin),
  };
}

/**
 * Deriva o nível de autonomia a partir das ferramentas concedidas ao agente. Só ferramentas de
 * escrita contam: leitura (`kind: 'read'` ou nome `*.get_/list_/search_/read_`) nunca torna um
 * agente "autônomo".
 */
export function deriveAutonomy(
  agent: Pick<AgentDescriptor, 'tools'>,
  tools: (Pick<ToolDescriptor, 'name' | 'action_class'> & { kind?: string })[] = [],
): AutonomyLevel {
  const classes = agent.tools.flatMap((name) => {
    const spec = tools.find((t) => t.name === name);
    if (spec) return spec.kind === 'read' ? [] : [spec.action_class];
    const inferred = inferActionClass(name);
    return inferred ? [inferred] : [];
  });
  if (classes.some((c) => c === 'auto')) return 'autonomous';
  if (classes.some((c) => c === 'requires_approval')) return 'approval_required';
  return 'suggest_only';
}

/** Heurística para ferramentas desconhecidas: leitura = ignorada; decisão/fusão = proibida. */
function inferActionClass(toolName: string): ActionClass | undefined {
  if (/\.(get|list|search|read)_/.test(toolName)) return undefined;
  if (/\.(merge|decide|change_priority|transmit)/.test(toolName)) return 'forbidden';
  return 'requires_approval';
}
