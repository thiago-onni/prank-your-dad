'use client';

import Link from 'next/link';
import { useMemo, useState } from 'react';
import {
  useHealthUnits,
  useHospitalEpisodes,
  type FollowupStatus,
  type HospitalEpisodeStatus,
  type HospitalRiskLevel,
} from '@sus-nexus/api-client';
import {
  Badge,
  EmptyState,
  Input,
  Select,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeaderCell,
  TableRow,
} from '@sus-nexus/design-system';
import {
  followupStatusLabels,
  formatDateTime,
  hospitalEpisodeClassLabels,
  hospitalEpisodeStatusLabels,
  hospitalRiskLabels,
} from '@sus-nexus/domain-components';
import { PageHeader } from '@/components/PageHeader';
import { PurposeRequired } from '@/components/PurposeRequired';
import { QueryState } from '@/components/QueryState';
import { useCitizenLookup } from '@/features/cuidado/useCitizenLookup';
import { t } from '@/i18n';
import { useUserScope } from '@/lib/userScope';
import { RiskBadge } from './RiskBadge';

const ALL = 'all';
const STATUSES = Object.keys(hospitalEpisodeStatusLabels) as HospitalEpisodeStatus[];
const FOLLOWUPS = Object.keys(followupStatusLabels) as FollowupStatus[];
const RISKS: HospitalRiskLevel[] = ['high', 'medium', 'low'];

/** Converte `YYYY-MM-DD` (fuso de Brasília) para ISO 8601 no início/fim do dia. */
export function dayBoundary(day: string, end: boolean): string | undefined {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(day)) return undefined;
  return new Date(`${day}T${end ? '23:59:59' : '00:00:00'}-03:00`).toISOString();
}

/** Internações e pós-alta da população da UBS (HOS-006, CUI-006). */
export function HospitalPage({ citizenId }: { citizenId?: string } = {}) {
  const scope = useUserScope();
  // Vindo do resumo do cidadão: mostra os episódios dele, independentemente da UBS.
  const [reference, setReference] = useState(citizenId ? ALL : (scope.cnes[0] ?? ALL));
  const [status, setStatus] = useState<HospitalEpisodeStatus | typeof ALL>(ALL);
  const [followup, setFollowup] = useState<FollowupStatus | typeof ALL>(ALL);
  const [risk, setRisk] = useState<HospitalRiskLevel | typeof ALL>(ALL);
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const units = useHealthUnits({ limit: 100 });
  const ubs = (units.data?.items ?? []).filter((u) => u.kind_code === '02');
  const query = useHospitalEpisodes({
    citizen_id: citizenId,
    reference_cnes: reference === ALL ? undefined : reference,
    status: status === ALL ? undefined : status,
    followup_status: followup === ALL ? undefined : followup,
    discharged_from: dayBoundary(from, false),
    discharged_to: dayBoundary(to, true),
    limit: 200,
  });
  // O contrato não filtra por risco: o filtro é aplicado sobre a página carregada.
  const episodes = useMemo(
    () => (query.data?.items ?? []).filter((e) => risk === ALL || e.risk_level === risk),
    [query.data, risk],
  );
  const citizenIds = useMemo(() => [...new Set(episodes.map((e) => e.citizen_id))], [episodes]);
  const lookup = useCitizenLookup(citizenIds);
  const highRisk = episodes.filter((e) => e.risk_level === 'high').length;
  const pending = episodes.filter((e) => e.followup?.status === 'pending').length;

  return (
    <PurposeRequired>
      <PageHeader
        title={t.hospital.title}
        description={t.hospital.description}
        actions={
          <p role="status" className="text-sm">
            <Badge tone="primary">
              {episodes.length} {t.hospital.count}
            </Badge>{' '}
            <Badge tone={highRisk > 0 ? 'danger' : 'neutral'}>
              {highRisk} {t.hospital.highRiskCount}
            </Badge>{' '}
            <Badge tone={pending > 0 ? 'warning' : 'neutral'}>
              {pending} {t.hospital.pendingCount}
            </Badge>
          </p>
        }
      />
      <fieldset className="mb-6 grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
        <legend className="sr-only">Filtros de episódios</legend>
        <Select
          label={t.hospital.referenceUnit}
          value={reference}
          onValueChange={setReference}
          options={[
            { value: ALL, label: t.hospital.allUnits },
            ...ubs.map((u) => ({ value: u.cnes, label: u.name })),
            ...(reference !== ALL && !ubs.some((u) => u.cnes === reference)
              ? [{ value: reference, label: reference }]
              : []),
          ]}
        />
        <Select
          label={t.hospital.status}
          value={status}
          onValueChange={(v) => setStatus(v as HospitalEpisodeStatus | typeof ALL)}
          options={[
            { value: ALL, label: t.hospital.allStatuses },
            ...STATUSES.map((s) => ({ value: s, label: hospitalEpisodeStatusLabels[s].label })),
          ]}
        />
        <Select
          label={t.hospital.followup}
          value={followup}
          onValueChange={(v) => setFollowup(v as FollowupStatus | typeof ALL)}
          options={[
            { value: ALL, label: t.hospital.allFollowups },
            ...FOLLOWUPS.map((s) => ({ value: s, label: followupStatusLabels[s].label })),
          ]}
        />
        <Select
          label={t.hospital.risk}
          value={risk}
          onValueChange={(v) => setRisk(v as HospitalRiskLevel | typeof ALL)}
          options={[
            { value: ALL, label: t.hospital.allRisks },
            ...RISKS.map((r) => ({ value: r, label: hospitalRiskLabels[r].label })),
          ]}
        />
        <Input
          label={t.hospital.dischargedFrom}
          type="date"
          value={from}
          max={to || undefined}
          onChange={(e) => setFrom(e.target.value)}
        />
        <Input
          label={t.hospital.dischargedTo}
          type="date"
          value={to}
          min={from || undefined}
          onChange={(e) => setTo(e.target.value)}
        />
      </fieldset>

      <QueryState
        isLoading={query.isLoading}
        error={query.error}
        data={query.data}
        onRetry={() => void query.refetch()}
      >
        {() =>
          episodes.length === 0 ? (
            <EmptyState title={t.hospital.noEpisodes} />
          ) : (
            <Table aria-label={t.hospital.title}>
              <TableHead>
                <TableRow>
                  <TableHeaderCell>{t.hospital.citizen}</TableHeaderCell>
                  <TableHeaderCell>{t.hospital.hospital}</TableHeaderCell>
                  <TableHeaderCell>{t.hospital.dischargedAt}</TableHeaderCell>
                  <TableHeaderCell>{t.hospital.risk}</TableHeaderCell>
                  <TableHeaderCell>{t.hospital.followup}</TableHeaderCell>
                  <TableHeaderCell>
                    <span className="sr-only">{t.app.actions}</span>
                  </TableHeaderCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {episodes.map((e) => {
                  const c = lookup.citizens.get(e.citizen_id);
                  const name = c?.display_name ?? `${e.citizen_id.slice(0, 12)}…`;
                  const st = hospitalEpisodeStatusLabels[e.status];
                  const fu = e.followup?.status ? followupStatusLabels[e.followup.status] : null;
                  const late =
                    e.followup?.status === 'pending' &&
                    Boolean(e.followup.due_at) &&
                    new Date(e.followup.due_at as string).getTime() < Date.now();
                  return (
                    <TableRow key={e.id} data-risk={e.risk_level ?? 'none'}>
                      <TableCell>
                        <Link
                          href={`/cidadaos/${e.citizen_id}`}
                          className="font-medium text-primary-fg-subtle hover:underline"
                        >
                          {name}
                        </Link>
                        {c?.microarea ? (
                          <span className="block text-xs text-fg-muted">
                            {t.activeSearch.microarea} {c.microarea}
                          </span>
                        ) : null}
                      </TableCell>
                      <TableCell>
                        {e.hospital_name ?? e.hospital_cnes}
                        <span className="block text-xs text-fg-muted">
                          {hospitalEpisodeClassLabels[e.episode_class]} ·{' '}
                          <Badge tone={st.tone}>{st.label}</Badge>
                        </span>
                      </TableCell>
                      <TableCell>
                        {e.discharged_at ? formatDateTime(e.discharged_at) : '—'}
                        <span className="block text-xs text-fg-muted">
                          {t.hospital.admittedAt} {formatDateTime(e.admitted_at)}
                          {e.length_of_stay_days !== undefined
                            ? ` · ${e.length_of_stay_days} ${t.hospital.days}`
                            : ''}
                        </span>
                      </TableCell>
                      <TableCell>
                        <span className="flex flex-wrap items-center gap-1">
                          <RiskBadge level={e.risk_level} ruleVersion={e.risk_rule_version} />
                          {e.readmission_within_30d ? (
                            <Badge tone="danger">{t.hospital.readmissionShort}</Badge>
                          ) : null}
                        </span>
                      </TableCell>
                      <TableCell>
                        {fu ? (
                          <>
                            <Badge tone={late ? 'danger' : fu.tone}>{fu.label}</Badge>
                            {e.followup?.due_at && e.followup.status === 'pending' ? (
                              <span
                                className={`block text-xs ${late ? 'font-semibold text-danger-fg-subtle' : 'text-fg-muted'}`}
                              >
                                {t.hospital.followupDue} {formatDateTime(e.followup.due_at)}
                              </span>
                            ) : null}
                          </>
                        ) : (
                          '—'
                        )}
                      </TableCell>
                      <TableCell>
                        <Link
                          href={`/hospital/${e.id}`}
                          aria-label={`${t.hospital.open}: ${name}`}
                          className="text-sm font-semibold text-primary-fg-subtle hover:underline"
                        >
                          {t.hospital.open}
                        </Link>
                      </TableCell>
                    </TableRow>
                  );
                })}
              </TableBody>
            </Table>
          )
        }
      </QueryState>
    </PurposeRequired>
  );
}
