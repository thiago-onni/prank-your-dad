'use client';

import Link from 'next/link';
import { AlertTriangle, FileDown, ShieldAlert } from 'lucide-react';
import { useState } from 'react';
import {
  useApproveProductionBatch,
  useExportProductionBatch,
  useProductionBatch,
  useProductionRecords,
  type ProductionBatch,
  type ProductionExportLayout,
} from '@sus-nexus/api-client';
import {
  Badge,
  Button,
  Card,
  CardHeader,
  Dialog,
  DialogContent,
  Select,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeaderCell,
  TableRow,
  Textarea,
  useToast,
} from '@sus-nexus/design-system';
import {
  formatCompetence,
  formatCurrency,
  formatDateTime,
  productionBatchStatusLabels,
  productionKindLabels,
  productionRecordStatusLabels,
} from '@sus-nexus/domain-components';
import { PageHeader } from '@/components/PageHeader';
import { PurposeRequired } from '@/components/PurposeRequired';
import { QueryState } from '@/components/QueryState';
import { describeError } from '@/lib/problem';
import { format, t } from '@/i18n';
import { formatBytes, safeMaskedIdentifier } from './format';
import { OutcomeDialog } from './OutcomeDialog';
import { useProductionPermissions } from './permissions';

/** Aprovação humana obrigatória do lote, com justificativa (PRO-010). */
export function ApproveBatchDialog({
  batch,
  open,
  onOpenChange,
}: {
  batch: ProductionBatch;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const [justification, setJustification] = useState('');
  const [error, setError] = useState<string | undefined>();
  const mutation = useApproveProductionBatch();
  const { toast } = useToast();

  const reset = () => {
    setJustification('');
    setError(undefined);
  };

  const submit = () => {
    const trimmed = justification.trim();
    if (trimmed.length < 10 || trimmed.length > 1000) {
      setError(t.production.justificationInvalid);
      return;
    }
    mutation.mutate(
      { batchId: batch.id, justification: trimmed },
      {
        onSuccess: () => {
          toast({ title: t.production.approved, tone: 'success' });
          reset();
          onOpenChange(false);
        },
        onError: (err) => {
          const { message, correlationId } = describeError(err);
          toast({
            title: t.production.approveFailed,
            description: `${message}${correlationId ? ` (${correlationId})` : ''}`,
            tone: 'danger',
          });
        },
      },
    );
  };

  return (
    <Dialog
      open={open}
      onOpenChange={(o) => {
        if (!o) reset();
        onOpenChange(o);
      }}
    >
      <DialogContent
        title={format(t.production.approveTitle, { id: batch.id })}
        description={t.production.approveDescription}
        size="md"
        footer={
          <>
            <Button variant="secondary" onClick={() => onOpenChange(false)}>
              {t.app.cancel}
            </Button>
            <Button variant="primary" onClick={submit} loading={mutation.isPending}>
              {t.production.approveBatch}
            </Button>
          </>
        }
      >
        <Textarea
          label={t.production.justification}
          required
          description={t.production.justificationHelp}
          value={justification}
          onChange={(e) => {
            setJustification(e.target.value);
            if (error) setError(undefined);
          }}
          error={error}
          maxLength={1000}
          rows={4}
        />
      </DialogContent>
    </Dialog>
  );
}

function SensitiveFileWarning() {
  return (
    <p
      role="note"
      className="flex items-start gap-2 rounded-md border border-warning bg-warning-subtle p-3 text-sm font-medium text-warning-fg-subtle"
    >
      <ShieldAlert aria-hidden="true" className="mt-0.5 h-5 w-5 shrink-0" />
      {t.production.sensitiveFileWarning}
    </p>
  );
}

function ExportCard({ batch, canExport }: { batch: ProductionBatch; canExport: boolean }) {
  const isBpa = batch.kind === 'bpa_c' || batch.kind === 'bpa_i';
  const isApac = batch.kind === 'apac';
  const [layout, setLayout] = useState<ProductionExportLayout>(
    isBpa ? 'bpa_mag_v202412' : isApac ? 'apac_mag_v202607' : 'csv_ref_v1',
  );
  const mutation = useExportProductionBatch();
  const { toast } = useToast();
  const exp = batch.export;

  const doExport = () =>
    mutation.mutate(
      { batchId: batch.id, layout },
      {
        onSuccess: () => toast({ title: t.production.exported, tone: 'success' }),
        onError: (err) => {
          const { message, correlationId } = describeError(err);
          toast({
            title: t.production.exportFailed,
            description: `${message}${correlationId ? ` (${correlationId})` : ''}`,
            tone: 'danger',
          });
        },
      },
    );

  return (
    <Card as="section" aria-labelledby="production-batch-export">
      <CardHeader
        headingLevel={2}
        title={<span id="production-batch-export">{t.production.exportTitle}</span>}
        description={t.production.transmissionNotice}
      />
      <div className="flex flex-col gap-4">
        {exp ? (
          <>
            <SensitiveFileWarning />
            <dl className="grid gap-x-6 gap-y-2 text-sm sm:grid-cols-2">
              <div className="sm:col-span-2">
                <dt className="text-fg-muted">{t.production.sha256}</dt>
                <dd>
                  <code className="break-all font-mono text-xs" data-testid="export-sha256">
                    {exp.sha256 ?? '—'}
                  </code>
                </dd>
              </div>
              <div className="sm:col-span-2">
                <dt className="text-fg-muted">{t.production.fileRef}</dt>
                <dd className="break-all font-mono text-xs">{exp.file_ref ?? '—'}</dd>
              </div>
              <div>
                <dt className="text-fg-muted">{t.production.exportLayout}</dt>
                <dd className="font-mono text-xs">{exp.layout ?? '—'}</dd>
              </div>
              <div>
                <dt className="text-fg-muted">
                  {t.production.sizeBytes} / {t.production.lines}
                </dt>
                <dd>
                  {formatBytes(exp.size_bytes)} · {exp.lines ?? '—'}
                </dd>
              </div>
              <div>
                <dt className="text-fg-muted">{t.production.exportedBy}</dt>
                <dd className="font-mono text-xs">{exp.exported_by ?? '—'}</dd>
              </div>
              <div>
                <dt className="text-fg-muted">{t.production.exportedAt}</dt>
                <dd>{formatDateTime(exp.exported_at)}</dd>
              </div>
            </dl>
            {exp.lines_missing_identifiers ? (
              <p
                role="alert"
                className="flex items-start gap-2 rounded-md border border-danger bg-danger-subtle p-3 text-sm text-danger-fg-subtle"
              >
                <AlertTriangle aria-hidden="true" className="mt-0.5 h-4 w-4 shrink-0" />
                {format(t.production.linesMissingIdentifiers, {
                  count: exp.lines_missing_identifiers,
                })}
              </p>
            ) : null}
          </>
        ) : batch.status !== 'approved' ? (
          <p className="text-sm text-fg-muted">{t.production.exportNotReady}</p>
        ) : canExport ? (
          <>
            <SensitiveFileWarning />
            <Select
              label={t.production.exportLayout}
              value={layout}
              onValueChange={(v) => setLayout(v as ProductionExportLayout)}
              options={[
                ...(isBpa ? [{ value: 'bpa_mag_v202412', label: t.production.layoutBpaMag }] : []),
                ...(isApac ? [{ value: 'apac_mag_v202607', label: t.production.layoutApac }] : []),
                { value: 'csv_ref_v1', label: t.production.layoutCsv },
              ]}
              className="max-w-md"
            />
            <div>
              <Button onClick={doExport} loading={mutation.isPending}>
                <FileDown aria-hidden="true" className="h-4 w-4" />
                {t.production.exportBatch}
              </Button>
            </div>
          </>
        ) : (
          <p className="text-sm text-fg-muted">{t.production.readOnlyNotice}</p>
        )}
      </div>
    </Card>
  );
}

function BatchRecords({ batchId }: { batchId: string }) {
  const query = useProductionRecords({ batch_id: batchId, limit: 200 });
  return (
    <Card as="section" aria-labelledby="production-batch-records">
      <CardHeader
        headingLevel={2}
        title={<span id="production-batch-records">{t.production.batchRecords}</span>}
      />
      <QueryState
        isLoading={query.isLoading}
        error={query.error}
        data={query.data}
        onRetry={() => void query.refetch()}
      >
        {(page) => (
          <div className="overflow-x-auto">
            <Table aria-label={t.production.batchRecords}>
              <TableHead>
                <TableRow>
                  <TableHeaderCell>{t.production.procedure}</TableHeaderCell>
                  <TableHeaderCell>{t.production.citizen}</TableHeaderCell>
                  <TableHeaderCell>{t.production.quantity}</TableHeaderCell>
                  <TableHeaderCell>{t.production.status}</TableHeaderCell>
                  <TableHeaderCell>{t.production.estimatedValue}</TableHeaderCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {page.items.map((r) => (
                  <TableRow key={r.id}>
                    <TableHeaderCell scope="row">
                      <Link
                        href={`/producao/registros/${r.id}`}
                        className="text-primary-fg-subtle hover:underline"
                      >
                        {r.procedure_code}
                      </Link>
                    </TableHeaderCell>
                    <TableCell className="font-mono text-xs">
                      {safeMaskedIdentifier(r.citizen_identifier_masked)}
                    </TableCell>
                    <TableCell className="tabular-nums">{r.quantity}</TableCell>
                    <TableCell>
                      <Badge tone={productionRecordStatusLabels[r.status].tone}>
                        {productionRecordStatusLabels[r.status].label}
                      </Badge>
                    </TableCell>
                    <TableCell className="tabular-nums">
                      {formatCurrency(r.estimated_value)}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
        )}
      </QueryState>
    </Card>
  );
}

export function ProductionBatchDetail({ batchId }: { batchId: string }) {
  const query = useProductionBatch(batchId);
  const perms = useProductionPermissions();
  const [approveOpen, setApproveOpen] = useState(false);
  const [outcomeOpen, setOutcomeOpen] = useState(false);

  return (
    <PurposeRequired>
      <QueryState
        isLoading={query.isLoading}
        error={query.error}
        data={query.data}
        onRetry={() => void query.refetch()}
      >
        {(batch) => {
          const status = productionBatchStatusLabels[batch.status];
          const canOutcome =
            perms.canRegisterOutcome &&
            (batch.status === 'exported' || batch.status === 'transmitted');
          return (
            <div className="flex flex-col gap-6">
              <PageHeader
                title={`${t.production.batchDetailTitle} ${productionKindLabels[batch.kind].label} · ${formatCompetence(batch.competence)}`}
                description={
                  <>
                    <span className="font-mono text-sm">{batch.id}</span> ·{' '}
                    <Link href="/producao?aba=lotes" className="underline">
                      {t.app.back}
                    </Link>
                  </>
                }
                actions={
                  <>
                    <Badge tone={status.tone}>{status.label}</Badge>
                    {perms.canApprove && batch.status === 'draft' ? (
                      <Button size="sm" onClick={() => setApproveOpen(true)}>
                        {t.production.approveBatch}
                      </Button>
                    ) : null}
                    {canOutcome ? (
                      <Button variant="secondary" size="sm" onClick={() => setOutcomeOpen(true)}>
                        {t.production.registerOutcome}
                      </Button>
                    ) : null}
                  </>
                }
              />
              <Card as="section" aria-labelledby="production-batch-data">
                <CardHeader
                  headingLevel={2}
                  title={<span id="production-batch-data">{t.app.details}</span>}
                />
                <dl className="grid gap-x-6 gap-y-2 text-sm sm:grid-cols-2 lg:grid-cols-3">
                  <div>
                    <dt className="text-fg-muted">{t.production.cnes}</dt>
                    <dd className="font-mono text-xs">{batch.cnes}</dd>
                  </div>
                  <div>
                    <dt className="text-fg-muted">{t.production.records}</dt>
                    <dd>{batch.records_count}</dd>
                  </div>
                  <div>
                    <dt className="text-fg-muted">{t.production.totalQuantity}</dt>
                    <dd>{batch.total_quantity ?? '—'}</dd>
                  </div>
                  <div>
                    <dt className="text-fg-muted">{t.production.estimatedValue}</dt>
                    <dd>{formatCurrency(batch.estimated_value)}</dd>
                  </div>
                  <div>
                    <dt className="text-fg-muted">{t.production.createdBy}</dt>
                    <dd>
                      <span className="font-mono text-xs">{batch.created_by ?? '—'}</span> ·{' '}
                      {formatDateTime(batch.created_at)}
                    </dd>
                  </div>
                  <div>
                    <dt className="text-fg-muted">{t.production.approvedBy}</dt>
                    <dd>
                      {batch.approved_by ? (
                        <>
                          <span className="font-mono text-xs">{batch.approved_by}</span> ·{' '}
                          {formatDateTime(batch.approved_at)}
                        </>
                      ) : (
                        '—'
                      )}
                    </dd>
                  </div>
                  <div className="sm:col-span-2">
                    <dt className="text-fg-muted">{t.production.approvalJustification}</dt>
                    <dd>{batch.approval_justification ?? '—'}</dd>
                  </div>
                  <div>
                    <dt className="text-fg-muted">{t.production.protocolNumber}</dt>
                    <dd className="font-mono text-xs">{batch.protocol_number ?? '—'}</dd>
                  </div>
                </dl>
              </Card>
              <ExportCard batch={batch} canExport={perms.canExport} />
              <BatchRecords batchId={batch.id} />
              {perms.canApprove ? (
                <ApproveBatchDialog
                  batch={batch}
                  open={approveOpen}
                  onOpenChange={setApproveOpen}
                />
              ) : null}
              {canOutcome ? (
                <OutcomeDialog
                  scope={{ kind: 'batch', batchId: batch.id }}
                  open={outcomeOpen}
                  onOpenChange={setOutcomeOpen}
                />
              ) : null}
            </div>
          );
        }}
      </QueryState>
    </PurposeRequired>
  );
}
