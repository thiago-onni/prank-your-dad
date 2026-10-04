'use client';

import { AlertOctagon } from 'lucide-react';
import {
  useRegulationQueueSummary,
  type SituationCapacityResponse,
  type SituationIndicator,
} from '@sus-nexus/api-client';
import {
  Card,
  CardHeader,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeaderCell,
  TableRow,
} from '@sus-nexus/design-system';
import { formatDateTime } from '@sus-nexus/domain-components';
import { QueryState } from '@/components/QueryState';
import { format, t } from '@/i18n';
import {
  formatIndicatorValue,
  formatSuppressible,
  formatTarget,
  priorityLabel,
  specialtyLabel,
  trafficLight,
} from './format';

/** Riscos: indicadores críticos da competência + filas fora do SLA. */
export function RiskPanel({
  indicators,
  capacity,
}: {
  indicators: SituationIndicator[];
  capacity?: SituationCapacityResponse;
}) {
  const critical = indicators.filter((i) => trafficLight(i).level === 'critica');
  const overdue = (capacity?.queue ?? [])
    .filter((q) => !q.n_overdue.suppressed && (q.n_overdue.value ?? 0) > 0)
    .sort((a, b) => (b.n_overdue.value ?? 0) - (a.n_overdue.value ?? 0))
    .slice(0, 3);
  return (
    <Card as="section" aria-labelledby="situation-risk">
      <CardHeader
        headingLevel={2}
        title={<span id="situation-risk">{t.situation.riskTitle}</span>}
        description={t.situation.riskDescription}
      />
      {critical.length === 0 ? (
        <p className="text-sm text-fg-muted">{t.situation.noRisk}</p>
      ) : (
        <ul className="flex flex-col gap-1 text-sm">
          {critical.map((i) => (
            <li key={i.code} className="flex items-start gap-2">
              <AlertOctagon aria-hidden="true" className="mt-0.5 h-4 w-4 shrink-0 text-danger" />
              <span>
                <strong>{i.name}</strong>: {formatIndicatorValue(i.value, i.unit, i.is_suppressed)}{' '}
                ({t.situation.target} {formatTarget(i.target, i.unit, i.direction)})
              </span>
            </li>
          ))}
        </ul>
      )}
      {overdue.length > 0 ? (
        <ul className="mt-2 flex flex-col gap-1 text-sm">
          {overdue.map((q) => (
            <li key={`${q.specialty}-${q.priority}-${q.request_kind}`}>
              {specialtyLabel(q.specialty)} ({priorityLabel(q.priority)}):{' '}
              {formatSuppressible(q.n_overdue)} {t.situation.overdue.toLowerCase()}
            </li>
          ))}
        </ul>
      ) : null}
    </Card>
  );
}

function LiveQueue() {
  const query = useRegulationQueueSummary('specialty');
  return (
    <Card as="section" aria-labelledby="situation-live-queue">
      <CardHeader
        headingLevel={2}
        title={<span id="situation-live-queue">{t.situation.liveQueueTitle}</span>}
        description={t.situation.liveQueueDescription}
      />
      <QueryState
        isLoading={query.isLoading}
        error={query.error}
        data={query.data}
        onRetry={() => void query.refetch()}
        skeletonRows={3}
      >
        {(d) => (
          <Table aria-label={t.situation.liveQueueTitle}>
            <TableHead>
              <TableRow>
                <TableHeaderCell>{t.situation.specialty}</TableHeaderCell>
                <TableHeaderCell>{t.situation.inQueue}</TableHeaderCell>
                <TableHeaderCell>{t.situation.overdue}</TableHeaderCell>
                <TableHeaderCell>{t.situation.waitP50}</TableHeaderCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {d.items.map((q) => (
                <TableRow key={q.group_key}>
                  <TableHeaderCell scope="row">{q.group_label ?? q.group_key}</TableHeaderCell>
                  <TableCell className="tabular-nums">{q.open_requests}</TableCell>
                  <TableCell className="tabular-nums">{q.sla_breached ?? '—'}</TableCell>
                  <TableCell className="tabular-nums">
                    {q.avg_waiting_days !== undefined
                      ? `${Math.round(q.avg_waiting_days)} dias`
                      : '—'}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </QueryState>
    </Card>
  );
}

export function CapacityPanel({ data }: { data: SituationCapacityResponse }) {
  return (
    <div className="flex flex-col gap-6">
      <Card as="section" aria-labelledby="situation-hospital">
        <CardHeader
          headingLevel={2}
          title={<span id="situation-hospital">{t.situation.capacityTitle}</span>}
          description={t.situation.capacityDescription}
        />
        {data.hospitals.length === 0 ? (
          <p className="text-sm text-fg-muted">{t.situation.empty}</p>
        ) : (
          <Table aria-label={t.situation.capacityTitle}>
            <TableHead>
              <TableRow>
                <TableHeaderCell>{t.situation.hospital}</TableHeaderCell>
                <TableHeaderCell>{t.situation.discharges}</TableHeaderCell>
                <TableHeaderCell>{t.situation.deaths}</TableHeaderCell>
                <TableHeaderCell>{t.situation.losAvg}</TableHeaderCell>
                <TableHeaderCell>{t.situation.readmissionRate}</TableHeaderCell>
                <TableHeaderCell>{t.situation.contactRate}</TableHeaderCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {data.hospitals.map((h) => (
                <TableRow key={h.hospital_cnes} data-cnes={h.hospital_cnes}>
                  <TableHeaderCell scope="row">
                    {h.health_unit_name}
                    <span className="block text-xs font-normal text-fg-muted">
                      CNES {h.hospital_cnes}
                    </span>
                  </TableHeaderCell>
                  <TableCell className="tabular-nums">
                    {formatSuppressible(h.n_discharges)}
                  </TableCell>
                  <TableCell className="tabular-nums">{formatSuppressible(h.n_deaths)}</TableCell>
                  <TableCell className="tabular-nums">
                    {formatSuppressible(h.los_avg_days, 'days')}
                  </TableCell>
                  <TableCell className="tabular-nums">
                    {formatSuppressible(h.readmission_30d_rate, 'rate')}
                  </TableCell>
                  <TableCell className="tabular-nums">
                    {formatSuppressible(h.post_discharge_contact_7d_rate, 'rate')}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </Card>
      <Card as="section" aria-labelledby="situation-queue">
        <CardHeader
          headingLevel={2}
          title={<span id="situation-queue">{t.situation.queueTitle}</span>}
          description={format(t.situation.queueDescription, {
            date: data.queue_as_of
              ? formatDateTime(data.queue_as_of.replace(' ', 'T').slice(0, 19) + '-03:00')
              : '—',
          })}
        />
        {data.queue.length === 0 ? (
          <p className="text-sm text-fg-muted">{t.situation.empty}</p>
        ) : (
          <Table aria-label={t.situation.queueTitle}>
            <TableHead>
              <TableRow>
                <TableHeaderCell>{t.situation.specialty}</TableHeaderCell>
                <TableHeaderCell>{t.situation.priority}</TableHeaderCell>
                <TableHeaderCell>{t.situation.inQueue}</TableHeaderCell>
                <TableHeaderCell>{t.situation.overdue}</TableHeaderCell>
                <TableHeaderCell>{t.situation.pendingDocs}</TableHeaderCell>
                <TableHeaderCell>{t.situation.waitP50}</TableHeaderCell>
                <TableHeaderCell>{t.situation.waitP90}</TableHeaderCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {data.queue.map((q) => (
                <TableRow key={`${q.specialty}-${q.priority}-${q.request_kind}`}>
                  <TableHeaderCell scope="row">{specialtyLabel(q.specialty)}</TableHeaderCell>
                  <TableCell>{priorityLabel(q.priority)}</TableCell>
                  <TableCell className="tabular-nums">{formatSuppressible(q.n_open)}</TableCell>
                  <TableCell className="tabular-nums">{formatSuppressible(q.n_overdue)}</TableCell>
                  <TableCell className="tabular-nums">
                    {formatSuppressible(q.n_pending_documents)}
                  </TableCell>
                  <TableCell className="tabular-nums">
                    {formatSuppressible(q.days_waiting_p50, 'days')}
                  </TableCell>
                  <TableCell className="tabular-nums">
                    {formatSuppressible(q.days_waiting_p90, 'days')}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </Card>
      <LiveQueue />
    </div>
  );
}
