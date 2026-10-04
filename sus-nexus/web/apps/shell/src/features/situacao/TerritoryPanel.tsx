'use client';

import type { SituationTerritoriesResponse, SituationTerritoryRow } from '@sus-nexus/api-client';
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
import { careLineLabel, formatSuppressible } from './format';
import { InequalitySummary } from './RankingPanel';

type Band = 'high' | 'mid' | 'low' | 'suppressed' | 'none';

/** Faixas da taxa de resolução (meta CUI_LACUNAS_RESOLVIDAS ≥ 60%). */
export function resolutionBand(row: SituationTerritoryRow): Band {
  const r = row.resolution_rate;
  if (r.suppressed) return 'suppressed';
  if (r.value === null) return 'none';
  if (r.value >= 0.6) return 'high';
  if (r.value >= 0.4) return 'mid';
  return 'low';
}

const BAND_CLASS: Record<Band, string> = {
  high: 'fill-success-subtle stroke-success',
  mid: 'fill-warning-subtle stroke-warning',
  low: 'fill-danger-subtle stroke-danger',
  suppressed: 'fill-bg-muted stroke-border-strong',
  none: 'fill-surface stroke-border-strong',
};

const BAND_MARK: Record<Band, string> = {
  high: '●',
  mid: '▲',
  low: '■',
  suppressed: '<5',
  none: '—',
};

const TILE_W = 150;
const TILE_H = 64;
const GAP = 12;
const HEADER = 40;

/**
 * Mapa esquemático (sem geometria nem tiles externos): uma coluna por unidade, um bloco por equipe,
 * colorido pela faixa de resolução + símbolo (não depende só de cor). A tabela é a alternativa.
 */
function TerritoryMap({ items }: { items: SituationTerritoryRow[] }) {
  const units = [...new Map(items.map((i) => [i.health_unit_cnes, i.health_unit_name])).entries()];
  const maxTeams = Math.max(
    1,
    ...units.map(([cnes]) => items.filter((i) => i.health_unit_cnes === cnes).length),
  );
  const width = units.length * (TILE_W + GAP) + GAP;
  const height = HEADER + maxTeams * (TILE_H + GAP) + GAP;
  return (
    <figure className="m-0">
      <figcaption className="mb-2 text-sm font-semibold">{t.situation.mapTitle}</figcaption>
      <div className="overflow-x-auto">
        <svg
          viewBox={`0 0 ${width} ${height}`}
          className="h-auto w-full min-w-[480px]"
          role="img"
          aria-label={format(t.situation.mapLabel, { n: items.length })}
        >
          <defs>
            <pattern
              id="sit-hatch"
              width="6"
              height="6"
              patternUnits="userSpaceOnUse"
              patternTransform="rotate(45)"
            >
              <line x1="0" y1="0" x2="0" y2="6" className="stroke-border-strong" strokeWidth="2" />
            </pattern>
          </defs>
          {units.map(([cnes, name], ui) => {
            const x = GAP + ui * (TILE_W + GAP);
            const teams = items.filter((i) => i.health_unit_cnes === cnes);
            return (
              <g key={cnes}>
                <text x={x} y={20} className="fill-fg text-[12px] font-semibold">
                  {name.length > 24 ? `${name.slice(0, 23)}…` : name}
                </text>
                {teams.map((row, ti) => {
                  const band = resolutionBand(row);
                  const y = HEADER + ti * (TILE_H + GAP);
                  return (
                    <g key={row.territory_key} data-band={band}>
                      <title>
                        {`${row.team_name ?? row.team_ine ?? '—'} (${name}): ${formatSuppressible(row.resolution_rate, 'rate')}`}
                      </title>
                      <rect
                        x={x}
                        y={y}
                        width={TILE_W}
                        height={TILE_H}
                        rx={6}
                        className={BAND_CLASS[band]}
                        strokeWidth={1.5}
                      />
                      {band === 'suppressed' ? (
                        <rect
                          x={x}
                          y={y}
                          width={TILE_W}
                          height={TILE_H}
                          rx={6}
                          fill="url(#sit-hatch)"
                          opacity={0.5}
                        />
                      ) : null}
                      <text x={x + 8} y={y + 22} className="fill-fg text-[11px]">
                        {(row.team_name ?? `INE ${row.team_ine ?? '—'}`).slice(0, 22)}
                      </text>
                      <text x={x + 8} y={y + 46} className="fill-fg text-[14px] font-semibold">
                        {BAND_MARK[band]}{' '}
                        {band === 'suppressed'
                          ? '(suprimido)'
                          : formatSuppressible(row.resolution_rate, 'rate')}
                      </text>
                    </g>
                  );
                })}
              </g>
            );
          })}
        </svg>
      </div>
      <ul
        aria-label={t.situation.mapLegend}
        className="mt-2 flex flex-wrap gap-4 text-xs text-fg-muted"
      >
        <li>● {t.situation.bandHigh}</li>
        <li>▲ {t.situation.bandMid}</li>
        <li>■ {t.situation.bandLow}</li>
        <li>&lt;5 {t.situation.suppressed}</li>
      </ul>
    </figure>
  );
}

export function TerritoryPanel({ data }: { data: SituationTerritoriesResponse }) {
  return (
    <Card as="section" aria-labelledby="situation-territories">
      <CardHeader
        headingLevel={2}
        title={
          <span id="situation-territories">
            {format(t.situation.territoriesTitle, {
              careLine: careLineLabel(data.care_line ?? ''),
            })}
          </span>
        }
        description={t.situation.territoriesDescription}
      />
      {data.items.length === 0 ? (
        <p className="text-sm text-fg-muted">{t.situation.empty}</p>
      ) : (
        <div className="flex flex-col gap-4">
          <TerritoryMap items={data.items} />
          <InequalitySummary
            inequality={data.inequality}
            unit="proporcao"
            headingId="situation-territories-inequality"
          />
          <Table aria-label={t.situation.territoryTable}>
            <TableHead>
              <TableRow>
                <TableHeaderCell>{t.situation.team}</TableHeaderCell>
                <TableHeaderCell>{t.situation.unit}</TableHeaderCell>
                <TableHeaderCell>{t.situation.detected}</TableHeaderCell>
                <TableHeaderCell>{t.situation.resolved}</TableHeaderCell>
                <TableHeaderCell>{t.situation.open}</TableHeaderCell>
                <TableHeaderCell>{t.situation.resolutionRate}</TableHeaderCell>
                <TableHeaderCell>{t.situation.daysOpenP50}</TableHeaderCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {data.items.map((r) => (
                <TableRow key={r.territory_key} data-territory={r.territory_key}>
                  <TableHeaderCell scope="row">
                    {r.team_name ?? '—'}
                    <span className="block text-xs font-normal text-fg-muted">
                      INE {r.team_ine ?? '—'}
                    </span>
                  </TableHeaderCell>
                  <TableCell>{r.health_unit_name}</TableCell>
                  <TableCell className="tabular-nums">{formatSuppressible(r.n_detected)}</TableCell>
                  <TableCell className="tabular-nums">{formatSuppressible(r.n_resolved)}</TableCell>
                  <TableCell className="tabular-nums">{formatSuppressible(r.n_open)}</TableCell>
                  <TableCell className="tabular-nums">
                    {formatSuppressible(r.resolution_rate, 'rate')}
                  </TableCell>
                  <TableCell className="tabular-nums">
                    {formatSuppressible(r.days_open_p50, 'days')}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      )}
    </Card>
  );
}
