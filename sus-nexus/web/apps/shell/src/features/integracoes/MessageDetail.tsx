'use client';

import Link from 'next/link';
import { useState } from 'react';
import { useIntegrationMessage, useReprocessMessage } from '@sus-nexus/api-client';
import {
  Badge,
  Button,
  Card,
  CardHeader,
  Dialog,
  DialogContent,
  useToast,
} from '@sus-nexus/design-system';
import {
  formatDateTime,
  integrationMessageStatusLabels,
  SourceSystemBadge,
} from '@sus-nexus/domain-components';
import { RefreshCw } from 'lucide-react';
import { PageHeader } from '@/components/PageHeader';
import { QueryState } from '@/components/QueryState';
import { describeError } from '@/lib/problem';
import { t } from '@/i18n';

export function MessageDetail({ messageId }: { messageId: string }) {
  const query = useIntegrationMessage(messageId);
  const reprocess = useReprocessMessage();
  const { toast } = useToast();
  const [confirmOpen, setConfirmOpen] = useState(false);

  const onReprocess = () => {
    reprocess.mutate(messageId, {
      onSuccess: () => {
        toast({ title: t.integrations.reprocessAccepted, tone: 'success' });
        setConfirmOpen(false);
      },
      onError: (error) => {
        const { message, correlationId } = describeError(error);
        toast({
          title: t.integrations.reprocessFailed,
          description: `${message}${correlationId ? ` (${correlationId})` : ''}`,
          tone: 'danger',
        });
      },
    });
  };

  return (
    <QueryState
      isLoading={query.isLoading}
      error={query.error}
      data={query.data}
      onRetry={() => void query.refetch()}
    >
      {(m) => {
        const meta = integrationMessageStatusLabels[m.status];
        const canReprocess =
          m.status === 'failed' || m.status === 'dead_lettered' || m.status === 'processed';
        return (
          <>
            <PageHeader
              title={
                <>
                  {t.integrations.messageDetail}{' '}
                  <code className="font-mono text-lg text-fg-muted">{m.id}</code>
                </>
              }
              description={
                <Link
                  href="/integracoes?tab=mensagens"
                  className="text-primary-fg-subtle hover:underline"
                >
                  ← {t.app.back}
                </Link>
              }
              actions={
                <Button
                  variant="primary"
                  onClick={() => setConfirmOpen(true)}
                  disabled={!canReprocess || reprocess.isPending}
                >
                  <RefreshCw aria-hidden="true" className="h-4 w-4" />
                  {t.integrations.reprocess}
                </Button>
              }
            />
            <div className="grid gap-4 lg:grid-cols-2">
              <Card as="section" aria-labelledby="msg-meta">
                <CardHeader title={<span id="msg-meta">{t.app.details}</span>} />
                <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-2 text-sm">
                  <dt className="text-fg-muted">{t.integrations.status}</dt>
                  <dd>
                    <Badge tone={meta.tone}>{meta.label}</Badge>
                  </dd>
                  <dt className="text-fg-muted">{t.integrations.connector}</dt>
                  <dd>
                    <SourceSystemBadge sourceSystem={m.source_system} />{' '}
                    <code className="font-mono text-xs">{m.connector_id}</code>
                  </dd>
                  <dt className="text-fg-muted">{t.integrations.entityType}</dt>
                  <dd>{m.entity_type ?? '—'}</dd>
                  <dt className="text-fg-muted">Registro de origem</dt>
                  <dd className="font-mono">{m.source_record_id ?? '—'}</dd>
                  <dt className="text-fg-muted">{t.integrations.receivedAt}</dt>
                  <dd>{formatDateTime(m.received_at)}</dd>
                  <dt className="text-fg-muted">{t.integrations.processedAt}</dt>
                  <dd>{formatDateTime(m.processed_at)}</dd>
                  <dt className="text-fg-muted">{t.integrations.attempts}</dt>
                  <dd>{m.attempts ?? 1}</dd>
                  <dt className="text-fg-muted">{t.integrations.correlationId}</dt>
                  <dd className="font-mono select-all">{m.correlation_id ?? '—'}</dd>
                  <dt className="text-fg-muted">{t.integrations.rawRef}</dt>
                  <dd className="break-all font-mono text-xs">
                    {m.raw_ref ?? '—'}
                    {m.raw_sha256 ? (
                      <span className="block text-fg-subtle">sha256 {m.raw_sha256}</span>
                    ) : null}
                  </dd>
                </dl>
              </Card>
              <Card as="section" aria-labelledby="msg-error">
                <CardHeader title={<span id="msg-error">{t.integrations.lastError}</span>} />
                {m.last_error ? (
                  <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-2 text-sm">
                    <dt className="text-fg-muted">Código</dt>
                    <dd className="font-mono">{m.last_error.code}</dd>
                    <dt className="text-fg-muted">{t.integrations.stage}</dt>
                    <dd>{m.last_error.stage ?? '—'}</dd>
                    <dt className="text-fg-muted">Mensagem</dt>
                    <dd>{m.last_error.message}</dd>
                    <dt className="text-fg-muted">Ocorrido em</dt>
                    <dd>{formatDateTime(m.last_error.occurred_at)}</dd>
                  </dl>
                ) : (
                  <p className="text-sm text-fg-muted">Sem erros registrados.</p>
                )}
              </Card>
            </div>
            <Dialog open={confirmOpen} onOpenChange={setConfirmOpen}>
              <DialogContent
                title={t.integrations.reprocess}
                description={t.integrations.reprocessConfirm}
                size="sm"
                footer={
                  <>
                    <Button variant="secondary" onClick={() => setConfirmOpen(false)}>
                      {t.app.cancel}
                    </Button>
                    <Button variant="primary" onClick={onReprocess} loading={reprocess.isPending}>
                      {t.app.confirm}
                    </Button>
                  </>
                }
              >
                <p className="text-sm">
                  <code className="font-mono">{m.id}</code> · {m.source_system}
                </p>
              </DialogContent>
            </Dialog>
          </>
        );
      }}
    </QueryState>
  );
}
