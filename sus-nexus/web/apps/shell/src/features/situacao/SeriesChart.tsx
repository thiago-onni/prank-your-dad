'use client';

import { useId, useState } from 'react';
import type { SituationSeriesResponse } from '@sus-nexus/api-client';
import {
  Button,
  Card,
  CardHeader,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeaderCell,
  TableRow,
} from '@sus-nexus/design-system';
import { formatCompetence } from '@sus-nexus/domain-components';
import { format, t } from '@/i18n';
import { formatIndicatorNumber, formatIndicatorValue, formatTarget } from './format';
import { TrafficBadge } from './IndicatorCards';

const W = 640;
const H = 220;
const PAD = { top: 16, right: 16, bottom: 36, left: 56 };

/** Série histórica: linha única (sem legenda), meta tracejada, pontos suprimidos como lacuna. */
export function SeriesChart({ series }: { series: SituationSeriesResponse }) {
  const uid = useId();
  const [showTable, setShowTable] = useState(false);
  const meta = series.indicator;
  if (!meta || series.points.length === 0) {
    return (
      <Card as="section">
        <p className="text-sm text-fg-muted">{t.situation.empty}</p>
      </Card>
    );
  }
  const pts = series.points;
  const values = pts.map((p) => p.value).filter((v): v is number => v !== null);
  const maxV = Math.max(...values, meta.target ?? 0, meta.unit === 'proporcao' ? 0.1 : 1) * 1.1;
  const x = (i: number) =>
    PAD.left + (pts.length === 1 ? 0 : (i * (W - PAD.left - PAD.right)) / (pts.length - 1));
  const y = (v: number) => H - PAD.bottom - (v / maxV) * (H - PAD.top - PAD.bottom);

  // Segmentos contínuos: um ponto suprimido/sem dado interrompe a linha (nunca vira zero).
  const segments: string[] = [];
  let current: string[] = [];
  pts.forEach((p, i) => {
    if (p.value === null) {
      if (current.length) segments.push(current.join(' '));
      current = [];
    } else {
      current.push(`${current.length ? 'L' : 'M'}${x(i).toFixed(1)},${y(p.value).toFixed(1)}`);
    }
  });
  if (current.length) segments.push(current.join(' '));

  const last = pts[pts.length - 1]!;
  const summary = `${pts.length} competências de ${formatCompetence(pts[0]!.competence)} a ${formatCompetence(last.competence)}; última: ${formatIndicatorValue(last.value, meta.unit, last.is_suppressed)}; meta ${formatTarget(meta.target, meta.unit, meta.direction)}${pts.some((p) => p.is_suppressed) ? `; ${pts.filter((p) => p.is_suppressed).length} competência(s) suprimida(s)` : ''}.`;
  const ticks = [0, maxV / 2, maxV];
  const headingId = `${uid}-series`;
  const tableId = `${uid}-series-table`;

  return (
    <Card as="section" aria-labelledby={headingId}>
      <CardHeader
        headingLevel={2}
        title={
          <span id={headingId}>{format(t.situation.seriesTitle, { indicator: meta.name })}</span>
        }
        description={t.situation.seriesDescription}
        actions={
          <Button
            size="sm"
            variant="secondary"
            aria-expanded={showTable}
            aria-controls={tableId}
            onClick={() => setShowTable((s) => !s)}
          >
            {showTable ? t.situation.hideTable : t.situation.showTable}
          </Button>
        }
      />
      <svg
        viewBox={`0 0 ${W} ${H}`}
        className="h-auto w-full"
        role="img"
        aria-label={format(t.situation.seriesChartLabel, { indicator: meta.name, summary })}
      >
        {ticks.map((tick) => (
          <g key={tick}>
            <line
              x1={PAD.left}
              x2={W - PAD.right}
              y1={y(tick)}
              y2={y(tick)}
              className="stroke-border"
              strokeWidth={1}
            />
            <text
              x={PAD.left - 8}
              y={y(tick) + 4}
              textAnchor="end"
              className="fill-fg-muted text-[11px]"
            >
              {formatIndicatorNumber(tick, meta.unit)}
            </text>
          </g>
        ))}
        {meta.target !== null ? (
          <g>
            <line
              x1={PAD.left}
              x2={W - PAD.right}
              y1={y(meta.target)}
              y2={y(meta.target)}
              className="stroke-fg-muted"
              strokeWidth={1.5}
              strokeDasharray="6 4"
            />
            <text
              x={W - PAD.right}
              y={y(meta.target) - 6}
              textAnchor="end"
              className="fill-fg text-[11px]"
            >
              {t.situation.target} {formatTarget(meta.target, meta.unit, meta.direction)}
            </text>
          </g>
        ) : null}
        {segments.map((d, i) => (
          <path key={i} d={d} fill="none" className="stroke-primary" strokeWidth={2} />
        ))}
        {pts.map((p, i) => (
          <g key={p.competence}>
            {p.value !== null ? (
              <circle
                cx={x(i)}
                cy={y(p.value)}
                r={4}
                className="fill-primary stroke-surface"
                strokeWidth={2}
              >
                <title>
                  {formatCompetence(p.competence)}: {formatIndicatorNumber(p.value, meta.unit)}
                </title>
              </circle>
            ) : (
              <text
                x={x(i)}
                y={H - PAD.bottom - 6}
                textAnchor="middle"
                className="fill-fg-muted text-[10px]"
              >
                <title>
                  {formatCompetence(p.competence)}:{' '}
                  {p.is_suppressed ? t.situation.suppressed : t.situation.noData}
                </title>
                {p.is_suppressed ? '<5' : '—'}
              </text>
            )}
            {i % 2 === pts.length % 2 || i === pts.length - 1 ? (
              <text x={x(i)} y={H - 12} textAnchor="middle" className="fill-fg-muted text-[11px]">
                {formatCompetence(p.competence)}
              </text>
            ) : null}
          </g>
        ))}
      </svg>
      <div id={tableId} hidden={!showTable}>
        {showTable ? (
          <Table aria-label={t.situation.seriesTable}>
            <TableHead>
              <TableRow>
                <TableHeaderCell>{t.situation.competence}</TableHeaderCell>
                <TableHeaderCell>{t.situation.value}</TableHeaderCell>
                <TableHeaderCell>{t.situation.base}</TableHeaderCell>
                <TableHeaderCell>{t.situation.situation}</TableHeaderCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {pts.map((p) => (
                <TableRow key={p.competence}>
                  <TableHeaderCell scope="row">{formatCompetence(p.competence)}</TableHeaderCell>
                  <TableCell className="tabular-nums">
                    {formatIndicatorValue(p.value, meta.unit, p.is_suppressed)}
                  </TableCell>
                  <TableCell className="tabular-nums">
                    {p.is_suppressed
                      ? t.situation.suppressed
                      : (p.denominator?.toLocaleString('pt-BR') ?? t.situation.noData)}
                  </TableCell>
                  <TableCell>
                    <TrafficBadge
                      indicator={{
                        value: p.value,
                        target: meta.target,
                        direction: meta.direction,
                        is_suppressed: p.is_suppressed,
                        is_on_target: p.is_on_target,
                      }}
                    />
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        ) : null}
      </div>
    </Card>
  );
}
