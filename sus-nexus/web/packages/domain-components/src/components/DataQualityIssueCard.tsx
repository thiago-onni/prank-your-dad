import { Badge, Button, Card, cn, type BadgeTone } from '@sus-nexus/design-system';
import { formatDateTime } from '../lib/format';

export interface DataQualityIssue {
  id: string;
  rule: string;
  rule_version?: string;
  severity: 'error' | 'warning' | 'info';
  message: string;
  /** Registro afetado (tipo + id interno). */
  record_type: string;
  record_id: string;
  field?: string;
  source_system: string;
  detected_at?: string;
  suggested_action?: string;
}

const severityMeta: Record<DataQualityIssue['severity'], { label: string; tone: BadgeTone }> = {
  error: { label: 'Erro', tone: 'danger' },
  warning: { label: 'Atenção', tone: 'warning' },
  info: { label: 'Informativo', tone: 'info' },
};

export interface DataQualityIssueCardProps {
  issue: DataQualityIssue;
  onAct?: (issue: DataQualityIssue) => void;
  actionLabel?: string;
  className?: string;
}

/** Regra violada, registro, origem e ação. */
export function DataQualityIssueCard({
  issue,
  onAct,
  actionLabel = 'Abrir tarefa',
  className,
}: DataQualityIssueCardProps) {
  const meta = severityMeta[issue.severity];
  return (
    <Card
      as="article"
      className={cn('flex flex-col gap-2', className)}
      aria-labelledby={`dq-${issue.id}`}
    >
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h3 id={`dq-${issue.id}`} className="font-semibold">
          {issue.message}
        </h3>
        <Badge tone={meta.tone}>{meta.label}</Badge>
      </div>
      <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-sm">
        <dt className="text-fg-muted">Regra</dt>
        <dd>
          <code className="font-mono">{issue.rule}</code>
          {issue.rule_version ? (
            <span className="text-fg-muted"> v{issue.rule_version}</span>
          ) : null}
        </dd>
        <dt className="text-fg-muted">Registro</dt>
        <dd>
          {issue.record_type} <code className="font-mono text-xs">{issue.record_id}</code>
          {issue.field ? <span className="text-fg-muted"> · campo {issue.field}</span> : null}
        </dd>
        <dt className="text-fg-muted">Origem</dt>
        <dd>
          {issue.source_system}
          {issue.detected_at ? (
            <span className="text-fg-muted">
              {' '}
              · detectado em {formatDateTime(issue.detected_at)}
            </span>
          ) : null}
        </dd>
        {issue.suggested_action ? (
          <>
            <dt className="text-fg-muted">Ação</dt>
            <dd>{issue.suggested_action}</dd>
          </>
        ) : null}
      </dl>
      {onAct ? (
        <div className="flex justify-end">
          <Button variant="secondary" size="sm" onClick={() => onAct(issue)}>
            {actionLabel}
          </Button>
        </div>
      ) : null}
    </Card>
  );
}
