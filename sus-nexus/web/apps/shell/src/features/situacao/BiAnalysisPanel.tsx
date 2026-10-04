'use client';

import { AlertTriangle, Bot, Sparkles } from 'lucide-react';
import { useState } from 'react';
import {
  parseBiSituationOutput,
  useRunBiSituationAnalyst,
  type AgentRunRecord,
  type BiSituationOutput,
  type IndicatorUnit,
} from '@sus-nexus/api-client';
import { useHasRole } from '@sus-nexus/auth/client';
import {
  Badge,
  Button,
  Card,
  CardHeader,
  ErrorState,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeaderCell,
  TableRow,
  Textarea,
} from '@sus-nexus/design-system';
import { formatCompetence } from '@sus-nexus/domain-components';
import { SITUATION_ROLES } from '@/lib/situacao/roles';
import { describeError } from '@/lib/problem';
import { format, t } from '@/i18n';

export interface BiIndicatorInfo {
  name: string;
  unit: IndicatorUnit;
}

function fmt(value: number | null | undefined, unit: string | undefined): string {
  if (value === null || value === undefined) return '—';
  if (unit === 'proporcao')
    return new Intl.NumberFormat('pt-BR', { style: 'percent', maximumFractionDigits: 1 }).format(
      value,
    );
  return `${value.toLocaleString('pt-BR', { maximumFractionDigits: 1 })}${unit === 'dias' ? ' dias' : ''}`;
}

/** Saída utilizável ou `null` quando o agente devolveu `invalid_output` (ou forma inesperada). */
export function usableOutput(run: AgentRunRecord): BiSituationOutput | null {
  if (run.status === 'invalid_output' || run.validation_status === 'invalid_output') return null;
  if (run.status !== 'completed') return null;
  return parseBiSituationOutput(run.output);
}

function Section({
  id,
  title,
  children,
}: {
  id: string;
  title: string;
  children: React.ReactNode;
}) {
  return (
    <section aria-labelledby={id} className="flex flex-col gap-2">
      <h3 id={id} className="text-base font-semibold">
        {title}
      </h3>
      {children}
    </section>
  );
}

function AnalysisResult({
  output,
  run,
  info,
}: {
  output: BiSituationOutput;
  run: AgentRunRecord;
  info: (code: string) => BiIndicatorInfo;
}) {
  const [showSources, setShowSources] = useState(false);
  const name = (code: string) => info(code).name;
  const none = <p className="text-sm text-fg-muted">{t.biAnalysis.none}</p>;
  return (
    <div className="flex flex-col gap-5" data-testid="bi-analysis-result">
      <Section id="bi-summary" title={t.biAnalysis.summary}>
        <p className="text-sm">{output.summary}</p>
        <p className="text-xs text-fg-muted">
          {format(t.biAnalysis.meta, { run: run.id, model: run.model, version: run.agent_version })}
        </p>
      </Section>
      <Section id="bi-off-target" title={t.biAnalysis.offTarget}>
        {output.off_target.length === 0 ? (
          none
        ) : (
          <ul className="flex flex-col gap-1 text-sm">
            {output.off_target.map((o) => (
              <li key={o.indicator_code}>
                <strong>{name(o.indicator_code)}</strong>:{' '}
                {fmt(o.value, info(o.indicator_code).unit)} (meta{' '}
                {fmt(o.target, info(o.indicator_code).unit)}){o.comment ? ` — ${o.comment}` : ''}
              </li>
            ))}
          </ul>
        )}
      </Section>
      <Section id="bi-trends" title={t.biAnalysis.trends}>
        {output.trends.length === 0 ? (
          none
        ) : (
          <ul className="flex flex-col gap-1 text-sm">
            {output.trends.map((tr) => (
              <li key={tr.indicator_code}>
                <strong>{name(tr.indicator_code)}</strong>:{' '}
                <Badge
                  tone={
                    tr.classification === 'piora'
                      ? 'danger'
                      : tr.classification === 'melhora'
                        ? 'success'
                        : 'neutral'
                  }
                >
                  {t.biAnalysis.trendLabels[tr.classification]}
                </Badge>{' '}
                {tr.first_competence ? formatCompetence(tr.first_competence) : '—'}{' '}
                {fmt(tr.first_value, info(tr.indicator_code).unit)} →{' '}
                {tr.last_competence ? formatCompetence(tr.last_competence) : '—'}{' '}
                {fmt(tr.last_value, info(tr.indicator_code).unit)}
              </li>
            ))}
          </ul>
        )}
      </Section>
      <Section id="bi-inequalities" title={t.biAnalysis.inequalities}>
        {output.inequalities.length === 0 ? (
          none
        ) : (
          <ul className="flex flex-col gap-1 text-sm">
            {output.inequalities.map((q, i) => (
              <li key={`${q.indicator_code}-${q.level}-${i}`}>
                <strong>{name(q.indicator_code)}</strong> ({q.level}): maior{' '}
                {fmt(q.highest.value, info(q.indicator_code).unit)} (
                {q.highest.health_unit_cnes
                  ? `CNES ${q.highest.health_unit_cnes}`
                  : `INE ${q.highest.team_ine ?? '—'}`}
                ) × menor {fmt(q.lowest.value, info(q.indicator_code).unit)} (
                {q.lowest.health_unit_cnes
                  ? `CNES ${q.lowest.health_unit_cnes}`
                  : `INE ${q.lowest.team_ine ?? '—'}`}
                )
                {q.ratio != null
                  ? ` · ${format(t.biAnalysis.ratio, { ratio: `${q.ratio.toLocaleString('pt-BR', { maximumFractionDigits: 2 })}×` })}`
                  : ''}
              </li>
            ))}
          </ul>
        )}
      </Section>
      <Section id="bi-hypotheses" title={t.biAnalysis.hypotheses}>
        {output.hypotheses.length === 0 ? (
          none
        ) : (
          <ul className="flex flex-col gap-2 text-sm">
            {output.hypotheses.map((h, i) => (
              <li
                key={i}
                data-kind="hipotese"
                className="rounded-md border border-dashed border-warning p-2"
              >
                <Badge tone="warning">{t.biAnalysis.hypothesisBadge}</Badge> {h.statement}
                {h.how_to_verify ? (
                  <span className="block text-xs text-fg-muted">
                    {t.biAnalysis.howToVerify}: {h.how_to_verify}
                  </span>
                ) : null}
              </li>
            ))}
          </ul>
        )}
      </Section>
      <Section id="bi-recommendations" title={t.biAnalysis.recommendations}>
        {output.recommendations.length === 0 ? (
          none
        ) : (
          <ul className="flex flex-col gap-2 text-sm">
            {output.recommendations.map((r, i) => (
              <li key={i}>
                <strong>{r.action}</strong>
                {r.rationale ? <span className="block">{r.rationale}</span> : null}
                {r.responsible_area ? (
                  <span className="block text-xs text-fg-muted">
                    {t.biAnalysis.responsible}: {r.responsible_area}
                  </span>
                ) : null}
              </li>
            ))}
          </ul>
        )}
      </Section>
      {output.data_limitations.length > 0 ? (
        <Section id="bi-limitations" title={t.biAnalysis.limitations}>
          <ul className="list-disc pl-5 text-sm">
            {output.data_limitations.map((l, i) => (
              <li key={i}>{l}</li>
            ))}
          </ul>
        </Section>
      ) : null}
      <Section id="bi-sources" title={t.biAnalysis.sources}>
        <Button
          size="sm"
          variant="secondary"
          aria-expanded={showSources}
          aria-controls="bi-sources-table"
          onClick={() => setShowSources((s) => !s)}
          className="self-start"
        >
          {showSources
            ? t.biAnalysis.hideSources
            : format(t.biAnalysis.showSources, { n: output.sources.length })}
        </Button>
        <div id="bi-sources-table" hidden={!showSources}>
          {showSources ? (
            <Table aria-label={t.biAnalysis.sourcesTable}>
              <TableHead>
                <TableRow>
                  <TableHeaderCell>{t.situation.indicator}</TableHeaderCell>
                  <TableHeaderCell>{t.situation.competence}</TableHeaderCell>
                  <TableHeaderCell>{t.biAnalysis.scope}</TableHeaderCell>
                  <TableHeaderCell>{t.situation.value}</TableHeaderCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {output.sources.map((s, i) => (
                  <TableRow key={i}>
                    <TableHeaderCell scope="row">{name(s.indicator_code)}</TableHeaderCell>
                    <TableCell>{formatCompetence(s.competence)}</TableCell>
                    <TableCell>
                      {s.scope}
                      {s.health_unit_cnes ? ` · CNES ${s.health_unit_cnes}` : ''}
                      {s.team_ine ? ` · INE ${s.team_ine}` : ''}
                    </TableCell>
                    <TableCell className="tabular-nums">
                      {s.suppressed
                        ? t.situation.suppressed
                        : fmt(s.value, info(s.indicator_code).unit)}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          ) : null}
        </div>
      </Section>
    </div>
  );
}

/** Painel "Análise assistida (IA)" — agente bi_situation_analyst via BFF `/api/ai`. */
export function BiAnalysisPanel({
  competence,
  careLine,
  info,
}: {
  competence: string;
  careLine?: string;
  info: (code: string) => BiIndicatorInfo;
}) {
  const allowed = useHasRole(...SITUATION_ROLES);
  const run = useRunBiSituationAnalyst();
  const [question, setQuestion] = useState('');
  if (!allowed) return null;
  const record = run.data;
  const output = record ? usableOutput(record) : null;

  return (
    <Card as="section" aria-labelledby="bi-analysis">
      <CardHeader
        headingLevel={2}
        title={
          <span id="bi-analysis" className="inline-flex items-center gap-2">
            <Sparkles aria-hidden="true" className="h-5 w-5" />
            {t.biAnalysis.title}
          </span>
        }
        description={t.biAnalysis.description}
      />
      <p
        role="note"
        className="mb-4 flex items-start gap-2 rounded-md border border-warning bg-warning-subtle p-3 text-sm text-warning-fg-subtle"
      >
        <Bot aria-hidden="true" className="mt-0.5 h-4 w-4 shrink-0" />
        {t.biAnalysis.disclaimer}
      </p>
      <form
        className="mb-4 flex flex-col gap-3"
        onSubmit={(e) => {
          e.preventDefault();
          const q = question.trim();
          run.mutate({
            competence,
            trend_months: 6,
            ...(careLine ? { care_line: careLine } : {}),
            ...(q ? { question: q.slice(0, 500) } : {}),
          });
        }}
      >
        <Textarea
          label={t.biAnalysis.question}
          description={t.biAnalysis.questionHelp}
          maxLength={500}
          rows={2}
          value={question}
          onChange={(e) => setQuestion(e.target.value)}
        />
        <Button type="submit" disabled={run.isPending} className="self-start">
          {run.isPending ? t.biAnalysis.running : t.biAnalysis.run}
        </Button>
      </form>
      {run.error ? (
        <ErrorState
          description={describeError(run.error).message}
          correlationId={describeError(run.error).correlationId}
        />
      ) : null}
      {record && !output ? (
        <div
          role="alert"
          className="flex items-start gap-2 rounded-md border border-danger bg-danger-subtle p-3 text-sm text-danger-fg-subtle"
        >
          <AlertTriangle aria-hidden="true" className="mt-0.5 h-4 w-4 shrink-0" />
          <span>
            <strong className="block">{t.biAnalysis.invalidOutputTitle}</strong>
            {t.biAnalysis.invalidOutput}
          </span>
        </div>
      ) : null}
      {record && output ? <AnalysisResult output={output} run={record} info={info} /> : null}
    </Card>
  );
}
