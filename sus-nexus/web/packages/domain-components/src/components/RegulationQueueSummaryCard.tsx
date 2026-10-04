import type { RegulationPriority, RegulationQueueItem } from '@sus-nexus/api-client';
import { Badge, Button, Card, cn } from '@sus-nexus/design-system';
import { regulationPriorityLabels } from '../lib/labels';

export interface RegulationQueueSummaryCardProps {
  item: RegulationQueueItem;
  /** Rótulo do agrupamento (ex.: "Especialidade"). */
  groupLabel?: string;
  selected?: boolean;
  /** Quando presente, o card ganha o botão "Filtrar fila" (alternável, `aria-pressed`). */
  onSelect?: (item: RegulationQueueItem) => void;
  selectLabel?: string;
  className?: string;
}

const numberFormat = new Intl.NumberFormat('pt-BR', { maximumFractionDigits: 1 });
const fmt = (n: number | undefined) => (n === undefined ? '—' : numberFormat.format(n));
const PRIORITY_ORDER: RegulationPriority[] = ['emergency', 'urgent', 'priority', 'elective'];

/**
 * Indicadores da fila por especialidade/procedimento/prestador: abertos, por prioridade,
 * espera média/p90, SLA estourado, pendências e capacidade disponível (REG-004/006).
 */
export function RegulationQueueSummaryCard({
  item,
  groupLabel,
  selected,
  onSelect,
  selectLabel = 'Filtrar fila',
  className,
}: RegulationQueueSummaryCardProps) {
  const interactive = Boolean(onSelect);
  const headingId = `queue-${item.group_key.replace(/[^a-zA-Z0-9_-]/g, '_')}`;
  const breached = item.sla_breached ?? 0;
  const byPriority = PRIORITY_ORDER.filter((p) => (item.by_priority?.[p] ?? 0) > 0);

  return (
    <Card
      as="article"
      className={cn(
        'flex flex-col gap-3',
        selected && 'border-primary ring-2 ring-primary',
        className,
      )}
      aria-labelledby={headingId}
    >
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div className="min-w-0">
          {groupLabel ? (
            <p className="text-xs font-semibold uppercase tracking-wide text-fg-muted">
              {groupLabel}
            </p>
          ) : null}
          <h3 id={headingId} className="truncate text-lg font-semibold leading-tight">
            {item.group_label ?? item.group_key}
          </h3>
        </div>
        <p className="text-right">
          <span className="block text-3xl font-bold leading-none text-primary-fg-subtle">
            {fmt(item.open_requests)}
          </span>
          <span className="text-xs text-fg-muted">abertos</span>
        </p>
      </div>

      {byPriority.length > 0 ? (
        <ul className="flex flex-wrap gap-1" aria-label="Abertos por prioridade">
          {byPriority.map((p) => {
            const meta = regulationPriorityLabels[p];
            return (
              <li key={p}>
                <Badge tone={meta.tone}>
                  {meta.label}: {fmt(item.by_priority?.[p])}
                </Badge>
              </li>
            );
          })}
        </ul>
      ) : null}

      <dl className="grid grid-cols-2 gap-x-3 gap-y-1 text-sm sm:grid-cols-3">
        <div>
          <dt className="text-fg-muted">Espera média</dt>
          <dd className="font-medium">{fmt(item.avg_waiting_days)} dias</dd>
        </div>
        <div>
          <dt className="text-fg-muted">Espera p90</dt>
          <dd className="font-medium">{fmt(item.p90_waiting_days)} dias</dd>
        </div>
        <div>
          <dt className="text-fg-muted">SLA estourado</dt>
          <dd className={cn('font-medium', breached > 0 && 'text-danger-fg-subtle')}>
            {fmt(item.sla_breached)}
          </dd>
        </div>
        <div>
          <dt className="text-fg-muted">Com pendências</dt>
          <dd
            className={cn('font-medium', (item.with_issues ?? 0) > 0 && 'text-warning-fg-subtle')}
          >
            {fmt(item.with_issues)}
          </dd>
        </div>
        <div>
          <dt className="text-fg-muted">Capacidade disp.</dt>
          <dd
            className={cn(
              'font-medium',
              item.capacity_available !== undefined &&
                item.capacity_available < item.open_requests &&
                'text-danger-fg-subtle',
            )}
          >
            {fmt(item.capacity_available)}
          </dd>
        </div>
        <div>
          <dt className="text-fg-muted">Agendados / faltas (30d)</dt>
          <dd className="font-medium">
            {fmt(item.scheduled_30d)} / {fmt(item.no_show_30d)}
          </dd>
        </div>
      </dl>

      {interactive ? (
        <Button
          size="sm"
          variant={selected ? 'primary' : 'secondary'}
          aria-pressed={Boolean(selected)}
          aria-label={`${selectLabel}: ${item.group_label ?? item.group_key}`}
          className="self-start"
          onClick={() => onSelect?.(item)}
        >
          {selectLabel}
        </Button>
      ) : null}
    </Card>
  );
}
