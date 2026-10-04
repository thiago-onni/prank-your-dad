'use client';

import Link from 'next/link';
import { useState, type ReactNode } from 'react';
import { useProductionRecord, type ProductionRecord } from '@sus-nexus/api-client';
import {
  Badge,
  Button,
  Card,
  CardHeader,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeaderCell,
  TableRow,
} from '@sus-nexus/design-system';
import {
  formatCompetence,
  formatCurrency,
  formatDate,
  formatDateTime,
  productionIssueSeverityLabels,
  productionIssueStatusLabels,
  productionKindLabels,
  productionRecordStatusLabels,
} from '@sus-nexus/domain-components';
import { PageHeader } from '@/components/PageHeader';
import { PurposeRequired } from '@/components/PurposeRequired';
import { QueryState } from '@/components/QueryState';
import { t } from '@/i18n';
import { CorrectionForm } from './CorrectionForm';
import { safeMaskedIdentifier } from './format';
import { OutcomeDialog } from './OutcomeDialog';
import { useProductionPermissions } from './permissions';

function RecordData({ record }: { record: ProductionRecord }) {
  const rows: { label: string; value: ReactNode; mono?: boolean }[] = [
    { label: t.production.kind, value: productionKindLabels[record.kind].description },
    { label: t.production.competence, value: formatCompetence(record.competence) },
    {
      label: t.production.unit,
      value: `${record.health_unit_name ?? '—'} · CNES ${record.cnes}`,
    },
    {
      label: t.production.procedure,
      value: `${record.procedure_code} · ${record.procedure_display ?? '—'}`,
    },
    { label: t.production.quantity, value: record.quantity },
    { label: t.production.attendanceDate, value: formatDate(record.attendance_date) },
    {
      label: t.production.citizen,
      value: safeMaskedIdentifier(record.citizen_identifier_masked),
      mono: true,
    },
    {
      label: t.production.professional,
      value: safeMaskedIdentifier(record.professional_cns_masked),
      mono: true,
    },
    { label: t.production.cbo, value: record.professional_cbo ?? '—', mono: true },
    { label: t.production.cid, value: record.cid_code ?? '—', mono: true },
    { label: t.production.characterOfCare, value: record.character_of_care ?? '—' },
    ...(record.apac_number ? [{ label: t.production.apacNumber, value: record.apac_number }] : []),
    ...(record.aih_number ? [{ label: t.production.aihNumber, value: record.aih_number }] : []),
    { label: t.production.estimatedValue, value: formatCurrency(record.estimated_value) },
    ...(record.paid_amount !== undefined
      ? [{ label: t.production.paidAmount, value: formatCurrency(record.paid_amount) }]
      : []),
    ...(record.approved_quantity !== undefined
      ? [{ label: t.production.approvedQuantity, value: record.approved_quantity }]
      : []),
    ...(record.outcome_reason
      ? [
          {
            label: t.production.outcomeReason,
            value: `${record.outcome_reason_code ? `${record.outcome_reason_code} · ` : ''}${record.outcome_reason}`,
          },
        ]
      : []),
    {
      label: t.production.batch,
      value: record.batch_id ? (
        <Link
          href={`/producao/lotes/${record.batch_id}`}
          className="font-mono text-xs text-primary-fg-subtle hover:underline"
        >
          {record.batch_id}
        </Link>
      ) : (
        '—'
      ),
    },
    { label: t.production.ruleVersion, value: record.rule_version ?? '—', mono: true },
    { label: t.production.validatedAt, value: formatDateTime(record.validated_at) },
    { label: t.production.corrections, value: record.correction_count ?? 0 },
    {
      label: t.production.source,
      value: `${record.source_system} · ${record.source_record_id}`,
      mono: true,
    },
  ];
  return (
    <Card as="section" aria-labelledby="production-record-data">
      <CardHeader
        headingLevel={2}
        title={<span id="production-record-data">{t.production.recordData}</span>}
      />
      <dl className="grid gap-x-6 gap-y-2 text-sm sm:grid-cols-2 lg:grid-cols-3">
        {rows.map((r) => (
          <div key={r.label}>
            <dt className="text-fg-muted">{r.label}</dt>
            <dd className={r.mono ? 'font-mono text-xs' : undefined}>{r.value}</dd>
          </div>
        ))}
      </dl>
    </Card>
  );
}

function RecordIssues({ record }: { record: ProductionRecord }) {
  const issues = record.issues ?? [];
  return (
    <Card as="section" aria-labelledby="production-record-issues">
      <CardHeader
        headingLevel={2}
        title={<span id="production-record-issues">{t.production.issuesTitle}</span>}
        description={t.production.issuesDescription}
      />
      {issues.length === 0 ? (
        <p className="text-sm text-fg-muted">{t.production.noRecordIssues}</p>
      ) : (
        <ul className="flex flex-col gap-2">
          {issues.map((i) => {
            const sev = productionIssueSeverityLabels[i.severity];
            const st = productionIssueStatusLabels[i.status];
            return (
              <li
                key={i.id}
                data-severity={i.severity}
                className="rounded-md border border-border p-3 text-sm"
              >
                <div className="flex flex-wrap items-center gap-2">
                  <Badge tone={sev.tone}>{sev.label}</Badge>
                  <Badge tone={st.tone}>{st.label}</Badge>
                  <code className="font-mono text-xs">{i.rule_id}</code>
                  <span className="text-xs text-fg-muted">
                    {t.production.ruleVersion}: {i.rule_version}
                  </span>
                </div>
                <p className="mt-1">{i.message}</p>
                <p className="text-xs text-fg-muted">
                  {i.field ? (
                    <>
                      {t.production.field}: <code>{i.field}</code> ·{' '}
                    </>
                  ) : null}
                  {t.production.createdAt} {formatDateTime(i.created_at)}
                  {i.resolution_note ? ` · ${t.production.resolution}: ${i.resolution_note}` : ''}
                </p>
              </li>
            );
          })}
        </ul>
      )}
    </Card>
  );
}

function RecordHistory({ record }: { record: ProductionRecord }) {
  const history = [...(record.history ?? [])].reverse();
  return (
    <Card as="section" aria-labelledby="production-record-history">
      <CardHeader
        headingLevel={2}
        title={<span id="production-record-history">{t.production.historyTitle}</span>}
      />
      {history.length === 0 ? (
        <p className="text-sm text-fg-muted">{t.production.noHistory}</p>
      ) : (
        <div className="overflow-x-auto">
          <Table aria-label={t.production.historyTitle}>
            <TableHead>
              <TableRow>
                <TableHeaderCell>Quando</TableHeaderCell>
                <TableHeaderCell>Ação</TableHeaderCell>
                <TableHeaderCell>{t.production.status}</TableHeaderCell>
                <TableHeaderCell>{t.production.actor}</TableHeaderCell>
                <TableHeaderCell>{t.production.justification}</TableHeaderCell>
                <TableHeaderCell>{t.production.ruleVersion}</TableHeaderCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {history.map((h, idx) => (
                <TableRow key={`${h.occurred_at}-${idx}`}>
                  <TableCell>{formatDateTime(h.occurred_at)}</TableCell>
                  <TableCell className="font-mono text-xs">{h.action ?? '—'}</TableCell>
                  <TableCell>
                    {h.from_status ? `${label(h.from_status)} → ` : ''}
                    {h.to_status ? label(h.to_status) : '—'}
                  </TableCell>
                  <TableCell className="font-mono text-xs">{h.actor_id ?? '—'}</TableCell>
                  <TableCell>{h.justification ?? '—'}</TableCell>
                  <TableCell className="font-mono text-xs">{h.rule_version ?? '—'}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      )}
    </Card>
  );
}

function label(status: string): string {
  return (
    productionRecordStatusLabels[status as keyof typeof productionRecordStatusLabels]?.label ??
    status
  );
}

export function ProductionRecordDetail({ recordId }: { recordId: string }) {
  const query = useProductionRecord(recordId);
  const perms = useProductionPermissions();
  const [outcomeOpen, setOutcomeOpen] = useState(false);

  return (
    <PurposeRequired>
      <QueryState
        isLoading={query.isLoading}
        error={query.error}
        data={query.data}
        onRetry={() => void query.refetch()}
      >
        {(record) => {
          const status = productionRecordStatusLabels[record.status];
          return (
            <div className="flex flex-col gap-6">
              <PageHeader
                title={record.procedure_display ?? record.procedure_code}
                description={
                  <>
                    {t.production.recordDetailTitle} · {productionKindLabels[record.kind].label} ·{' '}
                    {formatCompetence(record.competence)} ·{' '}
                    <Link href="/producao?aba=registros" className="underline">
                      {t.app.back}
                    </Link>
                  </>
                }
                actions={
                  <>
                    <Badge tone={status.tone}>{status.label}</Badge>
                    {perms.canRegisterOutcome ? (
                      <Button variant="secondary" size="sm" onClick={() => setOutcomeOpen(true)}>
                        {t.production.registerOutcome}
                      </Button>
                    ) : null}
                  </>
                }
              />
              <RecordData record={record} />
              <RecordIssues record={record} />
              {perms.canCorrect ? <CorrectionForm record={record} /> : null}
              <RecordHistory record={record} />
              {perms.canRegisterOutcome ? (
                <OutcomeDialog
                  scope={{ kind: 'record', recordId: record.id }}
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
