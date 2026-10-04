import { Badge, Card, CardHeader, cn, type BadgeTone } from '@sus-nexus/design-system';
import { formatDateTime } from '../lib/format';

export type AgentActionClass = 'auto' | 'requires_approval' | 'forbidden';

export interface AgentToolCall {
  name: string;
  action_class: AgentActionClass;
  risk?: 'low' | 'medium' | 'high';
  started_at?: string;
  duration_ms?: number;
  /** Resumo mínimo (sem PII). */
  summary?: string;
  status: 'ok' | 'error' | 'skipped' | 'denied' | 'requires_approval';
  /** Decisão da política (OPA) para esta chamada. */
  policy?: { allow?: boolean; requires_approval?: boolean; reasons?: string[] };
  /** Argumentos mascarados (sem PII), já serializados. */
  args?: Record<string, string>;
}

export interface AgentPlannedAction {
  id: string;
  tool: string;
  action_class: AgentActionClass;
  status: string;
  statusLabel?: string;
  statusTone?: BadgeTone;
  reasons?: string[];
  approver?: string;
  justification?: string;
  decided_at?: string;
  error?: string;
}

export interface AgentDecision {
  run_id: string;
  agent_id: string;
  agent_version?: string;
  prompt_version?: string;
  model?: string;
  tenant?: string;
  trigger?: string;
  started_at: string;
  finished_at?: string;
  /** Entradas mínimas (já minimizadas/mascaradas pelo serviço de agentes). */
  inputs: Record<string, string>;
  tools: AgentToolCall[];
  /** Saída estruturada já serializada (JSON legível) ou texto. */
  output: string;
  rule_id?: string;
  rule_version?: string;
  /** Ações planejadas pelo agente (executadas, pendentes, bloqueadas). */
  actions?: AgentPlannedAction[];
  approval?: {
    required: boolean;
    approved_by?: string;
    approved_at?: string;
    decision?: 'approved' | 'rejected';
  };
  error?: string;
}

const actionClassMeta: Record<AgentActionClass, { label: string; tone: BadgeTone }> = {
  auto: { label: 'Automática', tone: 'success' },
  requires_approval: { label: 'Requer aprovação', tone: 'warning' },
  forbidden: { label: 'Proibida', tone: 'danger' },
};

const toolStatusMeta: Record<AgentToolCall['status'], { label: string; tone: BadgeTone }> = {
  ok: { label: 'OK', tone: 'success' },
  error: { label: 'Erro', tone: 'danger' },
  skipped: { label: 'Ignorada', tone: 'neutral' },
  denied: { label: 'Negada pelo OPA', tone: 'danger' },
  requires_approval: { label: 'Aguardando aprovação', tone: 'warning' },
};

export interface AgentDecisionTraceProps {
  decision: AgentDecision;
  className?: string;
}

function SectionTitle({ id, children }: { id: string; children: string }) {
  return (
    <h4 id={id} className="text-xs font-semibold uppercase tracking-wide text-fg-muted">
      {children}
    </h4>
  );
}

/**
 * Rastro de decisão do agente: contexto minimizado, ferramentas chamadas com decisão OPA,
 * saída estruturada, ações planejadas, regra/versão e aprovador (AIA-002/010).
 */
export function AgentDecisionTrace({ decision, className }: AgentDecisionTraceProps) {
  const rid = decision.run_id;
  const inputs = Object.entries(decision.inputs);
  return (
    <Card
      as="section"
      className={cn('flex flex-col gap-4', className)}
      aria-labelledby={`trace-${rid}`}
    >
      <CardHeader
        title={<span id={`trace-${rid}`}>Execução {rid}</span>}
        description={
          <>
            Agente <code className="font-mono">{decision.agent_id}</code>
            {decision.agent_version ? ` v${decision.agent_version}` : ''}
            {decision.prompt_version ? ` · prompt ${decision.prompt_version}` : ''}
            {decision.model ? ` · modelo ${decision.model}` : ''}
            {' · '}
            {formatDateTime(decision.started_at)}
            {decision.finished_at ? ` → ${formatDateTime(decision.finished_at)}` : ' (em execução)'}
            {decision.trigger ? ` · gatilho: ${decision.trigger}` : ''}
          </>
        }
      />

      {decision.error ? (
        <p role="alert" className="rounded-sm border border-danger bg-danger-subtle p-2 text-sm">
          {decision.error}
        </p>
      ) : null}

      <section aria-labelledby={`trace-in-${rid}`}>
        <SectionTitle id={`trace-in-${rid}`}>Contexto minimizado (sem PII)</SectionTitle>
        {inputs.length === 0 ? (
          <p className="mt-1 text-sm text-fg-muted">Nenhum contexto registrado.</p>
        ) : (
          <dl className="mt-1 grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-sm">
            {inputs.map(([k, v]) => (
              <div key={k} className="contents">
                <dt className="font-mono text-fg-muted">{k}</dt>
                <dd className="break-words">{v}</dd>
              </div>
            ))}
          </dl>
        )}
      </section>

      <section aria-labelledby={`trace-tools-${rid}`}>
        <SectionTitle id={`trace-tools-${rid}`}>
          {`Ferramentas chamadas (${decision.tools.length})`}
        </SectionTitle>
        {decision.tools.length === 0 ? (
          <p className="mt-1 text-sm text-fg-muted">Nenhuma ferramenta chamada.</p>
        ) : (
          <ol className="mt-1 flex flex-col gap-1 text-sm">
            {decision.tools.map((t, i) => {
              const meta = actionClassMeta[t.action_class];
              const st = toolStatusMeta[t.status];
              const allow = t.policy?.allow;
              return (
                <li
                  key={`${t.name}-${i}`}
                  className="flex flex-wrap items-center gap-2 rounded-sm border border-border px-2 py-1"
                >
                  <span className="text-fg-muted">{i + 1}.</span>
                  <code className="font-mono font-semibold">{t.name}</code>
                  <Badge tone={meta.tone}>{meta.label}</Badge>
                  <Badge tone={st.tone}>{st.label}</Badge>
                  {allow !== undefined ? (
                    <Badge tone={allow ? 'success' : 'danger'}>
                      OPA: {allow ? 'permitido' : 'negado'}
                      {t.policy?.requires_approval ? ' (com aprovação)' : ''}
                    </Badge>
                  ) : null}
                  {t.duration_ms !== undefined ? (
                    <span className="text-fg-muted">{t.duration_ms} ms</span>
                  ) : null}
                  {t.policy?.reasons && t.policy.reasons.length > 0 ? (
                    <span className="basis-full text-xs text-fg-muted">
                      Motivos: {t.policy.reasons.join('; ')}
                    </span>
                  ) : null}
                  {t.args && Object.keys(t.args).length > 0 ? (
                    <span className="basis-full font-mono text-xs text-fg-muted">
                      {Object.entries(t.args)
                        .map(([k, v]) => `${k}=${v}`)
                        .join(' · ')}
                    </span>
                  ) : null}
                  {t.summary ? <span className="basis-full text-fg-muted">{t.summary}</span> : null}
                </li>
              );
            })}
          </ol>
        )}
      </section>

      <section aria-labelledby={`trace-out-${rid}`}>
        <SectionTitle id={`trace-out-${rid}`}>Saída estruturada</SectionTitle>
        <pre className="mt-1 max-h-80 overflow-auto whitespace-pre-wrap rounded-sm bg-bg-muted p-2 font-mono text-xs">
          {decision.output || '—'}
        </pre>
      </section>

      {decision.actions && decision.actions.length > 0 ? (
        <section aria-labelledby={`trace-actions-${rid}`}>
          <SectionTitle id={`trace-actions-${rid}`}>
            {`Ações planejadas (${decision.actions.length})`}
          </SectionTitle>
          <ul className="mt-1 flex flex-col gap-1 text-sm">
            {decision.actions.map((a) => {
              const meta = actionClassMeta[a.action_class];
              return (
                <li
                  key={a.id}
                  className="flex flex-wrap items-center gap-2 rounded-sm border border-border px-2 py-1"
                >
                  <code className="font-mono font-semibold">{a.tool}</code>
                  <Badge tone={meta.tone}>{meta.label}</Badge>
                  <Badge tone={a.statusTone ?? 'neutral'}>{a.statusLabel ?? a.status}</Badge>
                  <span className="font-mono text-xs text-fg-muted">{a.id}</span>
                  {a.approver ? (
                    <span className="basis-full text-xs text-fg-muted">
                      Decidida por {a.approver}
                      {a.decided_at ? ` em ${formatDateTime(a.decided_at)}` : ''}
                      {a.justification ? ` — “${a.justification}”` : ''}
                    </span>
                  ) : null}
                  {a.reasons && a.reasons.length > 0 ? (
                    <span className="basis-full text-xs text-fg-muted">
                      Motivos: {a.reasons.join('; ')}
                    </span>
                  ) : null}
                  {a.error ? (
                    <span className="basis-full text-xs text-danger-fg-subtle">{a.error}</span>
                  ) : null}
                </li>
              );
            })}
          </ul>
        </section>
      ) : null}

      <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-sm">
        <dt className="text-fg-muted">Regra</dt>
        <dd>
          {decision.rule_id ?? '—'}
          {decision.rule_version ? (
            <span className="text-fg-muted"> v{decision.rule_version}</span>
          ) : null}
        </dd>
        {decision.tenant ? (
          <>
            <dt className="text-fg-muted">Tenant</dt>
            <dd className="font-mono">{decision.tenant}</dd>
          </>
        ) : null}
        <dt className="text-fg-muted">Aprovação</dt>
        <dd>
          {!decision.approval?.required
            ? 'Não exigida'
            : decision.approval.approved_by
              ? `${decision.approval.decision === 'rejected' ? 'Rejeitada' : 'Aprovada'} por ${decision.approval.approved_by} em ${formatDateTime(decision.approval.approved_at)}`
              : 'Pendente'}
        </dd>
      </dl>
    </Card>
  );
}
