import { Badge, cn, type BadgeTone } from '@sus-nexus/design-system';
import { formatDate } from '../lib/format';

export type ConsentState = 'granted' | 'denied' | 'pending' | 'expired' | 'unknown';

export interface ConsentStatus {
  /** Tipo do consentimento ou preferência (ex.: `data_sharing`, `sms`, `whatsapp`). */
  kind: string;
  label: string;
  state: ConsentState;
  updatedAt?: string;
  source?: string;
}

const stateMeta: Record<ConsentState, { label: string; tone: BadgeTone }> = {
  granted: { label: 'Concedido', tone: 'success' },
  denied: { label: 'Negado', tone: 'danger' },
  pending: { label: 'Pendente', tone: 'warning' },
  expired: { label: 'Expirado', tone: 'warning' },
  unknown: { label: 'Não informado', tone: 'neutral' },
};

export interface ConsentStatusIndicatorProps {
  consents: ConsentStatus[];
  compact?: boolean;
  className?: string;
}

/** Consentimentos e preferências de comunicação do cidadão. */
export function ConsentStatusIndicator({
  consents,
  compact,
  className,
}: ConsentStatusIndicatorProps) {
  const blocking = consents.filter((c) => c.state === 'denied' || c.state === 'expired');
  return (
    <div className={cn('flex flex-col gap-1', className)}>
      {blocking.length > 0 ? (
        <p role="status" className="text-sm font-medium text-danger-fg-subtle">
          Atenção: {blocking.map((c) => c.label.toLowerCase()).join(', ')} —{' '}
          {blocking.length === 1 ? 'restrição ativa' : 'restrições ativas'}.
        </p>
      ) : null}
      <ul
        className={cn('flex gap-2', compact ? 'flex-wrap' : 'flex-col')}
        aria-label="Consentimentos"
      >
        {consents.map((c) => {
          const meta = stateMeta[c.state];
          return (
            <li key={c.kind} className="flex items-center gap-2 text-sm">
              <span className={cn(!compact && 'w-48')}>{c.label}</span>
              <Badge tone={meta.tone}>{meta.label}</Badge>
              {!compact && c.updatedAt ? (
                <span className="text-fg-muted">{formatDate(c.updatedAt)}</span>
              ) : null}
              {!compact && c.source ? <span className="text-fg-subtle">via {c.source}</span> : null}
            </li>
          );
        })}
      </ul>
    </div>
  );
}
