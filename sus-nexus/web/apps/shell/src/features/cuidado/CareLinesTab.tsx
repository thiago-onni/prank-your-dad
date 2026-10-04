'use client';

import Link from 'next/link';
import { useMemo, useState } from 'react';
import {
  useCareGaps,
  useCarePlans,
  useHealthUnits,
  useProtocols,
  type CarePlan,
} from '@sus-nexus/api-client';
import { Badge, Card, CardHeader, EmptyState, Select } from '@sus-nexus/design-system';
import { CareGapCard, careLineLabel } from '@sus-nexus/domain-components';
import { QueryState } from '@/components/QueryState';
import { format, t } from '@/i18n';
import { useUserScope } from '@/lib/userScope';
import { useCitizenLookup } from './useCitizenLookup';

const ALL = 'all';

export interface CareLineStats {
  plans: number;
  items: number;
  done: number;
  late: number;
  onTimePct: number;
  latePct: number;
}

/** Itens cancelados não contam; "atrasado" = `overdue` do core; o restante está em dia. */
export function careLineStats(plans: CarePlan[]): CareLineStats {
  const items = plans.flatMap((p) => p.items).filter((i) => i.status !== 'cancelled');
  const late = items.filter((i) => i.overdue).length;
  const done = items.filter((i) => i.status === 'done').length;
  const latePct = items.length ? Math.round((late / items.length) * 100) : 0;
  return {
    plans: plans.length,
    items: items.length,
    done,
    late,
    latePct,
    onTimePct: items.length ? 100 - latePct : 100,
  };
}

/** Linhas de cuidado: planos ativos da equipe por linha, % de itens em dia/atrasados e lacunas. */
export function CareLinesTab() {
  const scope = useUserScope();
  const [cnes, setCnes] = useState(scope.cnes[0] ?? ALL);
  const [team, setTeam] = useState(scope.teams[0] ?? ALL);
  const units = useHealthUnits({ limit: 100 });
  const ubs = (units.data?.items ?? []).filter((u) => u.kind_code === '02');
  const filters = {
    cnes: cnes === ALL ? undefined : cnes,
    team_ine: team === ALL ? undefined : team,
  };
  const plans = useCarePlans({ ...filters, status: 'active', limit: 200 });
  const gaps = useCareGaps({ ...filters, status: 'open' });
  const protocols = useProtocols();
  const protocolName = (id: string | undefined) => protocols.data?.find((p) => p.id === id)?.name;

  const planItems = useMemo(() => plans.data?.items ?? [], [plans.data]);
  const teams = useMemo(
    () =>
      [
        ...new Set(
          [...planItems.map((p) => p.team_ine), team === ALL ? undefined : team].filter(
            (x): x is string => Boolean(x),
          ),
        ),
      ].sort(),
    [planItems, team],
  );
  const lines = useMemo(() => {
    const by = new Map<string, CarePlan[]>();
    for (const p of planItems) by.set(p.care_line, [...(by.get(p.care_line) ?? []), p]);
    return [...by.entries()].sort((a, b) => a[0].localeCompare(b[0]));
  }, [planItems]);
  const citizenIds = useMemo(() => [...new Set(planItems.map((p) => p.citizen_id))], [planItems]);
  const lookup = useCitizenLookup(citizenIds);

  return (
    <section aria-labelledby="care-lines-title" className="flex flex-col gap-4">
      <div>
        <h2 id="care-lines-title" className="text-xl font-semibold">
          {t.careLines.tab}
        </h2>
        <p className="text-sm text-fg-muted">{t.careLines.description}</p>
      </div>
      <fieldset className="grid gap-3 sm:grid-cols-2">
        <legend className="sr-only">Filtros das linhas de cuidado</legend>
        <Select
          label={t.activeSearch.unit}
          value={cnes}
          onValueChange={(v) => {
            setCnes(v);
            setTeam(ALL);
          }}
          options={[
            { value: ALL, label: t.hospital.allUnits },
            ...ubs.map((u) => ({ value: u.cnes, label: u.name })),
            ...(cnes !== ALL && !ubs.some((u) => u.cnes === cnes)
              ? [{ value: cnes, label: cnes }]
              : []),
          ]}
        />
        <Select
          label={t.activeSearch.team}
          value={team}
          onValueChange={setTeam}
          options={[
            { value: ALL, label: t.activeSearch.allTeams },
            ...teams.map((x) => ({ value: x, label: x })),
          ]}
        />
      </fieldset>
      <QueryState
        isLoading={plans.isLoading}
        error={plans.error}
        data={plans.data}
        onRetry={() => void plans.refetch()}
      >
        {() =>
          lines.length === 0 ? (
            <EmptyState title={t.careLines.none} />
          ) : (
            <div className="grid gap-4 xl:grid-cols-2">
              {lines.map(([line, linePlans]) => {
                const stats = careLineStats(linePlans);
                const lineGaps = (gaps.data?.items ?? [])
                  .filter((g) => g.care_line === line)
                  .sort((a, b) => (b.days_overdue ?? 0) - (a.days_overdue ?? 0));
                const headingId = `line-${line}`;
                return (
                  <Card key={line} as="section" aria-labelledby={headingId}>
                    <CardHeader
                      title={
                        <span id={headingId}>
                          {careLineLabel(line)}{' '}
                          <Badge tone="neutral">
                            {stats.plans} {t.careLines.plans}
                          </Badge>
                        </span>
                      }
                      description={protocolName(linePlans[0]?.protocol_id)}
                    />
                    <p className="text-sm font-medium">
                      {format(t.careLines.itemsSummary, {
                        onTime: stats.onTimePct,
                        late: stats.latePct,
                      })}
                    </p>
                    <div
                      aria-hidden="true"
                      className="mt-2 flex h-3 w-full overflow-hidden rounded-full bg-bg-muted"
                    >
                      <div className="bg-success" style={{ width: `${stats.onTimePct}%` }} />
                      <div className="bg-danger" style={{ width: `${stats.latePct}%` }} />
                    </div>
                    <p className="mt-1 text-xs text-fg-muted">
                      {format(t.carePlan.progress, { done: stats.done, total: stats.items })}
                    </p>

                    <h4 className="mt-4 text-base font-semibold">{t.careLines.topGaps}</h4>
                    {lineGaps.length === 0 ? (
                      <p className="text-sm text-fg-muted">{t.careLines.noGaps}</p>
                    ) : (
                      <ul className="mt-2 flex flex-col gap-2">
                        {lineGaps.slice(0, 3).map((g) => (
                          <li key={g.id}>
                            <CareGapCard
                              gap={g}
                              showCitizen
                              headingLevel="h4"
                              protocolName={protocolName(g.protocol_id)}
                            />
                          </li>
                        ))}
                      </ul>
                    )}

                    <h4 className="mt-4 text-base font-semibold">{t.careLines.planList}</h4>
                    <ul className="mt-1 flex flex-col divide-y divide-border text-sm">
                      {linePlans.map((p) => {
                        const c = lookup.citizens.get(p.citizen_id);
                        const s = careLineStats([p]);
                        return (
                          <li key={p.id} className="flex flex-wrap items-center gap-2 py-2">
                            <Link
                              href={`/cidadaos/${p.citizen_id}?aba=plano`}
                              className="font-medium text-primary-fg-subtle hover:underline"
                            >
                              {c?.display_name ?? `${p.citizen_id.slice(0, 12)}…`}
                            </Link>
                            {c?.microarea ? (
                              <span className="text-fg-muted">
                                · {t.activeSearch.microarea} {c.microarea}
                              </span>
                            ) : null}
                            <span className="ml-auto flex gap-1">
                              <Badge tone="success">
                                {s.items - s.late} {t.careLines.onTime}
                              </Badge>
                              <Badge tone={s.late > 0 ? 'danger' : 'neutral'}>
                                {s.late} {t.careLines.late}
                              </Badge>
                            </span>
                          </li>
                        );
                      })}
                    </ul>
                  </Card>
                );
              })}
            </div>
          )
        }
      </QueryState>
    </section>
  );
}
