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
  status: 'ok' | 'error' | 'skipped';
}

export interface AgentDecision {
  run_id: string;
  agent_id: string;
  agent_version?: string;
  started_at: string;
  finished_at?: string;
  /** Entradas mínimas (já minimizadas/mascaradas pelo serviço de agentes). */
  inputs: Record<string, string>;
  tools: AgentToolCall[];
  output: string;
  rule_id?: string;
  rule_version?: string;
  approval?: {
    required: boolean;
    approved_by?: string;
    approved_at?: string;
    decision?: 'approved' | 'rejected';
  };
}

const actionClassMeta: Record<AgentActionClass, { label: string; tone: BadgeTone }> = {
  auto: { label: 'Automática', tone: 'success' },
  requires_approval: { label: 'Requer aprovação', tone: 'warning' },
  forbidden: { label: 'Proibida', tone: 'danger' },
};

export interface AgentDecisionTraceProps {
  decision: AgentDecision;
  className?: string;
}

/** Rastro de decisão do agente: entradas mínimas, ferramentas, saída, regra/versão, aprovador. */
export function AgentDecisionTrace({ decision, className }: AgentDecisionTraceProps) {
  return (
    <Card
      as="section"
      className={cn('flex flex-col gap-3', className)}
      aria-labelledby={`trace-${decision.run_id}`}
    >
      <CardHeader
        title={<span id={`trace-${decision.run_id}`}>Execução {decision.run_id}</span>}
        description={
          <>
            Agente <code className="font-mono">{decision.agent_id}</code>
            {decision.agent_version ? ` v${decision.agent_version}` : ''} ·{' '}
            {formatDateTime(decision.started_at)}
            {decision.finished_at ? ` → ${formatDateTime(decision.finished_at)}` : ' (em execução)'}
          </>
        }
      />
      <section aria-labelledby={`trace-in-${decision.run_id}`}>
        <h4
          id={`trace-in-${decision.run_id}`}
          className="text-xs font-semibold uppercase tracking-wide text-fg-muted"
        >
          Entradas (mínimas)
        </h4>
        <dl className="mt-1 grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-sm">
          {Object.entries(decision.inputs).map(([k, v]) => (
            <div key={k} className="contents">
              <dt className="font-mono text-fg-muted">{k}</dt>
              <dd>{v}</dd>
            </div>
          ))}
        </dl>
      </section>
      <section aria-labelledby={`trace-tools-${decision.run_id}`}>
        <h4
          id={`trace-tools-${decision.run_id}`}
          className="text-xs font-semibold uppercase tracking-wide text-fg-muted"
        >
          Ferramentas chamadas ({decision.tools.length})
        </h4>
        <ol className="mt-1 flex flex-col gap-1 text-sm">
          {decision.tools.map((t, i) => {
            const meta = actionClassMeta[t.action_class];
            return (
              <li
                key={`${t.name}-${i}`}
                className="flex flex-wrap items-center gap-2 rounded-sm border border-border px-2 py-1"
              >
                <span className="text-fg-muted">{i + 1}.</span>
                <code className="font-mono font-semibold">{t.name}</code>
                <Badge tone={meta.tone}>{meta.label}</Badge>
                <Badge
                  tone={t.status === 'ok' ? 'success' : t.status === 'error' ? 'danger' : 'neutral'}
                >
                  {t.status === 'ok' ? 'OK' : t.status === 'error' ? 'Erro' : 'Ignorada'}
                </Badge>
                {t.duration_ms !== undefined ? (
                  <span className="text-fg-muted">{t.duration_ms} ms</span>
                ) : null}
                {t.summary ? <span className="basis-full text-fg-muted">{t.summary}</span> : null}
              </li>
            );
          })}
        </ol>
      </section>
      <section aria-labelledby={`trace-out-${decision.run_id}`}>
        <h4
          id={`trace-out-${decision.run_id}`}
          className="text-xs font-semibold uppercase tracking-wide text-fg-muted"
        >
          Saída
        </h4>
        <p className="mt-1 whitespace-pre-wrap rounded-sm bg-bg-muted p-2 text-sm">
          {decision.output}
        </p>
      </section>
      <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-sm">
        <dt className="text-fg-muted">Regra</dt>
        <dd>
          {decision.rule_id ?? '—'}
          {decision.rule_version ? (
            <span className="text-fg-muted"> v{decision.rule_version}</span>
          ) : null}
        </dd>
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
