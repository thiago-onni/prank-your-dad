'use client';

import { ExternalLink, Info, Lock } from 'lucide-react';
import { useState } from 'react';
import {
  useSituationCapacity,
  useSituationFilters,
  useSituationIndicators,
  useSituationRanking,
  useSituationSeries,
  useSituationTerritories,
  type SituationFiltersResponse,
  type SituationIndicatorCode,
} from '@sus-nexus/api-client';
import { useHasRole } from '@sus-nexus/auth/client';
import {
  EmptyState,
  Select,
  Tabs,
  TabsContent,
  TabsList,
  TabsTrigger,
} from '@sus-nexus/design-system';
import { formatCompetence } from '@sus-nexus/domain-components';
import { PageHeader } from '@/components/PageHeader';
import { QueryState } from '@/components/QueryState';
import { SITUATION_ROLES } from '@/lib/situacao/roles';
import { format, t } from '@/i18n';
import { BiAnalysisPanel } from './BiAnalysisPanel';
import { CapacityPanel, RiskPanel } from './CapacityPanel';
import { careLineLabel } from './format';
import { IndicatorCard, LightsSummary } from './IndicatorCards';
import { RankingPanel } from './RankingPanel';
import { SeriesChart } from './SeriesChart';
import { TerritoryPanel } from './TerritoryPanel';

export const SITUATION_TABS = ['indicadores', 'comparacao', 'territorios', 'capacidade'] as const;
export type SituationTab = (typeof SITUATION_TABS)[number];

export function isSituationTab(value: unknown): value is SituationTab {
  return typeof value === 'string' && (SITUATION_TABS as readonly string[]).includes(value);
}

const ALL = 'todos';
const DEFAULT_INDICATOR: SituationIndicatorCode = 'AGE_ABSENTEISMO';

function rememberTab(tab: string) {
  if (typeof window === 'undefined') return;
  const url = new URL(window.location.href);
  url.searchParams.set('aba', tab);
  window.history.replaceState(window.history.state, '', url);
}

function SituationContent({
  filters,
  initialTab,
}: {
  filters: SituationFiltersResponse;
  initialTab: SituationTab;
}) {
  const [competence, setCompetence] = useState(filters.default_competence);
  const [unit, setUnit] = useState(ALL);
  const [indicator, setIndicator] = useState<SituationIndicatorCode>(DEFAULT_INDICATOR);
  const [careLine, setCareLine] = useState(filters.care_lines[0] ?? '');
  const [team, setTeam] = useState(ALL);
  const cnes = unit === ALL ? undefined : unit;
  const unitName = filters.units.find((u) => u.cnes === cnes)?.name;

  const indicators = useSituationIndicators({ competence, cnes });
  const series = useSituationSeries({ indicator, competence, cnes });
  const ranking = useSituationRanking({ indicator, competence });
  const territories = useSituationTerritories({
    competence,
    care_line: careLine || undefined,
    cnes,
    team_ine: team === ALL ? undefined : team,
  });
  const capacity = useSituationCapacity({ competence, cnes });

  const competenceOptions = filters.competences.map((c) => ({
    value: c,
    label: formatCompetence(c),
  }));
  const unitOptions = [
    { value: ALL, label: t.situation.allUnits },
    ...filters.units.map((u) => ({ value: u.cnes, label: `${u.name} (${u.cnes})` })),
  ];
  const indicatorOptions = (indicators.data?.items ?? []).map((i) => ({
    value: i.code,
    label: i.name,
  }));
  const teamOptions = [
    { value: ALL, label: t.situation.allTeams },
    ...filters.territories
      .filter((tr) => !cnes || tr.health_unit_cnes === cnes)
      .map((tr) => ({ value: tr.team_ine, label: tr.team_name ?? `INE ${tr.team_ine}` })),
  ];

  return (
    <div className="flex flex-col gap-6">
      <div role="group" aria-label={t.situation.filters} className="flex flex-wrap gap-4">
        <Select
          label={t.situation.competence}
          value={competence}
          onValueChange={setCompetence}
          options={competenceOptions}
          className="w-44"
        />
        <Select
          label={t.situation.unit}
          value={unit}
          onValueChange={(v) => {
            setUnit(v);
            setTeam(ALL);
          }}
          options={unitOptions}
          className="min-w-64"
        />
      </div>

      <Tabs defaultValue={initialTab} onValueChange={rememberTab}>
        <TabsList aria-label={t.situation.tabs}>
          <TabsTrigger value="indicadores">{t.situation.tabIndicators}</TabsTrigger>
          <TabsTrigger value="comparacao">{t.situation.tabComparison}</TabsTrigger>
          <TabsTrigger value="territorios">{t.situation.tabTerritories}</TabsTrigger>
          <TabsTrigger value="capacidade">{t.situation.tabCapacity}</TabsTrigger>
        </TabsList>

        <TabsContent value="indicadores">
          <QueryState
            isLoading={indicators.isLoading}
            error={indicators.error}
            data={indicators.data}
            onRetry={() => void indicators.refetch()}
          >
            {(d) => (
              <div className="flex flex-col gap-6">
                <section aria-labelledby="situation-indicators" className="flex flex-col gap-3">
                  <div className="flex flex-wrap items-baseline justify-between gap-2">
                    <h2 id="situation-indicators" className="text-xl font-semibold">
                      {format(t.situation.indicatorsTitle, {
                        competence: formatCompetence(d.competence),
                      })}
                    </h2>
                    <p className="text-sm text-fg-muted">
                      {cnes
                        ? format(t.situation.indicatorsScopeUnit, { unit: unitName ?? cnes })
                        : t.situation.indicatorsScopeMunicipality}
                    </p>
                  </div>
                  <LightsSummary items={d.items} />
                  {d.items.length === 0 ? (
                    <p className="text-sm text-fg-muted">{t.situation.empty}</p>
                  ) : (
                    <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
                      {d.items.map((i) => (
                        <IndicatorCard
                          key={i.code}
                          indicator={i}
                          selected={i.code === indicator}
                          onSelect={setIndicator}
                        />
                      ))}
                    </div>
                  )}
                </section>
                <QueryState
                  isLoading={series.isLoading}
                  error={series.error}
                  data={series.data}
                  onRetry={() => void series.refetch()}
                  skeletonRows={3}
                >
                  {(s) => <SeriesChart series={s} />}
                </QueryState>
              </div>
            )}
          </QueryState>
        </TabsContent>

        <TabsContent value="comparacao">
          <div className="flex flex-col gap-4">
            <Select
              label={t.situation.indicator}
              value={indicator}
              onValueChange={(v) => setIndicator(v as SituationIndicatorCode)}
              options={
                indicatorOptions.length
                  ? indicatorOptions
                  : [{ value: indicator, label: indicator }]
              }
              className="max-w-xl"
            />
            <QueryState
              isLoading={ranking.isLoading}
              error={ranking.error}
              data={ranking.data}
              onRetry={() => void ranking.refetch()}
            >
              {(r) => <RankingPanel ranking={r} />}
            </QueryState>
          </div>
        </TabsContent>

        <TabsContent value="territorios">
          <div className="flex flex-col gap-4">
            <div className="flex flex-wrap gap-4">
              <Select
                label={t.situation.careLine}
                value={careLine}
                onValueChange={setCareLine}
                options={filters.care_lines.map((c) => ({ value: c, label: careLineLabel(c) }))}
                className="min-w-64"
              />
              <Select
                label={t.situation.team}
                value={team}
                onValueChange={setTeam}
                options={teamOptions}
                className="min-w-64"
              />
            </div>
            {careLine ? (
              <QueryState
                isLoading={territories.isLoading}
                error={territories.error}
                data={territories.data}
                onRetry={() => void territories.refetch()}
              >
                {(d) => <TerritoryPanel data={d} />}
              </QueryState>
            ) : (
              <p className="text-sm text-fg-muted">{t.situation.empty}</p>
            )}
          </div>
        </TabsContent>

        <TabsContent value="capacidade">
          <QueryState
            isLoading={capacity.isLoading}
            error={capacity.error}
            data={capacity.data}
            onRetry={() => void capacity.refetch()}
          >
            {(c) => (
              <div className="flex flex-col gap-6">
                <RiskPanel indicators={indicators.data?.items ?? []} capacity={c} />
                <CapacityPanel data={c} />
              </div>
            )}
          </QueryState>
        </TabsContent>
      </Tabs>

      <BiAnalysisPanel
        competence={competence}
        careLine={careLine || undefined}
        info={(code) => {
          const i = indicators.data?.items.find((x) => x.code === code);
          return { name: i?.name ?? code, unit: i?.unit ?? 'proporcao' };
        }}
      />
    </div>
  );
}

/** Sala de Situação (S26): indicadores × meta, série, comparação, territórios e capacidade. */
export function SituationPage({
  initialTab = 'indicadores',
  metabaseUrl,
}: {
  initialTab?: SituationTab;
  metabaseUrl?: string;
}) {
  const allowed = useHasRole(...SITUATION_ROLES);
  const filters = useSituationFilters({ enabled: allowed });
  if (!allowed) {
    return (
      <EmptyState
        icon={<Lock className="h-10 w-10" />}
        title={t.situation.restrictedTitle}
        description={t.situation.restrictedDescription}
      />
    );
  }
  return (
    <>
      <PageHeader
        title={t.situation.title}
        description={t.situation.description}
        actions={
          metabaseUrl ? (
            <a
              href={metabaseUrl}
              target="_blank"
              rel="noopener noreferrer"
              className="inline-flex items-center gap-1 rounded-sm text-sm font-semibold text-primary-fg-subtle underline"
            >
              {t.situation.metabaseLink}
              <ExternalLink aria-hidden="true" className="h-4 w-4" />
              <span className="sr-only">{t.situation.metabaseHint}</span>
            </a>
          ) : null
        }
      />
      <p
        role="note"
        className="mb-4 flex items-start gap-2 rounded-md border border-border bg-bg-muted p-3 text-sm"
      >
        <Info aria-hidden="true" className="mt-0.5 h-4 w-4 shrink-0" />
        {t.situation.privacyNote}
      </p>
      <QueryState
        isLoading={filters.isLoading}
        error={filters.error}
        data={filters.data}
        onRetry={() => void filters.refetch()}
      >
        {(f) => <SituationContent filters={f} initialTab={initialTab} />}
      </QueryState>
    </>
  );
}
