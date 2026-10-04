import type { ConnectorHealth } from '@sus-nexus/api-client';
import { Badge, cn } from '@sus-nexus/design-system';
import { formatDateTime } from '../lib/format';
import { connectorHealthLabels } from '../lib/labels';

export interface IntegrationHealthBadgeProps {
  health: ConnectorHealth;
  lastMessageAt?: string;
  showLastMessage?: boolean;
  className?: string;
}

/** Saudável/degradado/parado + última mensagem recebida. */
export function IntegrationHealthBadge({
  health,
  lastMessageAt,
  showLastMessage = true,
  className,
}: IntegrationHealthBadgeProps) {
  const meta = connectorHealthLabels[health];
  const dot: Record<ConnectorHealth, string> = {
    healthy: 'bg-success',
    degraded: 'bg-warning-strong',
    down: 'bg-danger',
    unknown: 'bg-gray-40',
  };
  return (
    <span className={cn('inline-flex flex-wrap items-center gap-2', className)}>
      <Badge tone={meta.tone} data-health={health}>
        <span aria-hidden="true" className={cn('h-2 w-2 rounded-full', dot[health])} />
        {meta.label}
      </Badge>
      {showLastMessage ? (
        <span className="text-xs text-fg-muted">
          Última mensagem:{' '}
          {lastMessageAt ? (
            <time dateTime={lastMessageAt}>{formatDateTime(lastMessageAt)}</time>
          ) : (
            'nunca'
          )}
        </span>
      ) : null}
    </span>
  );
}
