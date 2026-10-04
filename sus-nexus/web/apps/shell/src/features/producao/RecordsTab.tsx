'use client';

import Link from 'next/link';
import { useState } from 'react';
import {
  useProductionDeadlines,
  useProductionRecords,
  type ProductionKind,
  type ProductionRecordStatus,
} from '@sus-nexus/api-client';
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
import {
  formatCompetence,
  formatCurrency,
  formatDate,
  productionKindLabels,
  productionRecordStatusLabels,
} from '@sus-nexus/domain-components';
import { QueryState } from '@/components/QueryState';
import { t } from '@/i18n';
import { PRODUCTION_KINDS, safeMaskedIdentifier } from './format';

const STATUSES = Object.keys(productionRecordStatusLabels) as ProductionRecordStatus[];

/** Lista de registros com filtros por situação/competência/instrumento e cursor (PRO-001..004). */
export function RecordsTab() {
  const [status, setStatus] = useState<ProductionRecordStatus | ''>('');
  const [competence, setCompetence] = useState('');
  const [kind, setKind] = useState<ProductionKind | ''>('');
  const [cursors, setCursors] = useState<string[]>([]);
  const deadlines = useProductionDeadlines();
  const query = useProductionRecords({
    status: status || undefined,
    competence: competence || undefined,
    kind: kind || undefined,
    cursor: cursors[cursors.length - 1],
    limit: 20,
  });
  const resetPaging = () => setCursors([]);

  return (
    <div className="flex flex-col gap-4">
      <fieldset className="grid gap-3 sm:grid-cols-3">
        <legend className="sr-only">Filtros</legend>
        <Select
          label={t.production.status}
          value={status || 'all'}
          onValueChange={(v) => {
            setStatus(v === 'all' ? '' : (v as ProductionRecordStatus));
            resetPaging();
          }}
          options={[
            { value: 'all', label: t.app.all },
            ...STATUSES.map((s) => ({ value: s, label: productionRecordStatusLabels[s].label })),
          ]}
        />
        <Select
          label={t.production.competence}
          value={competence || 'all'}
          onValueChange={(v) => {
            setCompetence(v === 'all' ? '' : v);
            resetPaging();
          }}
          options={[
            { value: 'all', label: t.production.allCompetences },
            ...(deadlines.data?.items ?? [])
              .map((d) => d.competence)
              .sort()
              .reverse()
              .map((c) => ({ value: c, label: formatCompetence(c) })),
          ]}
        />
        <Select
          label={t.production.kind}
          value={kind || 'all'}
          onValueChange={(v) => {
            setKind(v === 'all' ? '' : (v as ProductionKind));
            resetPaging();
          }}
          options={[
            { value: 'all', label: t.production.allKinds },
            ...PRODUCTION_KINDS.map((k) => ({ value: k, label: productionKindLabels[k].label })),
          ]}
        />
      </fieldset>
      <QueryState
        isLoading={query.isLoading}
        error={query.error}
        data={query.data}
        onRetry={() => void query.refetch()}
      >
        {(page) =>
          page.items.length === 0 ? (
            <EmptyState title={t.production.noRecords} />
          ) : (
            <div className="flex flex-col gap-3">
              <div className="overflow-x-auto">
                <Table aria-label={t.production.recordsTable}>
                  <TableHead>
                    <TableRow>
                      <TableHeaderCell>{t.production.procedure}</TableHeaderCell>
                      <TableHeaderCell>{t.production.kind}</TableHeaderCell>
                      <TableHeaderCell>{t.production.competence}</TableHeaderCell>
                      <TableHeaderCell>{t.production.unit}</TableHeaderCell>
                      <TableHeaderCell>{t.production.citizen}</TableHeaderCell>
                      <TableHeaderCell>{t.production.status}</TableHeaderCell>
                      <TableHeaderCell>{t.production.openIssues}</TableHeaderCell>
                      <TableHeaderCell>{t.production.estimatedValue}</TableHeaderCell>
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {page.items.map((r) => {
                      const meta = productionRecordStatusLabels[r.status];
                      const open = (r.issues ?? []).filter((i) => i.status === 'open');
                      const errors = open.filter((i) => i.severity === 'error').length;
                      const warnings = open.length - errors;
                      return (
                        <TableRow key={r.id} data-status={r.status}>
                          <TableHeaderCell scope="row">
                            <Link
                              href={`/producao/registros/${r.id}`}
                              className="font-medium text-primary-fg-subtle hover:underline"
                            >
                              {r.procedure_display ?? r.procedure_code}
                            </Link>
                            <span className="block font-mono text-xs font-normal text-fg-muted">
                              {r.procedure_code} · {formatDate(r.attendance_date)}
                            </span>
                          </TableHeaderCell>
                          <TableCell>{productionKindLabels[r.kind].label}</TableCell>
                          <TableCell>{formatCompetence(r.competence)}</TableCell>
                          <TableCell>
                            {r.health_unit_name ?? '—'}
                            <span className="block font-mono text-xs text-fg-muted">
                              CNES {r.cnes}
                            </span>
                          </TableCell>
                          <TableCell className="font-mono text-xs">
                            {safeMaskedIdentifier(r.citizen_identifier_masked)}
                          </TableCell>
                          <TableCell>
                            <Badge tone={meta.tone}>{meta.label}</Badge>
                          </TableCell>
                          <TableCell>
                            {open.length === 0 ? (
                              <span className="text-fg-muted">—</span>
                            ) : (
                              <span className="flex flex-wrap gap-1">
                                {errors > 0 ? <Badge tone="danger">{errors} erro(s)</Badge> : null}
                                {warnings > 0 ? (
                                  <Badge tone="warning">{warnings} aviso(s)</Badge>
                                ) : null}
                              </span>
                            )}
                          </TableCell>
                          <TableCell className="tabular-nums">
                            {formatCurrency(r.estimated_value)}
                          </TableCell>
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
                summary={`${page.items.length} registro(s) nesta página`}
              />
            </div>
          )
        }
      </QueryState>
    </div>
  );
}
