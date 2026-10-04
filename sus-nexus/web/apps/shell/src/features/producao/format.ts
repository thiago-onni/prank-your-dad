import type { ProductionDeadline, ProductionKind } from '@sus-nexus/api-client';
import type { BadgeTone } from '@sus-nexus/design-system';
import { maskCns, maskCpf } from '@sus-nexus/domain-components';
import { t } from '@/i18n';

/**
 * Garante que um identificador (CNS/CPF) nunca apareça em claro. O core já entrega
 * `*_masked`; se por erro chegar um valor com mais de 4 dígitos visíveis, mascaramos aqui.
 */
export function safeMaskedIdentifier(value: string | undefined | null): string {
  if (!value) return '—';
  const digits = value.replace(/\D/g, '');
  if (digits.length <= 4) return value;
  return digits.length === 11 ? maskCpf(digits) : maskCns(digits);
}

export type DeadlineLevel = 'overdue' | 'd1' | 'd5' | 'ok';

export interface DeadlineAlert {
  level: DeadlineLevel;
  label: string;
  description: string;
  tone: BadgeTone;
}

const DAY = 24 * 3600_000;

/** Dias inteiros até o prazo (negativo = vencido). Usa `days_remaining` do core quando houver. */
export function daysToDeadline(
  deadline: Pick<ProductionDeadline, 'deadline_at' | 'days_remaining'>,
  now: Date = new Date(),
): number {
  if (typeof deadline.days_remaining === 'number') return deadline.days_remaining;
  return Math.floor((new Date(deadline.deadline_at).getTime() - now.getTime()) / DAY);
}

/** Destaque D-5 / D-1 / vencido para um prazo de apresentação (PRO-007). */
export function deadlineAlert(
  deadline: Pick<ProductionDeadline, 'deadline_at' | 'days_remaining' | 'status'>,
  now?: Date,
): DeadlineAlert {
  const days = daysToDeadline(deadline, now);
  if (days < 0 || (deadline.status === 'closed' && days <= 0))
    return {
      level: 'overdue',
      label: t.production.alertOverdue,
      description: t.production.alertOverdueLong,
      tone: 'danger',
    };
  if (days <= 1)
    return {
      level: 'd1',
      label: t.production.alertD1,
      description: t.production.alertD1Long,
      tone: 'danger',
    };
  if (days <= 5)
    return {
      level: 'd5',
      label: t.production.alertD5,
      description: t.production.alertD5Long,
      tone: 'warning',
    };
  return { level: 'ok', label: t.production.alertOnTime, description: '', tone: 'success' };
}

export const PRODUCTION_KINDS: ProductionKind[] = ['bpa_c', 'bpa_i', 'apac', 'aih'];

/** Competência anterior ao mês corrente (a que está em apresentação). */
export function defaultCompetence(now: Date = new Date()): string {
  const d = new Date(now.getFullYear(), now.getMonth() - 1, 1);
  return `${d.getFullYear()}${String(d.getMonth() + 1).padStart(2, '0')}`;
}

export function formatBytes(n: number | undefined): string {
  if (n === undefined) return '—';
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024)
    return `${(n / 1024).toLocaleString('pt-BR', { maximumFractionDigits: 1 })} KB`;
  return `${(n / 1024 / 1024).toLocaleString('pt-BR', { maximumFractionDigits: 1 })} MB`;
}
