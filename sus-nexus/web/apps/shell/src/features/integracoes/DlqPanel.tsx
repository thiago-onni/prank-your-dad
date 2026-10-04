'use client';

import Link from 'next/link';
import { useState } from 'react';
import { useDeadLetters } from '@sus-nexus/api-client';
import {
  CursorPagination,
  EmptyState,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeaderCell,
  TableRow,
} from '@sus-nexus/design-system';
import { formatDateTime } from '@sus-nexus/domain-components';
import { QueryState } from '@/components/QueryState';
import { t } from '@/i18n';

export function DlqPanel() {
  const [cursors, setCursors] = useState<string[]>([]);
  const query = useDeadLetters({ cursor: cursors[cursors.length - 1], limit: 50 });
  return (
    <QueryState
      isLoading={query.isLoading}
      error={query.error}
      data={query.data}
      onRetry={() => void query.refetch()}
    >
      {(page) =>
        page.items.length === 0 ? (
          <EmptyState title={t.integrations.noDlq} />
        ) : (
          <div className="flex flex-col gap-3">
            <Table aria-label={t.integrations.dlq}>
              <TableHead>
                <TableRow>
                  <TableHeaderCell>{t.integrations.createdAt}</TableHeaderCell>
                  <TableHeaderCell>{t.integrations.topic}</TableHeaderCell>
                  <TableHeaderCell>{t.integrations.reason}</TableHeaderCell>
                  <TableHeaderCell>{t.integrations.stage}</TableHeaderCell>
                  <TableHeaderCell>{t.integrations.attempts}</TableHeaderCell>
                  <TableHeaderCell>{t.integrations.owner}</TableHeaderCell>
                  <TableHeaderCell>{t.integrations.triagedAt}</TableHeaderCell>
                  <TableHeaderCell>{t.app.actions}</TableHeaderCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {page.items.map((d) => (
                  <TableRow key={d.id}>
                    <TableCell>{formatDateTime(d.created_at)}</TableCell>
                    <TableCell className="font-mono text-xs">{d.topic ?? '—'}</TableCell>
                    <TableCell>{d.reason}</TableCell>
                    <TableCell>{d.stage ?? '—'}</TableCell>
                    <TableCell>{d.attempts}</TableCell>
                    <TableCell>{d.owner ?? '—'}</TableCell>
                    <TableCell>
                      {d.triaged_at ? formatDateTime(d.triaged_at) : 'Não triada'}
                    </TableCell>
                    <TableCell>
                      <Link
                        href={`/integracoes/mensagens/${d.message_id}`}
                        className="font-semibold text-primary-fg-subtle hover:underline"
                      >
                        {t.integrations.openMessage}
                      </Link>
                    </TableCell>
                  </TableRow>
                ))}
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
  );
}
