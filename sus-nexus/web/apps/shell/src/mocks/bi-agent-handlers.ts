import { HttpResponse, http } from 'msw';
import type { AgentRunRecord, BiSituationInput, BiSituationOutput } from '@sus-nexus/api-client';
import { SITUATION_INDICATOR_CODES } from '@sus-nexus/api-client';
import { iso, NOW, ulid } from './data';
import { aggIndicadoresMensais, INDICATOR_META, MAIN_TENANT } from './situacao-data';
import { readJson } from './http-utils';

/**
 * ai-service sintético: `POST /agents/bi_situation_analyst/run`. Reproduz a validação do contrato
 * (`BiSituationInput`, `additionalProperties: false`, sem tenant) e monta a saída a partir dos
 * mesmos agregados da Sala de Situação (MSW), como a regra determinística `bi_situation_rules_v1`.
 */

const COMPETENCE_RE = /^(19|20)\d{2}(0[1-9]|1[0-2])$/;
const CARE_LINE_RE = /^[a-z][a-z0-9_]{1,39}$/;
const ALLOWED_KEYS = ['competence', 'trend_months', 'indicators', 'care_line', 'question'];

function validationError(loc: string, msg: string) {
  return HttpResponse.json(
    { detail: [{ loc: ['body', loc], msg, type: 'value_error' }] },
    { status: 422 },
  );
}

type Row = (typeof aggIndicadoresMensais)[number];
const municipal = (code: string, competence: string): Row | undefined =>
  aggIndicadoresMensais.find(
    (r) =>
      r.tenant_id === MAIN_TENANT &&
      r.indicator_code === code &&
      r.competence === competence &&
      r.aggregation_level === 'municipio',
  );

export function buildBiOutput(input: BiSituationInput): BiSituationOutput {
  const codes = input.indicators ?? [...SITUATION_INDICATOR_CODES];
  const months = input.trend_months ?? 6;
  const offTarget: BiSituationOutput['off_target'] = [];
  const trends: BiSituationOutput['trends'] = [];
  const inequalities: BiSituationOutput['inequalities'] = [];
  const sources: BiSituationOutput['sources'] = [];
  const limitations: string[] = [];
  for (const code of codes) {
    const row = municipal(code, input.competence);
    const meta = INDICATOR_META[code];
    if (!row) continue;
    if (row.is_suppressed) {
      limitations.push(`${code}: célula suprimida (n < 5) na competência; não analisado.`);
      continue;
    }
    sources.push({
      indicator_code: code,
      competence: input.competence,
      scope: 'municipio',
      value: row.indicator_value as number,
      suppressed: false,
    });
    if (row.is_on_target !== false) continue;
    offTarget.push({
      indicator_code: code,
      value: row.indicator_value as number,
      target: meta.target,
      comment: `${meta.name} fora da meta.`,
    });
    const series = aggIndicadoresMensais
      .filter(
        (r) =>
          r.tenant_id === MAIN_TENANT &&
          r.indicator_code === code &&
          r.aggregation_level === 'municipio' &&
          String(r.competence) <= input.competence,
      )
      .sort((a, b) => String(a.competence).localeCompare(String(b.competence)))
      .slice(-months);
    const first = series[0];
    const last = series.at(-1);
    const fv = first?.indicator_value as number | null;
    const lv = last?.indicator_value as number | null;
    const delta = fv !== null && lv !== null ? lv - fv : null;
    const better =
      delta === null ? null : meta.direction === 'maior_melhor' ? delta > 0 : delta < 0;
    trends.push({
      indicator_code: code,
      classification:
        delta === null
          ? 'indeterminado'
          : Math.abs(delta) < Math.abs(meta.target) * 0.02
            ? 'estavel'
            : better
              ? 'melhora'
              : 'piora',
      first_competence: String(first?.competence ?? ''),
      last_competence: String(last?.competence ?? ''),
      first_value: fv,
      last_value: lv,
      comment: `Janela de ${series.length} competências.`,
    });
    const units = aggIndicadoresMensais.filter(
      (r) =>
        r.tenant_id === MAIN_TENANT &&
        r.indicator_code === code &&
        r.competence === input.competence &&
        r.aggregation_level === 'unidade' &&
        !r.is_suppressed &&
        r.indicator_value !== null,
    );
    if (units.length >= 2) {
      const sorted = [...units].sort(
        (a, b) => (a.indicator_value as number) - (b.indicator_value as number),
      );
      const lo = sorted[0]!;
      const hi = sorted.at(-1)!;
      inequalities.push({
        indicator_code: code,
        level: 'unidade',
        highest: {
          health_unit_cnes: String(hi.health_unit_cnes),
          value: hi.indicator_value as number,
        },
        lowest: {
          health_unit_cnes: String(lo.health_unit_cnes),
          value: lo.indicator_value as number,
        },
        ratio:
          (lo.indicator_value as number) > 0
            ? Math.round(((hi.indicator_value as number) / (lo.indicator_value as number)) * 100) /
              100
            : null,
        comment: 'Diferença entre a unidade de maior e a de menor valor publicado.',
      });
    }
  }
  const firstOff = offTarget[0]?.indicator_code;
  return {
    competence: input.competence,
    summary: `Competência ${input.competence.slice(4)}/${input.competence.slice(0, 4)}: ${offTarget.length} indicador(es) fora da meta entre ${sources.length} analisados (somente dados agregados).`,
    off_target: offTarget,
    trends,
    inequalities,
    hypotheses: firstOff
      ? [
          {
            kind: 'hipotese',
            statement:
              'Hipótese: a concentração de faltas em poucas unidades sugere barreiras de acesso (horário/transporte) nessas áreas.',
            related_indicators: [firstOff],
            how_to_verify: 'Comparar absenteísmo por turno e por microárea nas unidades extremas.',
          },
        ]
      : [],
    recommendations: firstOff
      ? [
          {
            kind: 'recomendacao_textual',
            action: 'Priorizar lembretes ativos de consulta nas unidades com maior absenteísmo.',
            rationale: 'Unidades extremas concentram a diferença em relação à meta.',
            related_indicators: [firstOff],
            responsible_area: 'Coordenação da APS',
          },
        ]
      : [],
    data_limitations: limitations,
    sources,
  };
}

export function biRun(input: BiSituationInput, output: BiSituationOutput | null): AgentRunRecord {
  const started = iso(NOW);
  return {
    id: `run_${ulid()}`,
    agent_id: 'bi_situation_analyst',
    agent_version: '1.0.0',
    prompt_version: 'bi_situation_v1',
    model: 'claude-sonnet-4-5',
    model_params: { temperature: 0 },
    rule_versions: { bi_situation_rules: 'bi_situation_rules_v1' },
    tenant: MAIN_TENANT,
    trigger: { kind: 'manual', ref: null },
    input_ref: { hash: 'sha256:mock', ref: null, kind: 'manual' },
    minimized_context: { competence: input.competence, aggregated_only: true },
    tools_called: [],
    output,
    validation_status: output ? 'valid' : 'invalid_output',
    validation_attempts: output ? 1 : 2,
    status: output ? 'completed' : 'invalid_output',
    actions: [],
    started_at: started,
    finished_at: started,
    cost_estimate: 0.004,
    tokens_in: 1800,
    tokens_out: 600,
    error: output ? null : 'Saída do modelo não passou no output_schema após 2 tentativas.',
  } as AgentRunRecord;
}

export const biAgentHandlers = [
  http.post('*/agents/bi_situation_analyst/run', async ({ request }) => {
    const body = await readJson<Record<string, unknown>>(request);
    if (!body || typeof body !== 'object') return validationError('body', 'JSON inválido');
    const extra = Object.keys(body).find((k) => !ALLOWED_KEYS.includes(k));
    if (extra) return validationError(extra, 'Extra inputs are not permitted');
    if (typeof body.competence !== 'string' || !COMPETENCE_RE.test(body.competence))
      return validationError('competence', 'Competência inválida');
    if (
      body.care_line != null &&
      (typeof body.care_line !== 'string' || !CARE_LINE_RE.test(body.care_line))
    )
      return validationError('care_line', 'Linha de cuidado inválida');
    const input = body as unknown as BiSituationInput;
    return HttpResponse.json(biRun(input, buildBiOutput(input)));
  }),
];
