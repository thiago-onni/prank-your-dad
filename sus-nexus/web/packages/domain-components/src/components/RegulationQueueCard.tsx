import { Badge, Card, cn, type BadgeTone } from '@sus-nexus/design-system';
import { formatDateTime, formatDuration } from '../lib/format';

export type RegulationPriority = 'emergency' | 'urgent' | 'priority' | 'elective';

export interface RegulationQueueItem {
  id: string;
  citizen_id: string;
  citizen_display_name: string;
  procedure: string;
  priority: RegulationPriority;
  requested_at: string;
  sla_due_at?: string;
  provider?: string;
  pending_items?: string[];
  status: string;
}

const priorityMeta: Record<RegulationPriority, { label: string; tone: BadgeTone }> = {
  emergency: { label: 'Emergência', tone: 'danger' },
  urgent: { label: 'Urgente', tone: 'danger' },
  priority: { label: 'Prioritário', tone: 'warning' },
  elective: { label: 'Eletivo', tone: 'neutral' },
};

export interface RegulationQueueCardProps {
  item: RegulationQueueItem;
  now?: Date;
  onSelect?: (item: RegulationQueueItem) => void;
  className?: string;
}

/** Item da fila de regulação: prioridade, tempo de espera, SLA, pendências, prestador. */
export function RegulationQueueCard({
  item,
  now = new Date(),
  onSelect,
  className,
}: RegulationQueueCardProps) {
  const meta = priorityMeta[item.priority];
  const waiting = now.getTime() - new Date(item.requested_at).getTime();
  const slaRemaining = item.sla_due_at
    ? new Date(item.sla_due_at).getTime() - now.getTime()
    : undefined;
  const slaBreached = slaRemaining !== undefined && slaRemaining < 0;
  const interactive = Boolean(onSelect);

  return (
    <Card
      as="article"
      className={cn(
        'flex flex-col gap-2',
        interactive && 'cursor-pointer hover:border-primary',
        className,
      )}
      aria-labelledby={`reg-${item.id}`}
      tabIndex={interactive ? 0 : undefined}
      role={interactive ? 'button' : undefined}
      onClick={interactive ? () => onSelect?.(item) : undefined}
      onKeyDown={
        interactive
          ? (e) => {
              if (e.key === 'Enter' || e.key === ' ') {
                e.preventDefault();
                onSelect?.(item);
              }
            }
          : undefined
      }
    >
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h3 id={`reg-${item.id}`} className="font-semibold">
          {item.procedure}
        </h3>
        <Badge tone={meta.tone}>{meta.label}</Badge>
      </div>
      <p className="text-sm text-fg-muted">{item.citizen_display_name}</p>
      <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-sm">
        <dt className="text-fg-muted">Solicitado</dt>
        <dd>
          {formatDateTime(item.requested_at)}{' '}
          <span className="text-fg-muted">(aguardando há {formatDuration(waiting)})</span>
        </dd>
        <dt className="text-fg-muted">SLA</dt>
        <dd className={cn(slaBreached && 'font-semibold text-danger-fg-subtle')}>
          {slaRemaining === undefined
            ? '—'
            : slaBreached
              ? `Estourado há ${formatDuration(slaRemaining)}`
              : `Restam ${formatDuration(slaRemaining)}`}
        </dd>
        <dt className="text-fg-muted">Prestador</dt>
        <dd>{item.provider ?? 'Não definido'}</dd>
        <dt className="text-fg-muted">Situação</dt>
        <dd>{item.status}</dd>
      </dl>
      {item.pending_items && item.pending_items.length > 0 ? (
        <div className="text-sm">
          <span className="font-semibold text-warning-fg-subtle">Pendências:</span>{' '}
          {item.pending_items.join('; ')}
        </div>
      ) : null}
    </Card>
  );
}
