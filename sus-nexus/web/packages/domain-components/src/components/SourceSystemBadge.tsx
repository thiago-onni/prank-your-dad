import { Database } from 'lucide-react';
import { Badge } from '@sus-nexus/design-system';
import { formatDateTime } from '../lib/format';

export interface SourceSystemBadgeProps {
  sourceSystem: string;
  syncedAt?: string;
  className?: string;
}

/** Sistema de origem + data de sincronização. */
export function SourceSystemBadge({ sourceSystem, syncedAt, className }: SourceSystemBadgeProps) {
  return (
    <Badge
      tone="neutral"
      className={className}
      title={syncedAt ? `Sincronizado em ${formatDateTime(syncedAt)}` : undefined}
    >
      <Database aria-hidden="true" className="h-3 w-3" />
      <span>{sourceSystem}</span>
      {syncedAt ? (
        <span className="font-normal text-fg-muted">
          <span className="sr-only">, sincronizado em </span>
          <span aria-hidden="true">· </span>
          {formatDateTime(syncedAt)}
        </span>
      ) : null}
    </Badge>
  );
}
