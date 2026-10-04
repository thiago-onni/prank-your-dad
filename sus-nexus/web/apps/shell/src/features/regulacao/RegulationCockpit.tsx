'use client';

import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useMemo, useState } from 'react';
import {
  useHealthUnits,
  useProviderCapacity,
  useRegulationQueueSummary,
  useRegulationRequests,
  type RegulationIssueFilter,
  type RegulationPriority,
  type RegulationQueueGroupBy,
  type RegulationQueueItem,
  type RegulationRequest,
  type RegulationSort,
  type RegulationStatus,
} from '@sus-nexus/api-client';
import {
  Badge,
  Button,
  EmptyState,
  Input,
  Select,
  VirtualizedTable,
  type VirtualColumn,
} from '@sus-nexus/design-system';
import {
  RegulationQueueSummaryCard,
  TaskSLAIndicator,
  regulationIssueFilterLabels,
  regulationPriorityLabels,
  regulationQueueGroupByLabels,
  regulationStatusLabels,
} from '@sus-nexus/domain-components';
import { PageHeader } from '@/components/PageHeader';
import { PurposeRequired } from '@/components/PurposeRequired';
import { QueryState } from '@/components/QueryState';
import { t } from '@/i18n';
import { RegulationSubnav } from './RegulationSubnav';

const OPEN: RegulationStatus[] = [
  'requested',
  'pending_documents',
  'returned',
  'under_review',
  'authorized',
];
const GROUP_BYS = Object.keys(regulationQueueGroupByLabels) as RegulationQueueGroupBy[];
const STATUSES = Object.keys(regulationStatusLabels) as RegulationStatus[];
const PRIORITIES = Object.keys(regulationPriorityLabels) as RegulationPriority[];
const ISSUES = Object.keys(regulationIssueFilterLabels) as RegulationIssueFilter[];
const SORTS: { value: RegulationSort; label: string }[] = [
  { value: 'waiting_time_desc', label: t.regulation.sortWaiting },
  { value: 'priority_desc', label: t.regulation.sortPriority },
  { value: 'created_at_asc', label: t.regulation.sortCreated },
];

interface Filters {
  status: RegulationStatus | '';
  priority: RegulationPriority | '';
  specialty: string;
  service_code: string;
  requesting_cnes: string;
  provider_cnes: string;
  issue: RegulationIssueFilter | '';
  sort: RegulationSort;
}

const EMPTY: Filters = {
  status: '',
  priority: '',
  specialty: '',
  service_code: '',
  requesting_cnes: '',
  provider_cnes: '',
  issue: '',
  sort: 'waiting_time_desc',
};

/** Aplica a seleção de um card de resumo como filtro da lista. */
function filterFromGroup(groupBy: RegulationQueueGroupBy, item: RegulationQueueItem): Partial<Filters> {
  switch (groupBy) {
    case 'specialty':
      return { specialty: item.group_key };
    case 'service_code':
      return { service_code: item.group_key };
    case 'provider_cnes':
      return { provider_cnes: item.group_key };
    case 'requesting_cnes':
      return { requesting_cnes: item.group_key };
    case 'priority':
      return { priority: item.group_key as RegulationPriority };
  }
}

export function slaTask(r: RegulationRequest) {
  return {
    due_at: r.sla_due_at,
    overdue: r.sla_breached,
    status: OPEN.includes(r.status) ? ('open' as const) : ('completed' as const),
    sla_policy_id: undefined,
  };
}

export function RegulationCockpit() {
  const router = useRouter();
  const [groupBy, setGroupBy] = useState<RegulationQueueGroupBy>('specialty');
  const [selectedGroup, setSelectedGroup] = useState<string | null>(null);
  const [filters, setFilters] = useState<Filters>(EMPTY);

  const summary = useRegulationQueueSummary(groupBy);
  const requests = useRegulationRequests({
    status: filters.status || undefined,
    priority: filters.priority || undefined,
    specialty: filters.specialty,
    service_code: filters.service_code,
    requesting_cnes: filters.requesting_cnes,
    provider_cnes: filters.provider_cnes,
    issue: filters.issue || undefined,
    sort: filters.sort,
    limit: 200,
  });
  const units = useHealthUnits({ limit: 100 });
  const capacity = useProviderCapacity({ limit: 200 });
  const providers = useMemo(() => {
    const map = new Map<string, string>();
    for (const c of capacity.data?.items ?? []) map.set(c.provider_cnes, c.provider_name ?? c.provider_cnes);
    return [...map.entries()].map(([value, label]) => ({ value, label: `${label} (${value})` }));
  }, [capacity.data]);

  const update = (patch: Partial<Filters>) => setFilters((f) => ({ ...f, ...patch }));
  const onGroupSelect = (item: RegulationQueueItem) => {
    if (selectedGroup === item.group_key) {
      setSelectedGroup(null);
      setFilters((f) => ({ ...f, ...Object.fromEntries(Object.keys(filterFromGroup(groupBy, item)).map((k) => [k, ''])) }));
      return;
    }
    setSelectedGroup(item.group_key);
    update(filterFromGroup(groupBy, item));
  };

  const columns: VirtualColumn<RegulationRequest>[] = [
    {
      id: 'priority',
      header: t.regulation.priority,
      width: '130px',
      cell: (r) => {
        const meta = regulationPriorityLabels[r.priority ?? 'elective'];
        return <Badge tone={meta.tone}>{meta.label}</Badge>;
      },
    },
    {
      id: 'service',
      header: t.regulation.service,
      width: 'minmax(220px, 2fr)',
      cell: (r) => (
        <span className="block truncate">
          <Link
            href={`/regulacao/${r.id}`}
            className="font-medium text-primary-fg-subtle hover:underline"
            onClick={(e) => e.stopPropagation()}
          >
            {r.service_description ?? r.requested_service_code}
          </Link>
          <span className="block text-xs text-fg-muted">
            {r.specialty ?? '—'} · {r.requested_service_code}
          </span>
        </span>
      ),
    },
    {
      id: 'citizen',
      header: t.regulation.citizen,
      width: '140px',
      cell: (r) => (
        <Link
          href={`/cidadaos/${r.citizen_id}`}
          className="font-mono text-xs text-primary-fg-subtle hover:underline"
          onClick={(e) => e.stopPropagation()}
        >
          {r.citizen_id.slice(0, 12)}…
        </Link>
      ),
    },
    {
      id: 'status',
      header: t.regulation.status,
      width: '170px',
      cell: (r) => {
        const meta = regulationStatusLabels[r.status];
        return <Badge tone={meta.tone}>{meta.label}</Badge>;
      },
    },
    {
      id: 'waiting',
      header: t.regulation.waiting,
      width: '90px',
      cell: (r) => `${r.waiting_days} ${t.regulation.waitingDays}`,
    },
    {
      id: 'sla',
      header: t.regulation.sla,
      width: '190px',
      cell: (r) => <TaskSLAIndicator task={slaTask(r)} />,
    },
    {
      id: 'provider',
      header: t.regulation.provider,
      width: 'minmax(160px, 1fr)',
      cell: (r) => r.provider_name ?? '—',
    },
    {
      id: 'issues',
      header: t.regulation.issues,
      width: '110px',
      cell: (r) => {
        const open = (r.issues ?? []).filter((i) => i.status === 'open').length;
        return open > 0 ? <Badge tone="warning">{open}</Badge> : <span className="text-fg-muted">—</span>;
      },
    },
  ];

  return (
    <>
      <PageHeader title={t.regulation.title} description={t.regulation.description} />
      <RegulationSubnav />

      <section aria-labelledby="queue-summary" className="mb-6">
        <div className="mb-3 flex flex-wrap items-end justify-between gap-3">
          <h2 id="queue-summary" className="text-xl font-semibold">
            {t.regulation.queueView}
          </h2>
          <Select
            label={t.regulation.groupBy}
            value={groupBy}
            onValueChange={(v) => {
              setGroupBy(v as RegulationQueueGroupBy);
              setSelectedGroup(null);
              setFilters(EMPTY);
            }}
            options={GROUP_BYS.map((g) => ({ value: g, label: regulationQueueGroupByLabels[g] }))}
            className="w-56"
          />
        </div>
        <QueryState
          isLoading={summary.isLoading}
          error={summary.error}
          data={summary.data}
          onRetry={() => void summary.refetch()}
          skeletonRows={3}
        >
          {(data) =>
            data.items.length === 0 ? (
              <EmptyState title={t.regulation.noSummary} />
            ) : (
              <ul className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3" aria-label={t.regulation.queueView}>
                {data.items.map((item) => (
                  <li key={item.group_key}>
                    <RegulationQueueSummaryCard
                      item={item}
                      groupLabel={regulationQueueGroupByLabels[groupBy]}
                      selected={selectedGroup === item.group_key}
                      onSelect={onGroupSelect}
                      className="h-full"
                    />
                  </li>
                ))}
              </ul>
            )
          }
        </QueryState>
      </section>

      <section aria-labelledby="requests-title">
        <div className="mb-3 flex flex-wrap items-center justify-between gap-3">
          <h2 id="requests-title" className="text-xl font-semibold">
            {t.regulation.requests}
          </h2>
          {selectedGroup ? (
            <Button
              variant="tertiary"
              size="sm"
              onClick={() => {
                setSelectedGroup(null);
                setFilters(EMPTY);
              }}
            >
              {t.regulation.clearSelection}
            </Button>
          ) : null}
        </div>
        <fieldset className="mb-4 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <legend className="sr-only">{t.regulation.filters}</legend>
          <Select
            label={t.regulation.status}
            value={filters.status || 'all'}
            onValueChange={(v) => update({ status: v === 'all' ? '' : (v as RegulationStatus) })}
            options={[
              { value: 'all', label: t.app.all },
              ...STATUSES.map((s) => ({ value: s, label: regulationStatusLabels[s].label })),
            ]}
          />
          <Select
            label={t.regulation.priority}
            value={filters.priority || 'all'}
            onValueChange={(v) => update({ priority: v === 'all' ? '' : (v as RegulationPriority) })}
            options={[
              { value: 'all', label: t.app.all },
              ...PRIORITIES.map((p) => ({ value: p, label: regulationPriorityLabels[p].label })),
            ]}
          />
          <Input
            label={t.regulation.specialty}
            value={filters.specialty}
            onChange={(e) => update({ specialty: e.target.value })}
          />
          <Input
            label={t.regulation.service}
            value={filters.service_code}
            onChange={(e) => update({ service_code: e.target.value.replace(/\D/g, '') })}
            inputMode="numeric"
          />
          <Select
            label={t.regulation.requestingCnes}
            value={filters.requesting_cnes || 'all'}
            onValueChange={(v) => update({ requesting_cnes: v === 'all' ? '' : v })}
            options={[
              { value: 'all', label: t.app.all },
              ...(units.data?.items ?? []).map((u) => ({ value: u.cnes, label: `${u.name} (${u.cnes})` })),
            ]}
          />
          <Select
            label={t.regulation.providerCnes}
            value={filters.provider_cnes || 'all'}
            onValueChange={(v) => update({ provider_cnes: v === 'all' ? '' : v })}
            options={[{ value: 'all', label: t.app.all }, ...providers]}
          />
          <Select
            label={t.regulation.issue}
            value={filters.issue || 'all'}
            onValueChange={(v) => update({ issue: v === 'all' ? '' : (v as RegulationIssueFilter) })}
            options={[
              { value: 'all', label: t.app.all },
              ...ISSUES.map((i) => ({ value: i, label: regulationIssueFilterLabels[i] })),
            ]}
          />
          <Select
            label={t.regulation.sort}
            value={filters.sort}
            onValueChange={(v) => update({ sort: v as RegulationSort })}
            options={SORTS}
          />
        </fieldset>

        <PurposeRequired>
          <QueryState
            isLoading={requests.isLoading}
            error={requests.error}
            data={requests.data}
            onRetry={() => void requests.refetch()}
          >
            {(page) => (
              <div className="flex flex-col gap-2">
                <p className="text-sm text-fg-muted" role="status">
                  {page.items.length} {t.regulation.showingCount}
                  {page.next_cursor ? ' (há mais resultados; refine os filtros)' : ''}
                </p>
                <VirtualizedTable
                  rows={page.items}
                  columns={columns}
                  getRowId={(r) => r.id}
                  aria-label={t.regulation.requests}
                  rowHeight={56}
                  height={560}
                  onRowActivate={(r) => router.push(`/regulacao/${r.id}`)}
                  emptyMessage={t.regulation.noRequests}
                />
              </div>
            )}
          </QueryState>
        </PurposeRequired>
      </section>
    </>
  );
}
