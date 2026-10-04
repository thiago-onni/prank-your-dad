'use client';

import { AlertTriangle, CalendarClock } from 'lucide-react';
import { useState } from 'react';
import {
  useProductionDeadlines,
  useProductionSummary,
  type ProductionDeadline,
  type ProductionRecordStatus,
  type ProductionSummary,
} from '@sus-nexus/api-client';
import {
  Badge,
  Card,
  CardHeader,
  Select,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeaderCell,
  TableRow,
  cn,
} from '@sus-nexus/design-system';
import {
  formatCompetence,
  formatCurrency,
  formatDateTime,
  productionDeadlineStatusLabels,
  productionKindLabels,
  productionRecordStatusLabels,
} from '@sus-nexus/domain-components';
import { QueryState } from '@/components/QueryState';
import { format, t } from '@/i18n';
import { deadlineAlert, defaultCompetence } from './format';

const STATUS_ORDER: ProductionRecordStatus[] = [
  'generated',
  'validated',
  'pending',
  'exported',
  'transmitted',
  'received',
  'rejected',
  'approved',
  'paid',
];

export function DeadlineBadge({ deadline }: { deadline: ProductionDeadline }) {
  const alert = deadlineAlert(deadline);
  return (
    <Badge tone={alert.tone} data-level={alert.level}>
      {alert.level !== 'ok' ? <AlertTriangle aria-hidden="true" className="mr-1 h-3 w-3" /> : null}
      {alert.label}
      {alert.description ? <span className="sr-only">: {alert.description}</span> : null}
    </Badge>
  );
}

function SummaryCard({
  summary,
  deadline,
}: {
  summary: ProductionSummary;
  deadline?: ProductionDeadline;
}) {
  const totals = summary.totals as Partial<Record<string, number>>;
  const values = summary.values ?? {};
  const alert = deadline ? deadlineAlert(deadline) : undefined;
  const headingId = 'production-summary';
  return (
    <Card as="section" aria-labelledby={headingId}>
      <CardHeader
        headingLevel={2}
        title={
          <span id={headingId}>
            {format(t.production.summaryTitle, {
              competence: formatCompetence(summary.competence),
            })}
          </span>
        }
        description={t.production.summaryDescription}
        actions={deadline ? <DeadlineBadge deadline={deadline} /> : null}
      />
      {deadline && alert && alert.level !== 'ok' ? (
        <p
          role="alert"
          className={cn(
            'mb-4 flex items-start gap-2 rounded-md border p-3 text-sm font-medium',
            alert.tone === 'danger'
              ? 'border-danger bg-danger-subtle text-danger-fg-subtle'
              : 'border-warning bg-warning-subtle text-warning-fg-subtle',
          )}
        >
          <CalendarClock aria-hidden="true" className="mt-0.5 h-5 w-5 shrink-0" />
          <span>
            {alert.label} · {alert.description}{' '}
            {format(t.production.deadlineIn, { date: formatDateTime(deadline.deadline_at) })}
          </span>
        </p>
      ) : summary.deadline_at ? (
        <p className="mb-4 text-sm text-fg-muted">
          {format(t.production.deadlineIn, { date: formatDateTime(summary.deadline_at) })}
        </p>
      ) : null}

      <dl className="grid grid-cols-2 gap-4 sm:grid-cols-3 lg:grid-cols-6">
        <div>
          <dt className="text-sm text-fg-muted">{t.production.totalRecords}</dt>
          <dd className="text-2xl font-semibold tabular-nums">{totals.records ?? 0}</dd>
        </div>
        <div>
          <dt className="text-sm text-fg-muted">{t.production.estimated}</dt>
          <dd className="text-2xl font-semibold tabular-nums">
            {formatCurrency(values.estimated)}
          </dd>
        </div>
        <div>
          <dt className="text-sm text-fg-muted">{t.production.validatedValue}</dt>
          <dd className="text-2xl font-semibold tabular-nums">
            {formatCurrency(values.validated)}
          </dd>
        </div>
        <div>
          <dt className="text-sm text-fg-muted">{t.production.paidValue}</dt>
          <dd className="text-2xl font-semibold tabular-nums">{formatCurrency(values.paid)}</dd>
        </div>
        <div>
          <dt className="text-sm text-fg-muted">{t.production.pendingValue}</dt>
          <dd className="text-2xl font-semibold tabular-nums">{formatCurrency(values.pending)}</dd>
        </div>
        <div>
          <dt className="text-sm text-fg-muted">
            {t.production.avoidableLoss}
            <span className="block text-xs">{t.production.avoidableLossHelp}</span>
          </dt>
          <dd className="text-2xl font-semibold tabular-nums text-danger-fg-subtle">
            {formatCurrency(values.avoidable_loss_estimated)}
          </dd>
        </div>
      </dl>

      <div className="mt-6 grid gap-6 lg:grid-cols-3">
        <section aria-labelledby="production-by-status">
          <h3 id="production-by-status" className="mb-2 text-base font-semibold">
            {t.production.byStatus}
          </h3>
          <ul className="flex flex-wrap gap-2">
            {STATUS_ORDER.map((s) => (
              <li key={s}>
                <Badge tone={productionRecordStatusLabels[s].tone}>
                  {productionRecordStatusLabels[s].label}: {totals[s] ?? 0}
                </Badge>
              </li>
            ))}
            <li>
              <Badge tone="primary">
                {productionRecordStatusLabels.corrected.label}: {totals.corrected ?? 0}
              </Badge>
            </li>
          </ul>
        </section>
        <section aria-labelledby="production-by-kind">
          <h3 id="production-by-kind" className="mb-2 text-base font-semibold">
            {t.production.byKind}
          </h3>
          <ul className="flex flex-col gap-1 text-sm">
            {(summary.by_kind ?? []).map((k) =>
              k.kind ? (
                <li key={k.kind} className="flex justify-between gap-2">
                  <abbr title={productionKindLabels[k.kind].description} className="no-underline">
                    {productionKindLabels[k.kind].label}
                  </abbr>
                  <span className="tabular-nums">
                    {k.records ?? 0} · {formatCurrency(k.estimated)}
                  </span>
                </li>
              ) : null,
            )}
          </ul>
        </section>
        <section aria-labelledby="production-by-rule">
          <h3 id="production-by-rule" className="mb-2 text-base font-semibold">
            {t.production.issuesByRule}
          </h3>
          {(summary.issues_by_rule ?? []).length === 0 ? (
            <p className="text-sm text-fg-muted">{t.production.noIssues}</p>
          ) : (
            <ul className="flex flex-col gap-1 text-sm">
              {(summary.issues_by_rule ?? []).map((r) => (
                <li key={r.rule_id} className="flex items-center justify-between gap-2">
                  <code className="font-mono text-xs">{r.rule_id}</code>
                  <span className="flex items-center gap-2">
                    <Badge tone={r.severity === 'error' ? 'danger' : 'warning'}>
                      {r.severity === 'error' ? 'Erro' : 'Aviso'}
                    </Badge>
                    <span className="tabular-nums">{r.open ?? 0}</span>
                  </span>
                </li>
              ))}
            </ul>
          )}
        </section>
      </div>
    </Card>
  );
}

function DeadlinesCard({ deadlines }: { deadlines: ProductionDeadline[] }) {
  return (
    <Card as="section" aria-labelledby="production-deadlines">
      <CardHeader
        headingLevel={2}
        title={<span id="production-deadlines">{t.production.deadlinesTitle}</span>}
        description={t.production.deadlinesDescription}
      />
      {deadlines.length === 0 ? (
        <p className="text-sm text-fg-muted">{t.production.noDeadlines}</p>
      ) : (
        <Table aria-label={t.production.deadlinesTitle}>
          <TableHead>
            <TableRow>
              <TableHeaderCell>{t.production.competence}</TableHeaderCell>
              <TableHeaderCell>{t.production.deadline}</TableHeaderCell>
              <TableHeaderCell>{t.production.status}</TableHeaderCell>
              <TableHeaderCell>{t.production.daysRemaining}</TableHeaderCell>
              <TableHeaderCell>{t.production.pendingRecords}</TableHeaderCell>
              <TableHeaderCell>{t.production.validatedRecords}</TableHeaderCell>
              <TableHeaderCell>{t.production.configuredBy}</TableHeaderCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {deadlines.map((d) => {
              const status = productionDeadlineStatusLabels[d.status];
              return (
                <TableRow key={d.competence} data-alert={deadlineAlert(d).level}>
                  <TableHeaderCell scope="row">{formatCompetence(d.competence)}</TableHeaderCell>
                  <TableCell>{formatDateTime(d.deadline_at)}</TableCell>
                  <TableCell>
                    <span className="flex flex-wrap gap-1">
                      <Badge tone={status.tone}>{status.label}</Badge>
                      <DeadlineBadge deadline={d} />
                    </span>
                  </TableCell>
                  <TableCell className="tabular-nums">{d.days_remaining ?? '—'}</TableCell>
                  <TableCell className="tabular-nums">{d.pending_records ?? '—'}</TableCell>
                  <TableCell className="tabular-nums">{d.validated_records ?? '—'}</TableCell>
                  <TableCell>{d.configured_by ?? '—'}</TableCell>
                </TableRow>
              );
            })}
          </TableBody>
        </Table>
      )}
    </Card>
  );
}

/** Painel da competência (PRO-009) + prazos de apresentação (PRO-007). */
export function PanelTab() {
  const deadlines = useProductionDeadlines();
  const [chosen, setChosen] = useState<string | undefined>();
  const items = deadlines.data?.items ?? [];
  // Padrão: competência em apresentação mais antiga ainda aberta (status open/closing).
  const active = [...items]
    .filter((d) => d.status !== 'closed')
    .sort((a, b) => (a.competence < b.competence ? -1 : 1))[0];
  const competence = chosen ?? active?.competence ?? defaultCompetence();
  const summary = useProductionSummary({ competence }, { enabled: !deadlines.isLoading });
  const options = [...new Set([...items.map((d) => d.competence), competence])]
    .sort()
    .reverse()
    .map((c) => ({ value: c, label: formatCompetence(c) }));

  return (
    <div className="flex flex-col gap-6">
      <Select
        label={t.production.competence}
        value={competence}
        onValueChange={setChosen}
        options={options}
        className="max-w-xs"
      />
      <QueryState
        isLoading={summary.isLoading}
        error={summary.error}
        data={summary.data}
        onRetry={() => void summary.refetch()}
      >
        {(s) => (
          <SummaryCard summary={s} deadline={items.find((d) => d.competence === s.competence)} />
        )}
      </QueryState>
      <QueryState
        isLoading={deadlines.isLoading}
        error={deadlines.error}
        data={deadlines.data}
        onRetry={() => void deadlines.refetch()}
      >
        {(d) => <DeadlinesCard deadlines={d.items} />}
      </QueryState>
    </div>
  );
}
