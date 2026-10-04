'use client';

import Link from 'next/link';
import { useState } from 'react';
import { useMergeCases, type MergeCaseStatus } from '@sus-nexus/api-client';
import {
  Badge,
  CursorPagination,
  EmptyState,
  Select,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeaderCell,
  TableRow,
} from '@sus-nexus/design-system';
import { formatDateTime, mergeCaseStatusLabels } from '@sus-nexus/domain-components';
import { QueryState } from '@/components/QueryState';
import { t } from '@/i18n';

const STATUSES = Object.keys(mergeCaseStatusLabels) as MergeCaseStatus[];

export function MergeQueue() {
  const [status, setStatus] = useState<MergeCaseStatus | ''>('open');
  const [cursors, setCursors] = useState<string[]>([]);
  const query = useMergeCases({
    status: status || undefined,
    cursor: cursors[cursors.length - 1],
    limit: 50,
  });

  return (
    <div className="flex flex-col gap-4">
      <Select
        label={t.integrations.filterStatus}
        value={status || 'all'}
        onValueChange={(v) => {
          setStatus(v === 'all' ? '' : (v as MergeCaseStatus));
          setCursors([]);
        }}
        options={[
          { value: 'all', label: t.app.all },
          ...STATUSES.map((s) => ({ value: s, label: mergeCaseStatusLabels[s].label })),
        ]}
        className="max-w-xs"
      />
      <QueryState
        isLoading={query.isLoading}
        error={query.error}
        data={query.data}
        onRetry={() => void query.refetch()}
      >
        {(page) =>
          page.items.length === 0 ? (
            <EmptyState title={t.registry.noCases} />
          ) : (
            <div className="flex flex-col gap-3">
              <Table aria-label={t.registry.reviewQueue}>
                <TableHead>
                  <TableRow>
                    <TableHeaderCell>Caso</TableHeaderCell>
                    <TableHeaderCell>{t.registry.candidates}</TableHeaderCell>
                    <TableHeaderCell>Motivo</TableHeaderCell>
                    <TableHeaderCell>{t.registry.score}</TableHeaderCell>
                    <TableHeaderCell>{t.registry.conflicts}</TableHeaderCell>
                    <TableHeaderCell>{t.integrations.status}</TableHeaderCell>
                    <TableHeaderCell>{t.registry.openedAt}</TableHeaderCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {page.items.map((c) => {
                    const meta = mergeCaseStatusLabels[c.status];
                    return (
                      <TableRow key={c.id}>
                        <TableHeaderCell scope="row">
                          <Link
                            href={`/cadastro/casos/${c.id}`}
                            className="font-mono text-xs text-primary-fg-subtle hover:underline"
                          >
                            {c.id}
                          </Link>
                        </TableHeaderCell>
                        <TableCell>
                          {c.candidates.map((cand) => cand.display_name).join(' × ')}
                        </TableCell>
                        <TableCell>{c.reason ?? '—'}</TableCell>
                        <TableCell className="tabular-nums">
                          {c.score !== undefined
                            ? c.score.toLocaleString('pt-BR', { maximumFractionDigits: 2 })
                            : '—'}
                        </TableCell>
                        <TableCell>
                          {c.conflicts && c.conflicts.length > 0 ? (
                            <span className="flex flex-wrap gap-1">
                              {c.conflicts.map((k) => (
                                <Badge key={k} tone="danger">
                                  {k}
                                </Badge>
                              ))}
                            </span>
                          ) : (
                            '—'
                          )}
                        </TableCell>
                        <TableCell>
                          <Badge tone={meta.tone}>{meta.label}</Badge>
                        </TableCell>
                        <TableCell>{formatDateTime(c.opened_at)}</TableCell>
                      </TableRow>
                    );
                  })}
                </TableBody>
              </Table>
              <CursorPagination
                nextCursor={page.next_cursor}
                hasPrevious={cursors.length > 0}
                isLoading={query.isFetching}
                onNext={(c) => setCursors((p) => [...p, c])}
                onPrevious={() => setCursors((p) => p.slice(0, -1))}
              />
            </div>
          )
        }
      </QueryState>
    </div>
  );
}
