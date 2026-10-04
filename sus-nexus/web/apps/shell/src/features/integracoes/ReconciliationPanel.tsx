'use client';

import { useState } from 'react';
import { useConnectors, useReconciliation } from '@sus-nexus/api-client';
import {
  Badge,
  EmptyState,
  Select,
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

export function ReconciliationPanel() {
  const [connector, setConnector] = useState('');
  const connectors = useConnectors();
  const query = useReconciliation({ connector_id: connector || undefined, limit: 100 });
  return (
    <div className="flex flex-col gap-4">
      <Select
        label={t.integrations.filterConnector}
        value={connector || 'all'}
        onValueChange={(v) => setConnector(v === 'all' ? '' : v)}
        options={[
          { value: 'all', label: t.app.all },
          ...(connectors.data ?? []).map((c) => ({
            value: c.connector_id,
            label: c.source_system,
          })),
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
            <EmptyState title={t.integrations.noReconciliation} />
          ) : (
            <Table aria-label={t.integrations.reconciliation}>
              <TableHead>
                <TableRow>
                  <TableHeaderCell>{t.integrations.connector}</TableHeaderCell>
                  <TableHeaderCell>{t.integrations.entityType}</TableHeaderCell>
                  <TableHeaderCell>{t.integrations.period}</TableHeaderCell>
                  <TableHeaderCell className="text-right">
                    {t.integrations.sourceCount}
                  </TableHeaderCell>
                  <TableHeaderCell className="text-right">
                    {t.integrations.busCount}
                  </TableHeaderCell>
                  <TableHeaderCell className="text-right">{t.integrations.gap}</TableHeaderCell>
                  <TableHeaderCell>{t.integrations.checkedAt}</TableHeaderCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {page.items.map((r, i) => (
                  <TableRow key={`${r.connector_id}-${r.period_start}-${i}`}>
                    <TableCell className="font-mono text-xs">{r.connector_id}</TableCell>
                    <TableCell>{r.entity_type}</TableCell>
                    <TableCell>
                      {formatDateTime(r.period_start)} – {formatDateTime(r.period_end)}
                    </TableCell>
                    <TableCell className="text-right tabular-nums">
                      {r.source_count.toLocaleString('pt-BR')}
                    </TableCell>
                    <TableCell className="text-right tabular-nums">
                      {r.bus_count.toLocaleString('pt-BR')}
                    </TableCell>
                    <TableCell className="text-right tabular-nums">
                      <Badge tone={r.gap === 0 ? 'success' : r.gap > 50 ? 'danger' : 'warning'}>
                        {r.gap}
                      </Badge>
                    </TableCell>
                    <TableCell>{formatDateTime(r.checked_at)}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )
        }
      </QueryState>
    </div>
  );
}
