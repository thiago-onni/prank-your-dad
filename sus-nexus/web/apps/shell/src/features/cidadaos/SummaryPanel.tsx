'use client';

import { useCitizenSummary, type CitizenDetail } from '@sus-nexus/api-client';
import { Badge, Card, CardHeader } from '@sus-nexus/design-system';
import {
  DataProvenancePanel,
  DataQualityIssueCard,
  formatDateTime,
} from '@sus-nexus/domain-components';
import { QueryState } from '@/components/QueryState';
import { t } from '@/i18n';

export function SummaryPanel({ citizen }: { citizen: CitizenDetail }) {
  const query = useCitizenSummary(citizen.id);
  return (
    <div className="grid gap-4 lg:grid-cols-2">
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
              <dt className="text-fg-muted">{t.citizen.lastDischarge}</dt>
              <dd className="text-right font-medium">
                {formatDateTime(s.last_hospital_discharge_at)}
              </dd>
              <dt className="text-fg-muted">{t.citizen.careLines}</dt>
              <dd className="flex flex-wrap justify-end gap-1">
                {s.care_lines && s.care_lines.length > 0
                  ? s.care_lines.map((l) => (
                      <Badge key={l} tone="primary">
                        {l}
                      </Badge>
                    ))
                  : '—'}
              </dd>
              <dt className="text-fg-muted">{t.citizen.careGaps}</dt>
              <dd className="text-right">
                <Badge tone={s.care_gaps ? 'warning' : 'success'}>{s.care_gaps ?? 0}</Badge>
              </dd>
              <dt className="text-fg-muted">{t.citizen.contactValid}</dt>
              <dd className="text-right">
                <Badge tone={s.contact_valid ? 'success' : 'danger'}>
                  {s.contact_valid ? t.app.yes : t.app.no}
                </Badge>
              </dd>
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
