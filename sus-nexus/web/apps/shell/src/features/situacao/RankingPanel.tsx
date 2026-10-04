'use client';

import type {
  SituationInequality,
  SituationRankingResponse,
  IndicatorUnit,
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
import { format, t } from '@/i18n';
import { formatIndicatorNumber, formatIndicatorValue, formatRatio, formatTarget } from './format';
import { TrafficBadge } from './IndicatorCards';

export function InequalitySummary({
  inequality,
  unit,
  headingId,
}: {
  inequality: SituationInequality | null;
  unit: IndicatorUnit;
  headingId: string;
}) {
  return (
    <section aria-labelledby={headingId} className="rounded-md border border-border p-3">
      <h3 id={headingId} className="mb-2 text-base font-semibold">
        {t.situation.inequalityTitle}
      </h3>
      {!inequality ? (
        <p className="text-sm text-fg-muted">{t.situation.noInequality}</p>
      ) : (
        <>
          <dl className="grid grid-cols-1 gap-x-6 gap-y-1 text-sm sm:grid-cols-2">
            <div className="flex gap-2">
              <dt className="text-fg-muted">{t.situation.highest}:</dt>
              <dd>
                <span className="font-semibold tabular-nums">
                  {formatIndicatorNumber(inequality.highest.value, unit)}
                </span>{' '}
                — {inequality.highest.label}
              </dd>
            </div>
            <div className="flex gap-2">
              <dt className="text-fg-muted">{t.situation.lowest}:</dt>
              <dd>
                <span className="font-semibold tabular-nums">
                  {formatIndicatorNumber(inequality.lowest.value, unit)}
                </span>{' '}
                — {inequality.lowest.label}
              </dd>
            </div>
            <div className="flex gap-2">
              <dt className="text-fg-muted">{t.situation.difference}:</dt>
              <dd className="font-semibold tabular-nums" data-testid="inequality-difference">
                {unit === 'proporcao'
                  ? `${(inequality.difference * 100).toLocaleString('pt-BR', { maximumFractionDigits: 1 })} p.p.`
                  : formatIndicatorNumber(inequality.difference, unit)}
              </dd>
            </div>
            <div className="flex gap-2">
              <dt className="text-fg-muted">{t.situation.ratio}:</dt>
              <dd className="font-semibold tabular-nums" data-testid="inequality-ratio">
                {formatRatio(inequality.ratio)}
              </dd>
            </div>
          </dl>
          <p className="mt-1 text-xs text-fg-muted">
            {format(t.situation.compared, { n: inequality.compared, s: inequality.suppressed })}
          </p>
        </>
      )}
    </section>
  );
}

/** Ranking/comparação por unidade com desigualdade (maior × menor, razão). */
export function RankingPanel({ ranking }: { ranking: SituationRankingResponse }) {
  const meta = ranking.indicator;
  if (!meta) {
    return (
      <Card as="section">
        <p className="text-sm text-fg-muted">{t.situation.empty}</p>
      </Card>
    );
  }
  // Melhor primeiro conforme a direção; suprimidos/sem dado no fim (nunca como zero).
  const sorted = [...ranking.items].sort((a, b) => {
    if (a.value === null || a.is_suppressed) return 1;
    if (b.value === null || b.is_suppressed) return -1;
    return meta.direction === 'maior_melhor' ? b.value - a.value : a.value - b.value;
  });
  const max = Math.max(...sorted.map((i) => i.value ?? 0), meta.target ?? 0) || 1;
  let rank = 0;
  return (
    <Card as="section" aria-labelledby="situation-ranking">
      <CardHeader
        headingLevel={2}
        title={
          <span id="situation-ranking">
            {format(t.situation.rankingTitle, { indicator: meta.name })}
          </span>
        }
        description={`${t.situation.rankingDescription} ${t.situation.target}: ${formatTarget(meta.target, meta.unit, meta.direction)}.`}
      />
      <InequalitySummary
        inequality={ranking.inequality}
        unit={meta.unit}
        headingId="situation-ranking-inequality"
      />
      {sorted.length === 0 ? (
        <p className="mt-3 text-sm text-fg-muted">{t.situation.empty}</p>
      ) : (
        <div className="mt-3">
          <Table aria-label={t.situation.rankingTable}>
            <TableHead>
              <TableRow>
                <TableHeaderCell>{t.situation.position}</TableHeaderCell>
                <TableHeaderCell>{t.situation.unit}</TableHeaderCell>
                <TableHeaderCell>{t.situation.value}</TableHeaderCell>
                <TableHeaderCell>{t.situation.base}</TableHeaderCell>
                <TableHeaderCell>{t.situation.situation}</TableHeaderCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {sorted.map((i) => {
                const published = i.value !== null && !i.is_suppressed;
                if (published) rank += 1;
                return (
                  <TableRow key={i.health_unit_cnes} data-cnes={i.health_unit_cnes}>
                    <TableCell className="tabular-nums">{published ? `${rank}º` : '—'}</TableCell>
                    <TableHeaderCell scope="row">
                      {i.health_unit_name}
                      <span className="block text-xs font-normal text-fg-muted">
                        CNES {i.health_unit_cnes}
                      </span>
                    </TableHeaderCell>
                    <TableCell>
                      <span className="flex items-center gap-2">
                        <span className="min-w-[7rem] tabular-nums">
                          {formatIndicatorValue(i.value, meta.unit, i.is_suppressed)}
                        </span>
                        {published ? (
                          <span
                            aria-hidden="true"
                            className="hidden h-2 w-32 rounded-full bg-bg-muted sm:block"
                          >
                            <span
                              className="block h-2 rounded-full bg-primary"
                              style={{ width: `${Math.max(2, ((i.value ?? 0) / max) * 100)}%` }}
                            />
                          </span>
                        ) : null}
                      </span>
                    </TableCell>
                    <TableCell className="tabular-nums">
                      {i.is_suppressed
                        ? t.situation.suppressed
                        : (i.denominator?.toLocaleString('pt-BR') ?? t.situation.noData)}
                    </TableCell>
                    <TableCell>
                      <TrafficBadge
                        indicator={{
                          value: i.value,
                          target: meta.target,
                          direction: meta.direction,
                          is_suppressed: i.is_suppressed,
                          is_on_target: i.is_on_target,
                        }}
                      />
                    </TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
        </div>
      )}
    </Card>
  );
}
