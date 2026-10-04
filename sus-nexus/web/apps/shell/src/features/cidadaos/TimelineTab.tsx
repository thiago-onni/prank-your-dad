'use client';

import { useMemo, useState } from 'react';
import { DOMAINS, useHealthUnits, useTimeline, type Domain } from '@sus-nexus/api-client';
import {
  Badge,
  Button,
  CursorPagination,
  EmptyState,
  Input,
  Select,
} from '@sus-nexus/design-system';
import {
  domainLabels,
  TimelineEvent,
  timelineConfidenceLabels,
} from '@sus-nexus/domain-components';
import { QueryState } from '@/components/QueryState';
import { t } from '@/i18n';

const PERIODS = [
  { value: '30', label: 'Últimos 30 dias' },
  { value: '90', label: 'Últimos 90 dias' },
  { value: '365', label: 'Últimos 12 meses' },
  { value: 'all', label: 'Todo o histórico' },
];

const CONFIDENCE_FILTERS = ['pending', 'divergent', 'unsynced'] as const;

export function TimelineTab({ citizenId }: { citizenId: string }) {
  const [period, setPeriod] = useState('365');
  const [domains, setDomains] = useState<Domain[]>([]);
  const [cnes, setCnes] = useState('');
  const [status, setStatus] = useState('');
  const [cursors, setCursors] = useState<string[]>([]);
  const units = useHealthUnits({ limit: 100 });

  const from = useMemo(() => {
    if (period === 'all') return undefined;
    return new Date(Date.now() - Number(period) * 24 * 3600_000).toISOString();
  }, [period]);

  const query = useTimeline(citizenId, {
    from,
    domain: domains,
    cnes: cnes || undefined,
    status: status || undefined,
    cursor: cursors[cursors.length - 1],
    limit: 50,
  });

  const toggleDomain = (d: Domain) => {
    setDomains((prev) => (prev.includes(d) ? prev.filter((x) => x !== d) : [...prev, d]));
    setCursors([]);
  };

  return (
    <div className="flex flex-col gap-4">
      <fieldset className="grid gap-3 rounded-lg border border-border p-3 md:grid-cols-3">
        <legend className="px-1 text-sm font-semibold">{t.citizen.filters}</legend>
        <Select
          label={t.citizen.period}
          value={period}
          onValueChange={(v) => {
            setPeriod(v);
            setCursors([]);
          }}
          options={PERIODS}
        />
        <Select
          label={t.citizen.unit}
          value={cnes || 'all'}
          onValueChange={(v) => {
            setCnes(v === 'all' ? '' : v);
            setCursors([]);
          }}
          options={[
            { value: 'all', label: t.app.all },
            ...(units.data?.items ?? []).map((u) => ({
              value: u.cnes,
              label: `${u.name} (${u.cnes})`,
            })),
          ]}
        />
        <Input
          label={t.citizen.status}
          value={status}
          onChange={(e) => {
            setStatus(e.target.value);
            setCursors([]);
          }}
          placeholder="ex.: pending, finished"
          list="status-options"
        />
        <datalist id="status-options">
          {[
            'finished',
            'pending',
            'booked',
            'noshow',
            'authorized',
            'open',
            ...CONFIDENCE_FILTERS,
          ].map((s) => (
            <option key={s} value={s} />
          ))}
        </datalist>
        <div className="md:col-span-3">
          <p className="mb-1 text-sm font-semibold" id="domain-filter-label">
            {t.citizen.domain}
          </p>
          <div className="flex flex-wrap gap-2" role="group" aria-labelledby="domain-filter-label">
            {DOMAINS.map((d) => {
              const active = domains.includes(d);
              return (
                <Button
                  key={d}
                  size="sm"
                  variant={active ? 'primary' : 'secondary'}
                  aria-pressed={active}
                  onClick={() => toggleDomain(d)}
                >
                  {domainLabels[d]}
                </Button>
              );
            })}
          </div>
        </div>
      </fieldset>

      <div
        className="flex flex-wrap items-center gap-2 text-xs text-fg-muted"
        aria-label={t.citizen.legend}
      >
        <span className="font-semibold">{t.citizen.legend}:</span>
        <Badge tone={timelineConfidenceLabels.pending.tone} title={t.citizen.legendPending}>
          {timelineConfidenceLabels.pending.label}
        </Badge>
        <Badge tone={timelineConfidenceLabels.divergent.tone} title={t.citizen.legendDivergent}>
          {timelineConfidenceLabels.divergent.label}
        </Badge>
        <Badge tone={timelineConfidenceLabels.unsynced.tone} title={t.citizen.legendUnsynced}>
          {timelineConfidenceLabels.unsynced.label}
        </Badge>
      </div>

      <QueryState
        isLoading={query.isLoading}
        error={query.error}
        data={query.data}
        onRetry={() => void query.refetch()}
      >
        {(page) =>
          page.items.length === 0 ? (
            <EmptyState title={t.citizen.noEvents} />
          ) : (
            <>
              <ol className="flex flex-col gap-3" aria-label={t.citizen.timeline}>
                {page.items.map((event) => (
                  <li key={event.id}>
                    <TimelineEvent event={event} relatedEvents={page.items} />
                  </li>
                ))}
              </ol>
              <CursorPagination
                nextCursor={page.next_cursor}
                hasPrevious={cursors.length > 0}
                isLoading={query.isFetching}
                summary={`${page.items.length} eventos nesta página`}
                onNext={(c) => setCursors((p) => [...p, c])}
                onPrevious={() => setCursors((p) => p.slice(0, -1))}
              />
            </>
          )
        }
      </QueryState>
    </div>
  );
}
