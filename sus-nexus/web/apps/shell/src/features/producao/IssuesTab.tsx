'use client';

import Link from 'next/link';
import { useState } from 'react';
import {
  useProductionIssues,
  type ProductionIssueSeverity,
  type ProductionIssueStatus,
  type ProductionKind,
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
  formatDateTime,
  productionIssueSeverityLabels,
  productionIssueStatusLabels,
  productionKindLabels,
} from '@sus-nexus/domain-components';
import { QueryState } from '@/components/QueryState';
import { format, t } from '@/i18n';
import { PRODUCTION_KINDS } from './format';

const SEVERITIES = Object.keys(productionIssueSeverityLabels) as ProductionIssueSeverity[];
const ISSUE_STATUSES = Object.keys(productionIssueStatusLabels) as ProductionIssueStatus[];

/** Fila de pendências de pré-auditoria (erros primeiro). */
export function IssuesTab() {
  const [severity, setSeverity] = useState<ProductionIssueSeverity | ''>('');
  const [status, setStatus] = useState<ProductionIssueStatus>('open');
  const [kind, setKind] = useState<ProductionKind | ''>('');
  const [cursors, setCursors] = useState<string[]>([]);
  const query = useProductionIssues({
    severity: severity || undefined,
    status,
    kind: kind || undefined,
    cursor: cursors[cursors.length - 1],
    limit: 20,
  });

  return (
    <div className="flex flex-col gap-4">
      <fieldset className="grid gap-3 sm:grid-cols-3">
        <legend className="sr-only">Filtros</legend>
        <Select
          label={t.production.severity}
          value={severity || 'all'}
          onValueChange={(v) => {
            setSeverity(v === 'all' ? '' : (v as ProductionIssueSeverity));
            setCursors([]);
          }}
          options={[
            { value: 'all', label: t.production.allSeverities },
            ...SEVERITIES.map((s) => ({ value: s, label: productionIssueSeverityLabels[s].label })),
          ]}
        />
        <Select
          label={t.production.issueStatus}
          value={status}
          onValueChange={(v) => {
            setStatus(v as ProductionIssueStatus);
            setCursors([]);
          }}
          options={ISSUE_STATUSES.map((s) => ({
            value: s,
            label: productionIssueStatusLabels[s].label,
          }))}
        />
        <Select
          label={t.production.kind}
          value={kind || 'all'}
          onValueChange={(v) => {
            setKind(v === 'all' ? '' : (v as ProductionKind));
            setCursors([]);
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
            <EmptyState title={t.production.noQueueIssues} />
          ) : (
            <div className="flex flex-col gap-3">
              <div className="overflow-x-auto">
                <Table aria-label={t.production.issuesQueue}>
                  <TableHead>
                    <TableRow>
                      <TableHeaderCell>{t.production.severity}</TableHeaderCell>
                      <TableHeaderCell>{t.production.rule}</TableHeaderCell>
                      <TableHeaderCell>{t.production.message}</TableHeaderCell>
                      <TableHeaderCell>{t.production.kind}</TableHeaderCell>
                      <TableHeaderCell>{t.production.competence}</TableHeaderCell>
                      <TableHeaderCell>{t.production.issueStatus}</TableHeaderCell>
                      <TableHeaderCell>{t.production.createdAt}</TableHeaderCell>
                      <TableHeaderCell>{t.production.record}</TableHeaderCell>
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {page.items.map((i) => {
                      const sev = productionIssueSeverityLabels[i.severity];
                      const st = productionIssueStatusLabels[i.status];
                      return (
                        <TableRow key={i.id} data-severity={i.severity}>
                          <TableCell>
                            <Badge tone={sev.tone}>{sev.label}</Badge>
                          </TableCell>
                          <TableCell>
                            <code className="font-mono text-xs">{i.rule_id}</code>
                            <span className="block text-xs text-fg-muted">{i.rule_version}</span>
                          </TableCell>
                          <TableCell>
                            {i.message}
                            {i.field ? (
                              <span className="block text-xs text-fg-muted">
                                {t.production.field}: <code>{i.field}</code>
                              </span>
                            ) : null}
                          </TableCell>
                          <TableCell>{i.kind ? productionKindLabels[i.kind].label : '—'}</TableCell>
                          <TableCell>{formatCompetence(i.competence)}</TableCell>
                          <TableCell>
                            <Badge tone={st.tone}>{st.label}</Badge>
                          </TableCell>
                          <TableCell>{formatDateTime(i.created_at)}</TableCell>
                          <TableCell>
                            <Link
                              href={`/producao/registros/${i.production_record_id}`}
                              className="font-mono text-xs text-primary-fg-subtle hover:underline"
                              aria-label={format(t.production.openRecord, {
                                id: i.production_record_id,
                              })}
                            >
                              {i.production_record_id.slice(0, 13)}…
                            </Link>
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
                summary={`${page.items.length} pendência(s) nesta página`}
              />
            </div>
          )
        }
      </QueryState>
    </div>
  );
}
