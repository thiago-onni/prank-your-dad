'use client';

import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useState } from 'react';
import {
  useCreateProductionBatch,
  useHealthUnits,
  useProductionBatches,
  useProductionDeadlines,
  type ProductionBatchStatus,
  type ProductionKind,
} from '@sus-nexus/api-client';
import {
  Badge,
  Button,
  Card,
  CardHeader,
  CursorPagination,
  EmptyState,
  Select,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeaderCell,
  TableRow,
  useToast,
} from '@sus-nexus/design-system';
import {
  formatCompetence,
  formatCurrency,
  formatDateTime,
  productionBatchStatusLabels,
  productionKindLabels,
} from '@sus-nexus/domain-components';
import { QueryState } from '@/components/QueryState';
import { describeError } from '@/lib/problem';
import { format, t } from '@/i18n';
import { PRODUCTION_KINDS } from './format';
import { useProductionPermissions } from './permissions';

const BATCH_STATUSES = Object.keys(productionBatchStatusLabels) as ProductionBatchStatus[];

/** Gera lote rascunho somente com registros `validated` (PRO-005). Visível ao auditor. */
function CreateBatchCard() {
  const router = useRouter();
  const { toast } = useToast();
  const deadlines = useProductionDeadlines();
  const units = useHealthUnits({ limit: 100 });
  const mutation = useCreateProductionBatch();
  const [competence, setCompetence] = useState('');
  const [cnes, setCnes] = useState('');
  const [kind, setKind] = useState<ProductionKind | ''>('');
  const [error, setError] = useState<string | undefined>();

  const submit = () => {
    if (!competence || !cnes || !kind) {
      setError(t.production.selectRequired);
      return;
    }
    setError(undefined);
    mutation.mutate(
      { competence, cnes, kind },
      {
        onSuccess: (batch) => {
          toast({
            title: format(t.production.batchCreated, {
              id: batch.id,
              count: batch.records_count,
            }),
            tone: 'success',
          });
          router.push(`/producao/lotes/${batch.id}`);
        },
        onError: (err) => {
          const { message, correlationId } = describeError(err);
          toast({
            title: t.production.batchCreateFailed,
            description: `${message}${correlationId ? ` (${correlationId})` : ''}`,
            tone: 'danger',
          });
        },
      },
    );
  };

  return (
    <Card as="section" aria-labelledby="production-create-batch">
      <CardHeader
        headingLevel={2}
        title={<span id="production-create-batch">{t.production.createBatchTitle}</span>}
        description={t.production.createBatchDescription}
      />
      <form
        noValidate
        onSubmit={(e) => {
          e.preventDefault();
          submit();
        }}
        className="flex flex-col gap-3"
      >
        <div className="grid gap-3 sm:grid-cols-3">
          <Select
            label={t.production.competence}
            required
            value={competence}
            onValueChange={setCompetence}
            options={(deadlines.data?.items ?? [])
              .filter((d) => d.status !== 'closed')
              .map((d) => ({ value: d.competence, label: formatCompetence(d.competence) }))}
          />
          <Select
            label={t.production.cnes}
            required
            value={cnes}
            onValueChange={setCnes}
            options={(units.data?.items ?? []).map((u) => ({
              value: u.cnes,
              label: `${u.name} (${u.cnes})`,
            }))}
          />
          <Select
            label={t.production.kind}
            required
            value={kind}
            onValueChange={(v) => setKind(v as ProductionKind)}
            options={PRODUCTION_KINDS.map((k) => ({
              value: k,
              label: productionKindLabels[k].label,
            }))}
          />
        </div>
        {error ? (
          <p role="alert" className="text-sm font-medium text-danger-fg-subtle">
            {error}
          </p>
        ) : null}
        <div>
          <Button type="submit" loading={mutation.isPending}>
            {t.production.createBatch}
          </Button>
        </div>
      </form>
    </Card>
  );
}

export function BatchesTab() {
  const perms = useProductionPermissions();
  const [status, setStatus] = useState<ProductionBatchStatus | ''>('');
  const [cursors, setCursors] = useState<string[]>([]);
  const query = useProductionBatches({
    status: status || undefined,
    cursor: cursors[cursors.length - 1],
    limit: 20,
  });

  return (
    <div className="flex flex-col gap-6">
      {perms.canCreateBatch ? <CreateBatchCard /> : null}
      <section aria-labelledby="production-batches" className="flex flex-col gap-4">
        <h2 id="production-batches" className="text-xl font-semibold">
          {t.production.batches}
        </h2>
        <Select
          label={t.production.status}
          value={status || 'all'}
          onValueChange={(v) => {
            setStatus(v === 'all' ? '' : (v as ProductionBatchStatus));
            setCursors([]);
          }}
          options={[
            { value: 'all', label: t.app.all },
            ...BATCH_STATUSES.map((s) => ({
              value: s,
              label: productionBatchStatusLabels[s].label,
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
              <EmptyState title={t.production.noBatches} />
            ) : (
              <div className="flex flex-col gap-3">
                <div className="overflow-x-auto">
                  <Table aria-label={t.production.batches}>
                    <TableHead>
                      <TableRow>
                        <TableHeaderCell>{t.production.batch}</TableHeaderCell>
                        <TableHeaderCell>{t.production.competence}</TableHeaderCell>
                        <TableHeaderCell>{t.production.kind}</TableHeaderCell>
                        <TableHeaderCell>{t.production.cnes}</TableHeaderCell>
                        <TableHeaderCell>{t.production.status}</TableHeaderCell>
                        <TableHeaderCell>{t.production.records}</TableHeaderCell>
                        <TableHeaderCell>{t.production.estimatedValue}</TableHeaderCell>
                        <TableHeaderCell>{t.production.createdAt}</TableHeaderCell>
                      </TableRow>
                    </TableHead>
                    <TableBody>
                      {page.items.map((b) => {
                        const meta = productionBatchStatusLabels[b.status];
                        return (
                          <TableRow key={b.id} data-status={b.status}>
                            <TableHeaderCell scope="row">
                              <Link
                                href={`/producao/lotes/${b.id}`}
                                className="font-mono text-xs text-primary-fg-subtle hover:underline"
                              >
                                {b.id}
                              </Link>
                            </TableHeaderCell>
                            <TableCell>{formatCompetence(b.competence)}</TableCell>
                            <TableCell>{productionKindLabels[b.kind].label}</TableCell>
                            <TableCell className="font-mono text-xs">{b.cnes}</TableCell>
                            <TableCell>
                              <Badge tone={meta.tone}>{meta.label}</Badge>
                            </TableCell>
                            <TableCell className="tabular-nums">{b.records_count}</TableCell>
                            <TableCell className="tabular-nums">
                              {formatCurrency(b.estimated_value)}
                            </TableCell>
                            <TableCell>{formatDateTime(b.created_at)}</TableCell>
                          </TableRow>
                        );
                      })}
                    </TableBody>
                  </Table>
                </div>
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
      </section>
    </div>
  );
}
