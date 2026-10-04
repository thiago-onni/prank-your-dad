'use client';

import { useRouter } from 'next/navigation';
import { useState } from 'react';
import {
  useConnectors,
  useIntegrationMessages,
  type IntegrationMessage,
  type IntegrationMessageStatus,
} from '@sus-nexus/api-client';
import {
  Badge,
  Button,
  CursorPagination,
  Input,
  Select,
  VirtualizedTable,
  type VirtualColumn,
} from '@sus-nexus/design-system';
import { formatDateTime, integrationMessageStatusLabels } from '@sus-nexus/domain-components';
import { QueryState } from '@/components/QueryState';
import { t } from '@/i18n';

const STATUSES = Object.keys(integrationMessageStatusLabels) as IntegrationMessageStatus[];

export function MessagesPanel({ initialConnector }: { initialConnector?: string }) {
  const router = useRouter();
  const [connector, setConnector] = useState(initialConnector ?? '');
  const [status, setStatus] = useState<IntegrationMessageStatus | ''>('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [cursors, setCursors] = useState<string[]>([]);
  const cursor = cursors[cursors.length - 1];

  const connectors = useConnectors();
  const query = useIntegrationMessages({
    connector_id: connector || undefined,
    status: status || undefined,
    from: from ? new Date(from).toISOString() : undefined,
    to: to ? new Date(to).toISOString() : undefined,
    cursor,
    limit: 100,
  });

  const resetPaging = () => setCursors([]);

  const columns: VirtualColumn<IntegrationMessage>[] = [
    {
      id: 'received',
      header: t.integrations.receivedAt,
      cell: (m) => formatDateTime(m.received_at),
      width: '150px',
    },
    {
      id: 'connector',
      header: t.integrations.connector,
      cell: (m) => m.source_system,
      width: 'minmax(140px, 1fr)',
    },
    {
      id: 'entity',
      header: t.integrations.entityType,
      cell: (m) => m.entity_type ?? '—',
      width: '130px',
    },
    {
      id: 'status',
      header: t.integrations.status,
      cell: (m) => {
        const meta = integrationMessageStatusLabels[m.status];
        return <Badge tone={meta.tone}>{meta.label}</Badge>;
      },
      width: '140px',
    },
    {
      id: 'attempts',
      header: t.integrations.attempts,
      cell: (m) => m.attempts ?? 1,
      width: '100px',
    },
    {
      id: 'error',
      header: t.integrations.lastError,
      cell: (m) => m.last_error?.message ?? '—',
      width: 'minmax(200px, 2fr)',
    },
  ];

  return (
    <div className="flex flex-col gap-4">
      <form
        className="grid gap-3 md:grid-cols-4"
        aria-label="Filtros de mensagens"
        onSubmit={(e) => {
          e.preventDefault();
          resetPaging();
        }}
      >
        <Select
          label={t.integrations.filterConnector}
          value={connector || 'all'}
          onValueChange={(v) => {
            setConnector(v === 'all' ? '' : v);
            resetPaging();
          }}
          options={[
            { value: 'all', label: t.app.all },
            ...(connectors.data ?? []).map((c) => ({
              value: c.connector_id,
              label: c.source_system,
            })),
          ]}
        />
        <Select
          label={t.integrations.filterStatus}
          value={status || 'all'}
          onValueChange={(v) => {
            setStatus(v === 'all' ? '' : (v as IntegrationMessageStatus));
            resetPaging();
          }}
          options={[
            { value: 'all', label: t.app.all },
            ...STATUSES.map((s) => ({ value: s, label: integrationMessageStatusLabels[s].label })),
          ]}
        />
        <Input
          label={t.integrations.from}
          type="datetime-local"
          value={from}
          onChange={(e) => setFrom(e.target.value)}
        />
        <Input
          label={t.integrations.to}
          type="datetime-local"
          value={to}
          onChange={(e) => setTo(e.target.value)}
        />
        <div className="flex gap-2 md:col-span-4">
          <Button type="submit" variant="secondary" size="sm">
            {t.app.search}
          </Button>
          <Button
            type="button"
            variant="tertiary"
            size="sm"
            onClick={() => {
              setConnector('');
              setStatus('');
              setFrom('');
              setTo('');
              resetPaging();
            }}
          >
            {t.app.clear}
          </Button>
        </div>
      </form>

      <QueryState
        isLoading={query.isLoading}
        error={query.error}
        data={query.data}
        onRetry={() => void query.refetch()}
      >
        {(page) => (
          <>
            <VirtualizedTable
              aria-label={t.integrations.messages}
              rows={page.items}
              columns={columns}
              getRowId={(m) => m.id}
              onRowActivate={(m) => router.push(`/integracoes/mensagens/${m.id}`)}
              emptyMessage={t.integrations.noMessages}
              height={520}
            />
            <CursorPagination
              nextCursor={page.next_cursor}
              hasPrevious={cursors.length > 0}
              isLoading={query.isFetching}
              summary={`${page.items.length} ${t.integrations.messages.toLowerCase()} nesta página`}
              onNext={(c) => setCursors((prev) => [...prev, c])}
              onPrevious={() => setCursors((prev) => prev.slice(0, -1))}
            />
          </>
        )}
      </QueryState>
    </div>
  );
}
