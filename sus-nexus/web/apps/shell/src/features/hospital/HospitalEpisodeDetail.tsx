'use client';

import Link from 'next/link';
import type { ReactNode } from 'react';
import { ArrowLeft } from 'lucide-react';
import { useHospitalEpisode, type HospitalEpisode } from '@sus-nexus/api-client';
import { Badge, Button, Card, CardHeader } from '@sus-nexus/design-system';
import {
  admissionSourceLabels,
  dischargeDispositionLabels,
  formatDateTime,
  hospitalEpisodeClassLabels,
  hospitalEpisodeStatusLabels,
  hospitalMovementLabels,
} from '@sus-nexus/domain-components';
import { CitizenBlock } from '@/components/CitizenBlock';
import { PageHeader } from '@/components/PageHeader';
import { PurposeRequired } from '@/components/PurposeRequired';
import { QueryState } from '@/components/QueryState';
import { t } from '@/i18n';
import { FollowupPanel } from './FollowupPanel';
import { RiskBadge } from './RiskBadge';

function Row({ label, children }: { label: string; children: ReactNode }) {
  return (
    <>
      <dt className="text-fg-muted">{label}</dt>
      <dd>{children}</dd>
    </>
  );
}

function EpisodeCard({ e }: { e: HospitalEpisode }) {
  const st = hospitalEpisodeStatusLabels[e.status];
  return (
    <Card as="section" aria-labelledby="hos-episode">
      <CardHeader title={<span id="hos-episode">{t.hospital.episode}</span>} />
      <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-2 text-sm">
        <Row label={t.hospital.hospital}>{e.hospital_name ?? e.hospital_cnes}</Row>
        <Row label={t.hospital.episodeClass}>{hospitalEpisodeClassLabels[e.episode_class]}</Row>
        <Row label={t.hospital.status}>
          <Badge tone={st.tone}>{st.label}</Badge>
        </Row>
        <Row label={t.hospital.admittedAt}>{formatDateTime(e.admitted_at)}</Row>
        <Row label={t.hospital.admissionSource}>
          {e.admission_source
            ? (admissionSourceLabels[e.admission_source] ?? e.admission_source)
            : '—'}
        </Row>
        <Row label={t.hospital.wardBed}>{[e.ward, e.bed].filter(Boolean).join(' / ') || '—'}</Row>
        <Row label={t.hospital.aih}>{e.aih_number ?? '—'}</Row>
        <Row label={t.hospital.risk}>
          <RiskBadge level={e.risk_level} ruleVersion={e.risk_rule_version} />
        </Row>
        <Row label={t.hospital.readmission}>
          {e.readmission_within_30d ? (
            <Badge tone="danger">{t.app.yes}</Badge>
          ) : (
            <span>{t.app.no}</span>
          )}
        </Row>
        {e.previous_episode_id ? (
          <Row label={t.hospital.previousEpisode}>
            <Link
              href={`/hospital/${e.previous_episode_id}`}
              className="text-primary-fg-subtle hover:underline"
            >
              {t.hospital.open}
            </Link>
          </Row>
        ) : null}
        {e.regulation_request_id ? (
          <Row label={t.hospital.regulationRequest}>
            <Link
              href={`/regulacao/${e.regulation_request_id}`}
              className="text-primary-fg-subtle hover:underline"
            >
              {e.regulation_request_id}
            </Link>
          </Row>
        ) : null}
      </dl>
    </Card>
  );
}

function MovementsCard({ e }: { e: HospitalEpisode }) {
  const movements = [...(e.movements ?? [])].sort((a, b) =>
    (a.occurred_at ?? '') < (b.occurred_at ?? '') ? 1 : -1,
  );
  return (
    <Card as="section" aria-labelledby="hos-movements">
      <CardHeader title={<span id="hos-movements">{t.hospital.movements}</span>} />
      {movements.length === 0 ? (
        <p className="text-sm text-fg-muted">{t.hospital.noMovements}</p>
      ) : (
        <ol className="relative flex flex-col gap-3 border-l-2 border-border pl-4">
          {movements.map((m, i) => (
            <li key={`${m.movement}-${m.occurred_at}-${i}`} className="relative text-sm">
              <span
                aria-hidden="true"
                className={`absolute -left-[1.4rem] top-1.5 h-3 w-3 rounded-full ${i === 0 ? 'bg-primary' : 'bg-border-strong'}`}
              />
              <span className="font-semibold">
                {m.movement ? (hospitalMovementLabels[m.movement] ?? m.movement) : '—'}
              </span>{' '}
              <time dateTime={m.occurred_at}>{formatDateTime(m.occurred_at)}</time>
              {m.ward || m.bed ? (
                <span className="block text-fg-muted">
                  {[m.ward, m.bed].filter(Boolean).join(' / ')}
                </span>
              ) : null}
            </li>
          ))}
        </ol>
      )}
    </Card>
  );
}

function DischargeCard({ e }: { e: HospitalEpisode }) {
  return (
    <Card as="section" aria-labelledby="hos-discharge">
      <CardHeader title={<span id="hos-discharge">{t.hospital.discharge}</span>} />
      {!e.discharged_at ? (
        <p className="text-sm text-fg-muted">{t.hospital.notDischarged}</p>
      ) : (
        <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-2 text-sm">
          <Row label={t.hospital.dischargedAt}>{formatDateTime(e.discharged_at)}</Row>
          <Row label={t.hospital.lengthOfStay}>
            {e.length_of_stay_days !== undefined
              ? `${e.length_of_stay_days} ${t.hospital.days}`
              : '—'}
          </Row>
          <Row label={t.hospital.disposition}>
            {e.disposition ? (dischargeDispositionLabels[e.disposition] ?? e.disposition) : '—'}
          </Row>
          {/* CID somente quando presente no payload; nunca inferido de outras fontes. */}
          <Row label={t.hospital.cid}>
            {e.principal_diagnosis_cid ? (
              <span className="font-mono">{e.principal_diagnosis_cid}</span>
            ) : (
              <span className="text-fg-muted">{t.hospital.cidAbsent}</span>
            )}
          </Row>
          <Row label={t.hospital.summaryDocument}>
            {e.has_summary_document ? t.hospital.summaryAvailable : t.hospital.summaryUnavailable}
          </Row>
        </dl>
      )}
    </Card>
  );
}

function CounterReferralCard({ e }: { e: HospitalEpisode }) {
  const cr = e.counter_referral;
  return (
    <Card as="section" aria-labelledby="hos-cr">
      <CardHeader title={<span id="hos-cr">{t.hospital.counterReferral}</span>} />
      {!cr?.received_at ? (
        <p className="text-sm text-fg-muted">{t.hospital.counterReferralNone}</p>
      ) : (
        <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-2 text-sm">
          <Row label={t.hospital.receivedAt}>{formatDateTime(cr.received_at)}</Row>
          <Row label={t.hospital.hasDocument}>{cr.has_document ? t.app.yes : t.app.no}</Row>
          <Row label={t.hospital.recommendations}>{cr.recommendations_count ?? '—'}</Row>
        </dl>
      )}
    </Card>
  );
}

export function HospitalEpisodeDetail({ episodeId }: { episodeId: string }) {
  const query = useHospitalEpisode(episodeId);
  return (
    <PurposeRequired>
      <PageHeader
        title={t.hospital.detailTitle}
        actions={
          <Button asChild variant="tertiary" size="sm">
            <Link href="/hospital">
              <ArrowLeft aria-hidden="true" className="h-4 w-4" />
              {t.app.back}
            </Link>
          </Button>
        }
      />
      <QueryState
        isLoading={query.isLoading}
        error={query.error}
        data={query.data}
        onRetry={() => void query.refetch()}
      >
        {(e) => (
          <div className="flex flex-col gap-6">
            <CitizenBlock citizenId={e.citizen_id} />
            <div className="grid gap-4 lg:grid-cols-2">
              <FollowupPanel episode={e} />
              <EpisodeCard e={e} />
              <DischargeCard e={e} />
              <MovementsCard e={e} />
              <CounterReferralCard e={e} />
            </div>
          </div>
        )}
      </QueryState>
    </PurposeRequired>
  );
}
