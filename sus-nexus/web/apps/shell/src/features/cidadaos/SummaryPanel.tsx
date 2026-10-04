'use client';

import Link from 'next/link';
import type { ReactNode } from 'react';
import {
  useCitizenSummary,
  type CitizenDetail,
  type CitizenOperationalSummary,
} from '@sus-nexus/api-client';
import { Badge, Card, CardHeader, Skeleton } from '@sus-nexus/design-system';
import {
  DataProvenancePanel,
  DataQualityIssueCard,
  careLineLabel,
  formatDateTime,
} from '@sus-nexus/domain-components';
import { QueryState } from '@/components/QueryState';
import { t } from '@/i18n';

function StatCard({ id, title, children }: { id: string; title: string; children: ReactNode }) {
  return (
    <Card as="section" aria-labelledby={id} className="flex flex-col gap-2">
      <h3 id={id} className="text-sm font-semibold text-fg-muted">
        {title}
      </h3>
      <div className="text-base font-semibold">{children}</div>
    </Card>
  );
}

/** Cartões de continuidade do cuidado (Fase 3): alta, linhas, lacunas e contato. */
function ContinuityCards({
  citizenId,
  summary,
}: {
  citizenId: string;
  summary: CitizenOperationalSummary;
}) {
  const lines = summary.care_lines ?? [];
  const gaps = summary.care_gaps ?? 0;
  return (
    <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4 lg:col-span-2">
      <StatCard id="card-discharge" title={t.citizen.lastDischarge}>
        {summary.last_hospital_discharge_at ? (
          <>
            <time dateTime={summary.last_hospital_discharge_at}>
              {formatDateTime(summary.last_hospital_discharge_at)}
            </time>
            <Link
              href={`/hospital?cidadao=${citizenId}`}
              className="mt-1 block text-sm font-normal text-primary-fg-subtle hover:underline"
            >
              {t.nav.hospital}
            </Link>
          </>
        ) : (
          <span className="font-normal text-fg-muted">—</span>
        )}
      </StatCard>
      <StatCard id="card-lines" title={t.citizen.careLines}>
        {lines.length > 0 ? (
          <span className="flex flex-wrap gap-1">
            {lines.map((l) => (
              <Badge key={l} tone="primary">
                {careLineLabel(l)}
              </Badge>
            ))}
          </span>
        ) : (
          <span className="font-normal text-fg-muted">{t.app.none}</span>
        )}
      </StatCard>
      <StatCard id="card-gaps" title={t.citizen.careGaps}>
        <Badge tone={gaps > 0 ? 'warning' : 'success'}>{gaps}</Badge>
      </StatCard>
      <StatCard id="card-contact" title={t.citizen.contactValid}>
        {summary.contact_valid === undefined ? (
          <span className="font-normal text-fg-muted">—</span>
        ) : (
          <Badge tone={summary.contact_valid ? 'success' : 'danger'}>
            {summary.contact_valid ? t.app.yes : `${t.app.no} — atualizar cadastro`}
          </Badge>
        )}
      </StatCard>
    </div>
  );
}

export function SummaryPanel({ citizen }: { citizen: CitizenDetail }) {
  const query = useCitizenSummary(citizen.id);
  return (
    <div className="grid gap-4 lg:grid-cols-2">
      {query.data ? (
        <ContinuityCards citizenId={citizen.id} summary={query.data} />
      ) : query.isLoading ? (
        <Skeleton className="h-24 w-full lg:col-span-2" label={t.app.loading} />
      ) : null}
      <Card as="section" aria-labelledby="op-summary">
        <CardHeader title={<span id="op-summary">{t.citizen.summary}</span>} />
        <QueryState
          isLoading={query.isLoading}
          error={query.error}
          data={query.data}
          onRetry={() => void query.refetch()}
          skeletonRows={4}
        >
          {(s) => (
            <dl className="grid grid-cols-[1fr_auto] gap-x-4 gap-y-2 text-sm">
              <dt className="text-fg-muted">{t.citizen.lastApsEncounter}</dt>
              <dd className="text-right font-medium">{formatDateTime(s.last_aps_encounter_at)}</dd>
              <dt className="text-fg-muted">{t.citizen.nextAppointment}</dt>
              <dd className="text-right font-medium">{formatDateTime(s.next_appointment_at)}</dd>
              <dt className="text-fg-muted">{t.citizen.openTasks}</dt>
              <dd className="text-right font-medium tabular-nums">{s.open_tasks ?? 0}</dd>
              <dt className="text-fg-muted">{t.citizen.openRegulation}</dt>
              <dd className="text-right font-medium tabular-nums">
                {s.open_regulation_requests ?? 0}
              </dd>
              <dt className="text-fg-muted">{t.citizen.pendingExams}</dt>
              <dd className="text-right font-medium tabular-nums">{s.pending_exams ?? 0}</dd>
            </dl>
          )}
        </QueryState>
      </Card>
      <div className="flex flex-col gap-4">
        <DataProvenancePanel provenance={citizen.attribute_provenance ?? {}} />
        {citizen.data_quality_issues && citizen.data_quality_issues.length > 0 ? (
          <section aria-labelledby="dq-title" className="flex flex-col gap-2">
            <h3 id="dq-title" className="text-lg font-semibold">
              {t.citizen.dataQuality}
            </h3>
            {citizen.data_quality_issues.map((issue, i) => (
              <DataQualityIssueCard
                key={i}
                issue={{
                  id: `${citizen.id}-${i}`,
                  rule: issue.rule ?? 'DQ',
                  severity: 'warning',
                  message: issue.message ?? '',
                  record_type: 'Citizen',
                  record_id: citizen.id,
                  field: issue.field,
                  source_system:
                    citizen.attribute_provenance?.[issue.field ?? '']?.source_system ?? 'core',
                }}
              />
            ))}
          </section>
        ) : null}
      </div>
    </div>
  );
}
