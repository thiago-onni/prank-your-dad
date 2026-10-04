import type {
  IndicatorDirection,
  IndicatorUnit,
  SituationIndicator,
  SuppressibleValue,
} from '@sus-nexus/api-client';
import type { BadgeTone } from '@sus-nexus/design-system';
import { t } from '@/i18n';

/** Rótulo único para células suprimidas (0 < n < 5). Nunca exibir como zero. */
export const SUPPRESSED_LABEL = t.situation.suppressed;
export const NO_DATA_LABEL = t.situation.noData;

const pct = new Intl.NumberFormat('pt-BR', { style: 'percent', maximumFractionDigits: 1 });
const dec = new Intl.NumberFormat('pt-BR', { maximumFractionDigits: 1 });
const int = new Intl.NumberFormat('pt-BR', { maximumFractionDigits: 0 });

export function formatIndicatorNumber(value: number, unit: IndicatorUnit): string {
  if (unit === 'proporcao') return pct.format(value);
  if (unit === 'dias') return `${dec.format(value)} ${value === 1 ? 'dia' : 'dias'}`;
  return dec.format(value);
}

/** Valor de indicador com supressão explícita. */
export function formatIndicatorValue(
  value: number | null,
  unit: IndicatorUnit,
  suppressed: boolean,
): string {
  if (suppressed) return SUPPRESSED_LABEL;
  if (value === null) return NO_DATA_LABEL;
  return formatIndicatorNumber(value, unit);
}

/** Contagem/medida de `agg_*` (`null` + suppressed → "<5 (suprimido)"; `null` sem → "sem dado"). */
export function formatSuppressible(
  v: SuppressibleValue,
  kind: 'count' | 'rate' | 'days' = 'count',
): string {
  if (v.suppressed) return SUPPRESSED_LABEL;
  if (v.value === null) return NO_DATA_LABEL;
  if (kind === 'rate') return pct.format(v.value);
  if (kind === 'days') return `${dec.format(v.value)} ${v.value === 1 ? 'dia' : 'dias'}`;
  return int.format(v.value);
}

export function formatTarget(
  target: number | null,
  unit: IndicatorUnit,
  direction: IndicatorDirection,
): string {
  if (target === null) return t.situation.noTarget;
  return `${direction === 'maior_melhor' ? '≥' : '≤'} ${formatIndicatorNumber(target, unit)}`;
}

export type TrafficLevel = 'atingida' | 'atencao' | 'critica' | 'suprimido' | 'sem_dado';

export interface TrafficLight {
  level: TrafficLevel;
  label: string;
  tone: BadgeTone;
  description: string;
}

/** Tolerância relativa à meta para "atenção" (10%). */
export const ATTENTION_TOLERANCE = 0.1;

/**
 * Semáforo da meta. "Atingida" segue `is_on_target` do dbt (fonte de verdade); fora da meta,
 * até 10% de distância relativa = "atenção", além disso = "crítica". Suprimido/sem dado = neutro.
 */
export function trafficLight(
  i: Pick<SituationIndicator, 'value' | 'target' | 'direction' | 'is_suppressed' | 'is_on_target'>,
): TrafficLight {
  if (i.is_suppressed)
    return {
      level: 'suprimido',
      label: t.situation.lightSuppressed,
      tone: 'neutral',
      description: t.situation.lightSuppressedHelp,
    };
  if (i.value === null || i.target === null)
    return {
      level: 'sem_dado',
      label: t.situation.lightNoData,
      tone: 'neutral',
      description: t.situation.lightNoDataHelp,
    };
  const onTarget =
    i.is_on_target ?? (i.direction === 'maior_melhor' ? i.value >= i.target : i.value <= i.target);
  if (onTarget)
    return {
      level: 'atingida',
      label: t.situation.lightOk,
      tone: 'success',
      description: t.situation.lightOkHelp,
    };
  const limit =
    i.direction === 'maior_melhor'
      ? i.target * (1 - ATTENTION_TOLERANCE)
      : i.target * (1 + ATTENTION_TOLERANCE);
  const attention = i.direction === 'maior_melhor' ? i.value >= limit : i.value <= limit;
  return attention
    ? {
        level: 'atencao',
        label: t.situation.lightWarning,
        tone: 'warning',
        description: t.situation.lightWarningHelp,
      }
    : {
        level: 'critica',
        label: t.situation.lightCritical,
        tone: 'danger',
        description: t.situation.lightCriticalHelp,
      };
}

export function formatRatio(ratio: number | null): string {
  return ratio === null ? '—' : `${dec.format(ratio)}×`;
}

const CARE_LINE_LABELS: Record<string, string> = {
  diabetes: 'Diabetes',
  gestante: 'Gestante (pré-natal)',
  hipertensao: 'Hipertensão',
  rastreamento_cancer_mama: 'Rastreamento de câncer de mama',
  saude_mental: 'Saúde mental',
  pre_natal: 'Pré-natal',
};

export function careLineLabel(code: string): string {
  return CARE_LINE_LABELS[code] ?? code.replace(/_/g, ' ');
}

const PRIORITY_LABELS: Record<string, string> = {
  routine: 'Eletiva',
  elective: 'Eletiva',
  priority: 'Prioritária',
  urgent: 'Urgente',
  emergency: 'Emergência',
};
export const priorityLabel = (p: string) => PRIORITY_LABELS[p] ?? p;
export const specialtyLabel = (s: string) =>
  s.charAt(0).toUpperCase() + s.slice(1).replace(/_/g, ' ');
