'use client';

import { useState } from 'react';
import type { TimelineEvent as TimelineEventData } from '@sus-nexus/api-client';
import { Badge, cn } from '@sus-nexus/design-system';
import { ChevronDown, ChevronRight, Link2 } from 'lucide-react';
import { formatDateTime } from '../lib/format';
import { domainLabels, timelineConfidenceLabels } from '../lib/labels';
import { SourceSystemBadge } from './SourceSystemBadge';

export interface TimelineEventProps {
  event: TimelineEventData;
  /** Eventos relacionados pela cadeia de correlação (causa → efeito). */
  relatedEvents?: TimelineEventData[];
  onOpenDetail?: (event: TimelineEventData) => void;
  className?: string;
}

const domainColors: Record<TimelineEventData['domain'], string> = {
  identity: 'border-l-gray-50',
  aps: 'border-l-green-cool-vivid-50',
  schedule: 'border-l-blue-warm-vivid-70',
  regulation: 'border-l-yellow-vivid-40',
  exam: 'border-l-blue-warm-vivid-50',
  hospital: 'border-l-red-vivid-50',
  careplan: 'border-l-green-cool-vivid-70',
  task: 'border-l-gray-40',
  production: 'border-l-gray-60',
  communication: 'border-l-blue-warm-vivid-40',
};

/**
 * Evento da linha do tempo: tipo, datas de ocorrência/registro, unidade/CNES, status,
 * confiança (pendente/divergente/não sincronizado) e cadeia causa-efeito expansível.
 */
export function TimelineEvent({
  event,
  relatedEvents = [],
  onOpenDetail,
  className,
}: TimelineEventProps) {
  const [expanded, setExpanded] = useState(false);
  const confidence = event.confidence ? timelineConfidenceLabels[event.confidence] : undefined;
  const hasChain = (event.correlation_chain?.length ?? 0) > 0;
  const chainId = `chain-${event.id}`;
  const lag = new Date(event.recorded_at).getTime() - new Date(event.occurred_at).getTime();
  const lateRecording = lag > 24 * 3600 * 1000;

  return (
    <article
      className={cn(
        'rounded-md border border-border border-l-4 bg-surface p-3',
        domainColors[event.domain],
        event.confidence === 'unsynced' && 'border-dashed opacity-90',
        className,
      )}
      aria-labelledby={`evt-title-${event.id}`}
    >
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div className="min-w-0">
          <div className="flex flex-wrap items-center gap-2">
            <Badge tone="primary">{domainLabels[event.domain]}</Badge>
            <h3 id={`evt-title-${event.id}`} className="text-base font-semibold text-fg">
              {event.summary ?? event.event_type}
            </h3>
          </div>
          <p className="mt-1 text-xs text-fg-muted">
            <code className="font-mono">{event.event_type}</code>
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <Badge tone="neutral">{event.status}</Badge>
          {confidence ? <Badge tone={confidence.tone}>{confidence.label}</Badge> : null}
          {event.sensitivity === 'restricted' || event.sensitivity === 'highly_restricted' ? (
            <Badge tone="danger">Restrito</Badge>
          ) : null}
        </div>
      </div>

      <dl className="mt-2 grid gap-x-6 gap-y-1 text-sm sm:grid-cols-2">
        <div>
          <dt className="inline text-fg-muted">Ocorrência: </dt>
          <dd className="inline font-medium">
            <time dateTime={event.occurred_at}>{formatDateTime(event.occurred_at)}</time>
          </dd>
        </div>
        <div>
          <dt className="inline text-fg-muted">Registro: </dt>
          <dd className="inline font-medium">
            <time dateTime={event.recorded_at}>{formatDateTime(event.recorded_at)}</time>
            {lateRecording ? (
              <span className="ml-1 text-xs text-warning-fg-subtle">(registro tardio)</span>
            ) : null}
          </dd>
        </div>
        <div>
          <dt className="inline text-fg-muted">Unidade: </dt>
          <dd className="inline font-medium">
            {event.health_unit_name ?? '—'}
            {event.cnes ? <span className="text-fg-muted"> · CNES {event.cnes}</span> : null}
          </dd>
        </div>
        <div>
          <dt className="inline text-fg-muted">Origem: </dt>
          <dd className="inline">
            <SourceSystemBadge sourceSystem={event.source_system} />
          </dd>
        </div>
      </dl>

      <div className="mt-2 flex flex-wrap items-center gap-3">
        {hasChain ? (
          <button
            type="button"
            className="inline-flex items-center gap-1 text-sm font-semibold text-primary-fg-subtle hover:underline"
            aria-expanded={expanded}
            aria-controls={chainId}
            onClick={() => setExpanded((v) => !v)}
          >
            {expanded ? (
              <ChevronDown aria-hidden="true" className="h-4 w-4" />
            ) : (
              <ChevronRight aria-hidden="true" className="h-4 w-4" />
            )}
            Cadeia causa-efeito ({event.correlation_chain?.length})
          </button>
        ) : null}
        {onOpenDetail ? (
          <button
            type="button"
            className="text-sm font-semibold text-primary-fg-subtle hover:underline"
            onClick={() => onOpenDetail(event)}
          >
            Ver detalhe
          </button>
        ) : null}
      </div>

      {hasChain ? (
        <ol
          id={chainId}
          hidden={!expanded}
          className="mt-2 flex flex-col gap-1 border-t border-border pt-2 text-sm"
        >
          {event.correlation_chain?.map((id, i) => {
            const related = relatedEvents.find((r) => r.id === id);
            return (
              <li key={id} className="flex items-center gap-2">
                <Link2 aria-hidden="true" className="h-3 w-3 text-fg-subtle" />
                <span className="text-fg-muted">{i + 1}.</span>
                {related ? (
                  <span>
                    <span className="font-medium">{related.summary ?? related.event_type}</span>
                    <span className="text-fg-muted"> — {formatDateTime(related.occurred_at)}</span>
                  </span>
                ) : (
                  <code className="font-mono text-xs">{id}</code>
                )}
              </li>
            );
          })}
        </ol>
      ) : null}
    </article>
  );
}
