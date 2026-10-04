'use client';

import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useState } from 'react';
import {
  useExamOrders,
  useHealthUnits,
  type ExamIssue,
  type ExamOrder,
  type ExamOrderStatus,
} from '@sus-nexus/api-client';
import { Badge, Select, VirtualizedTable, type VirtualColumn } from '@sus-nexus/design-system';
import { examIssueLabels, examStatusLabels, formatDateTime } from '@sus-nexus/domain-components';
import { PageHeader } from '@/components/PageHeader';
import { PurposeRequired } from '@/components/PurposeRequired';
import { QueryState } from '@/components/QueryState';
import { t } from '@/i18n';

const STATUSES = Object.keys(examStatusLabels) as ExamOrderStatus[];
const ISSUES = Object.keys(examIssueLabels) as ExamIssue[];

export function ExamsPage() {
  const router = useRouter();
  const [status, setStatus] = useState<ExamOrderStatus | ''>('');
  const [issue, setIssue] = useState<ExamIssue | ''>('');
  const [cnes, setCnes] = useState('');
  const units = useHealthUnits({ limit: 100 });
  const query = useExamOrders({
    status: status || undefined,
    issue: issue || undefined,
    requesting_cnes: cnes,
    limit: 200,
  });

  const columns: VirtualColumn<ExamOrder>[] = [
    {
      id: 'exam',
      header: t.exams.exam,
      width: 'minmax(220px, 2fr)',
      cell: (o) => (
        <span className="block truncate">
          <Link
            href={`/exames/${o.id}`}
            className="font-medium text-primary-fg-subtle hover:underline"
            onClick={(e) => e.stopPropagation()}
          >
            {o.exam_description ?? o.exam_code}
          </Link>
          <span className="block text-xs text-fg-muted">
            {o.exam_code} · {o.requesting_unit_name ?? o.requesting_cnes ?? '—'}
          </span>
        </span>
      ),
    },
    {
      id: 'citizen',
      header: t.exams.citizen,
      width: '140px',
      cell: (o) => (
        <Link
          href={`/cidadaos/${o.citizen_id}`}
          className="font-mono text-xs text-primary-fg-subtle hover:underline"
          onClick={(e) => e.stopPropagation()}
        >
          {o.citizen_id.slice(0, 12)}…
        </Link>
      ),
    },
    {
      id: 'status',
      header: t.exams.status,
      width: '140px',
      cell: (o) => {
        const meta = examStatusLabels[o.status];
        return <Badge tone={meta.tone}>{meta.label}</Badge>;
      },
    },
    {
      id: 'issues',
      header: t.exams.issue,
      width: 'minmax(160px, 1fr)',
      cell: (o) =>
        (o.issues ?? []).length === 0 ? (
          <span className="text-fg-muted">—</span>
        ) : (
          <span className="flex flex-wrap gap-1">
            {(o.issues ?? []).map((i) => (
              <Badge key={i} tone={examIssueLabels[i].tone}>
                {examIssueLabels[i].label}
              </Badge>
            ))}
          </span>
        ),
    },
    {
      id: 'requested',
      header: t.exams.requestedAt,
      width: '150px',
      cell: (o) => formatDateTime(o.requested_at),
    },
    {
      id: 'scheduled',
      header: t.exams.scheduledAt,
      width: '150px',
      cell: (o) => formatDateTime(o.scheduled_at),
    },
    {
      id: 'results',
      header: t.exams.results,
      width: '110px',
      cell: (o) => {
        const n = o.results?.length ?? 0;
        const critical = o.results?.some((r) => r.critical);
        return n === 0 ? (
          <span className="text-fg-muted">—</span>
        ) : (
          <Badge tone={critical ? 'danger' : 'success'}>
            {n}
            {critical ? ` · ${t.exams.critical}` : ''}
          </Badge>
        );
      },
    },
  ];

  return (
    <>
      <PageHeader title={t.exams.title} description={t.exams.description} />
      <fieldset className="mb-4 grid gap-3 sm:grid-cols-3">
        <legend className="sr-only">Filtros</legend>
        <Select
          label={t.exams.status}
          value={status || 'all'}
          onValueChange={(v) => setStatus(v === 'all' ? '' : (v as ExamOrderStatus))}
          options={[
            { value: 'all', label: t.app.all },
            ...STATUSES.map((s) => ({ value: s, label: examStatusLabels[s].label })),
          ]}
        />
        <Select
          label={t.exams.issue}
          value={issue || 'all'}
          onValueChange={(v) => setIssue(v === 'all' ? '' : (v as ExamIssue))}
          options={[
            { value: 'all', label: t.app.all },
            ...ISSUES.map((i) => ({ value: i, label: examIssueLabels[i].label })),
          ]}
        />
        <Select
          label={t.exams.requestingCnes}
          value={cnes || 'all'}
          onValueChange={(v) => setCnes(v === 'all' ? '' : v)}
          options={[
            { value: 'all', label: t.app.all },
            ...(units.data?.items ?? []).map((u) => ({
              value: u.cnes,
              label: `${u.name} (${u.cnes})`,
            })),
          ]}
        />
      </fieldset>
      <PurposeRequired>
        <QueryState
          isLoading={query.isLoading}
          error={query.error}
          data={query.data}
          onRetry={() => void query.refetch()}
        >
          {(page) => (
            <VirtualizedTable
              rows={page.items}
              columns={columns}
              getRowId={(o) => o.id}
              aria-label={t.exams.title}
              rowHeight={56}
              height={560}
              onRowActivate={(o) => router.push(`/exames/${o.id}`)}
              emptyMessage={t.exams.noOrders}
            />
          )}
        </QueryState>
      </PurposeRequired>
    </>
  );
}
