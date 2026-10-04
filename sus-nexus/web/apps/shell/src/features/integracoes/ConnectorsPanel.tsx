'use client';

import Link from 'next/link';
import { useConnectors } from '@sus-nexus/api-client';
import { Card, EmptyState } from '@sus-nexus/design-system';
import { IntegrationHealthBadge } from '@sus-nexus/domain-components';
import { QueryState } from '@/components/QueryState';
import { t } from '@/i18n';

export function ConnectorsPanel() {
  const query = useConnectors({ refetchInterval: 30_000 });
  return (
    <QueryState
      isLoading={query.isLoading}
      error={query.error}
      data={query.data}
      onRetry={() => void query.refetch()}
    >
      {(connectors) =>
        connectors.length === 0 ? (
          <EmptyState title={t.app.none} />
        ) : (
          <ul
            className="grid gap-4 md:grid-cols-2 xl:grid-cols-3"
            aria-label={t.integrations.connectors}
          >
            {connectors.map((c) => (
              <li key={c.connector_id}>
                <Card
                  as="article"
                  className="flex h-full flex-col gap-3"
                  aria-labelledby={`conn-${c.connector_id}`}
                >
                  <div className="flex flex-wrap items-start justify-between gap-2">
                    <div>
                      <h3 id={`conn-${c.connector_id}`} className="text-lg font-semibold">
                        {c.source_system}
                      </h3>
                      <p className="text-xs text-fg-muted">
                        <code className="font-mono">{c.connector_id}</code> · v{c.connector_version}
                      </p>
                    </div>
                    <IntegrationHealthBadge
                      health={c.health}
                      lastMessageAt={c.last_message_at}
                      showLastMessage={false}
                    />
                  </div>
                  <IntegrationHealthBadge
                    health={c.health}
                    lastMessageAt={c.last_message_at}
                    className="[&>span:first-child]:hidden"
                  />
                  <dl className="grid grid-cols-2 gap-x-4 gap-y-1 text-sm">
                    <dt className="text-fg-muted">{t.integrations.received24h}</dt>
                    <dd className="text-right font-medium tabular-nums">
                      {(c.received_24h ?? 0).toLocaleString('pt-BR')}
                    </dd>
                    <dt className="text-fg-muted">{t.integrations.failed24h}</dt>
                    <dd
                      className={`text-right font-medium tabular-nums ${c.failed_24h ? 'text-danger-fg-subtle' : ''}`}
                    >
                      {(c.failed_24h ?? 0).toLocaleString('pt-BR')}
                    </dd>
                    <dt className="text-fg-muted">{t.integrations.dlqOpen}</dt>
                    <dd
                      className={`text-right font-medium tabular-nums ${c.dlq_open ? 'text-danger-fg-subtle' : ''}`}
                    >
                      {c.dlq_open ?? 0}
                    </dd>
                    <dt className="text-fg-muted">{t.integrations.gap}</dt>
                    <dd
                      className={`text-right font-medium tabular-nums ${c.reconciliation_gap ? 'text-warning-fg-subtle' : ''}`}
                    >
                      {c.reconciliation_gap ?? 0}
                    </dd>
                  </dl>
                  <Link
                    href={`/integracoes?tab=mensagens&connector=${encodeURIComponent(c.connector_id)}`}
                    className="mt-auto text-sm font-semibold text-primary-fg-subtle hover:underline"
                  >
                    Ver mensagens de {c.source_system}
                  </Link>
                </Card>
              </li>
            ))}
          </ul>
        )
      }
    </QueryState>
  );
}
