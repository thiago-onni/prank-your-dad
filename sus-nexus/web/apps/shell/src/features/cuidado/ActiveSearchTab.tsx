'use client';

import Link from 'next/link';
import { useMemo, useState } from 'react';
import { useCareGaps, useHealthUnits, type CareGap, type CareGapKind } from '@sus-nexus/api-client';
import {
  Badge,
  Button,
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
import { careGapKindLabels, careLineLabel, formatDate } from '@sus-nexus/domain-components';
import { QueryState } from '@/components/QueryState';
import { format, t } from '@/i18n';
import { useUserScope } from '@/lib/userScope';
import { ResolveGapDialog } from './ResolveGapDialog';

const ALL = 'all';
const MINE = 'mine';
const GAP_KINDS = Object.keys(careGapKindLabels) as CareGapKind[];

function uniq(values: (string | undefined)[]): string[] {
  return [...new Set(values.filter((v): v is string => Boolean(v)))].sort();
}

/**
 * Busca ativa (CUI-003/004): lacunas abertas por UBS/equipe/microárea com dias de atraso e
 * validade do contato. ACS vê apenas as próprias microáreas (o core aplica o mesmo escopo).
 */
export function ActiveSearchTab() {
  const scope = useUserScope();
  const acs = scope.restrictedToMicroareas;
  const [cnes, setCnes] = useState(scope.cnes[0] ?? ALL);
  const [team, setTeam] = useState(ALL);
  const [microarea, setMicroarea] = useState(acs ? MINE : ALL);
  const [careLine, setCareLine] = useState(ALL);
  const [gapKind, setGapKind] = useState<CareGapKind | typeof ALL>(ALL);
  const [minDays, setMinDays] = useState('');
  const [resolving, setResolving] = useState<CareGap | null>(null);
  const units = useHealthUnits({ limit: 100 });
  const ubs = (units.data?.items ?? []).filter((u) => u.kind_code === '02');

  const microareaParam = microarea === ALL || microarea === MINE ? undefined : microarea;
  const minDaysNumber = Number.parseInt(minDays, 10);
  const query = useCareGaps(
    {
      cnes: cnes === ALL ? undefined : cnes,
      team_ine: team === ALL ? undefined : team,
      microarea: microareaParam,
      care_line: careLine === ALL ? undefined : careLine,
      gap_kind: gapKind === ALL ? undefined : gapKind,
      min_days_overdue:
        Number.isFinite(minDaysNumber) && minDaysNumber > 0 ? minDaysNumber : undefined,
      status: 'open',
    },
    // ACS sem microárea vinculada não consulta (o core negaria de qualquer forma).
    { enabled: !acs || scope.microareas.length > 0 },
  );

  const items = useMemo(() => {
    const all = query.data?.items ?? [];
    // Defesa em profundidade na UI: o ACS nunca vê lacunas fora das suas microáreas.
    return acs ? all.filter((g) => g.microarea && scope.microareas.includes(g.microarea)) : all;
  }, [query.data, acs, scope.microareas]);
  const teams = useMemo(() => uniq(items.map((g) => g.team_ine)), [items]);
  const careLines = useMemo(() => uniq(items.map((g) => g.care_line)), [items]);
  const microareas = acs ? scope.microareas : uniq(items.map((g) => g.microarea));
  const invalid = items.filter((g) => g.contact_valid === false).length;

  if (acs && scope.microareas.length === 0) {
    return <EmptyState title={t.activeSearch.title} description={t.activeSearch.acsNoMicroarea} />;
  }

  return (
    <section aria-labelledby="active-search-title" className="flex flex-col gap-4">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div>
          <h2 id="active-search-title" className="text-xl font-semibold">
            {t.activeSearch.title}
          </h2>
          <p className="text-sm text-fg-muted">{t.activeSearch.description}</p>
        </div>
        <p role="status" className="text-sm">
          <Badge tone="primary">
            {items.length} {t.activeSearch.count}
          </Badge>{' '}
          <Badge tone={invalid > 0 ? 'danger' : 'neutral'}>
            {invalid} {t.activeSearch.invalidContacts}
          </Badge>
        </p>
      </div>
      {acs ? (
        <p className="rounded-md border border-border bg-info-subtle p-3 text-sm">
          {format(t.activeSearch.acsNotice, { list: scope.microareas.join(', ') })}
        </p>
      ) : null}
      <fieldset className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
        <legend className="sr-only">Filtros da busca ativa</legend>
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
            ...uniq([...teams, team === ALL ? undefined : team]).map((x) => ({
              value: x,
              label: x,
            })),
          ]}
        />
        <Select
          label={t.activeSearch.microarea}
          value={microarea}
          onValueChange={setMicroarea}
          options={[
            acs
              ? { value: MINE, label: t.activeSearch.myMicroareas }
              : { value: ALL, label: t.activeSearch.allMicroareas },
            ...uniq([...microareas, microareaParam]).map((m) => ({
              value: m,
              label: `${t.activeSearch.microarea} ${m}`,
            })),
          ]}
        />
        <Select
          label={t.activeSearch.careLine}
          value={careLine}
          onValueChange={setCareLine}
          options={[
            { value: ALL, label: t.activeSearch.allCareLines },
            ...uniq([...careLines, careLine === ALL ? undefined : careLine]).map((l) => ({
              value: l,
              label: careLineLabel(l),
            })),
          ]}
        />
        <Select
          label={t.activeSearch.gapKind}
          value={gapKind}
          onValueChange={(v) => setGapKind(v as CareGapKind | typeof ALL)}
          options={[
            { value: ALL, label: t.activeSearch.allKinds },
            ...GAP_KINDS.map((k) => ({ value: k, label: careGapKindLabels[k] })),
          ]}
        />
        <Input
          label={t.activeSearch.minDays}
          type="number"
          inputMode="numeric"
          min={0}
          value={minDays}
          onChange={(e) => setMinDays(e.target.value)}
        />
      </fieldset>

      <QueryState
        isLoading={query.isLoading}
        error={query.error}
        data={query.data}
        onRetry={() => void query.refetch()}
      >
        {() =>
          items.length === 0 ? (
            <EmptyState title={t.activeSearch.none} />
          ) : (
            <Table aria-label={t.activeSearch.title}>
              <TableHead>
                <TableRow>
                  <TableHeaderCell>{t.activeSearch.citizen}</TableHeaderCell>
                  <TableHeaderCell>{t.activeSearch.gapKind}</TableHeaderCell>
                  <TableHeaderCell>{t.activeSearch.careLine}</TableHeaderCell>
                  <TableHeaderCell>{t.activeSearch.microarea}</TableHeaderCell>
                  <TableHeaderCell>{t.activeSearch.daysOverdue}</TableHeaderCell>
                  <TableHeaderCell>{t.activeSearch.contact}</TableHeaderCell>
                  <TableHeaderCell>
                    <span className="sr-only">{t.app.actions}</span>
                  </TableHeaderCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {items.map((g) => (
                  <TableRow key={g.id}>
                    <TableCell>
                      <Link
                        href={`/cidadaos/${g.citizen_id}`}
                        className="font-medium text-primary-fg-subtle hover:underline"
                      >
                        {g.citizen_display_name ?? `${g.citizen_id.slice(0, 12)}…`}
                      </Link>
                      <span className="block text-xs text-fg-muted">
                        {t.activeSearch.team} {g.team_ine ?? '—'} · v{g.protocol_version}
                      </span>
                    </TableCell>
                    <TableCell>
                      {careGapKindLabels[g.gap_kind]}
                      <span className="block text-xs text-fg-muted">
                        {t.carePlan.expectedBy} {formatDate(g.expected_by)}
                      </span>
                    </TableCell>
                    <TableCell>{careLineLabel(g.care_line)}</TableCell>
                    <TableCell>{g.microarea ?? '—'}</TableCell>
                    <TableCell>
                      <Badge tone={(g.days_overdue ?? 0) > 30 ? 'danger' : 'warning'}>
                        {g.days_overdue ?? 0} {t.hospital.days}
                      </Badge>
                    </TableCell>
                    <TableCell>
                      {g.contact_valid === false ? (
                        <Badge tone="danger">{t.activeSearch.contactInvalid}</Badge>
                      ) : g.contact_valid ? (
                        <Badge tone="success">{t.activeSearch.contactValid}</Badge>
                      ) : (
                        '—'
                      )}
                    </TableCell>
                    <TableCell>
                      <Button
                        size="sm"
                        variant="secondary"
                        onClick={() => setResolving(g)}
                        aria-label={`${t.activeSearch.resolve}: ${careGapKindLabels[g.gap_kind]} — ${g.citizen_display_name ?? g.citizen_id}`}
                      >
                        {t.activeSearch.resolve}
                      </Button>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )
        }
      </QueryState>
      <ResolveGapDialog gap={resolving} onClose={() => setResolving(null)} />
    </section>
  );
}
