import type { Task } from '@sus-nexus/api-client';
import { cn } from '@sus-nexus/design-system';
import { AlertOctagon, Clock } from 'lucide-react';
import { formatDateTime, formatDuration } from '../lib/format';

export interface TaskSLAIndicatorProps {
  task: Pick<Task, 'due_at' | 'overdue' | 'status' | 'sla_policy_id'>;
  now?: Date;
  /** Limiar (ms) para destacar "vence em breve". Padrão: 4 h. */
  warnThresholdMs?: number;
  className?: string;
}

export type SlaState = 'none' | 'ok' | 'warning' | 'breached' | 'escalated' | 'closed';

export function computeSlaState(
  task: TaskSLAIndicatorProps['task'],
  now: Date,
  warnThresholdMs: number,
): { state: SlaState; remainingMs?: number } {
  if (task.status === 'completed' || task.status === 'cancelled') return { state: 'closed' };
  if (task.status === 'escalated') return { state: 'escalated' };
  if (!task.due_at) return { state: 'none' };
  const remainingMs = new Date(task.due_at).getTime() - now.getTime();
  if (task.overdue || remainingMs < 0) return { state: 'breached', remainingMs };
  if (remainingMs < warnThresholdMs) return { state: 'warning', remainingMs };
  return { state: 'ok', remainingMs };
}

/** Tempo restante/estourado do SLA da tarefa e sinal de escalonamento. */
export function TaskSLAIndicator({
  task,
  now = new Date(),
  warnThresholdMs = 4 * 3600 * 1000,
  className,
}: TaskSLAIndicatorProps) {
  const { state, remainingMs } = computeSlaState(task, now, warnThresholdMs);

  const styles: Record<SlaState, string> = {
    none: 'text-fg-muted',
    ok: 'text-success-fg-subtle',
    warning: 'text-warning-fg-subtle',
    breached: 'text-danger-fg-subtle font-semibold',
    escalated: 'text-danger-fg-subtle font-semibold',
    closed: 'text-fg-subtle',
  };

  let text: string;
  switch (state) {
    case 'none':
      text = 'Sem SLA';
      break;
    case 'closed':
      text = 'Encerrada';
      break;
    case 'escalated':
      text = 'Escalonada';
      break;
    case 'breached':
      text = `SLA estourado há ${formatDuration(remainingMs ?? 0)}`;
      break;
    case 'warning':
      text = `Vence em ${formatDuration(remainingMs ?? 0)}`;
      break;
    default:
      text = `Restam ${formatDuration(remainingMs ?? 0)}`;
  }

  const Icon = state === 'breached' || state === 'escalated' ? AlertOctagon : Clock;

  return (
    <span
      className={cn('inline-flex items-center gap-1 text-sm', styles[state], className)}
      data-sla-state={state}
      title={task.due_at ? `Prazo: ${formatDateTime(task.due_at)}` : undefined}
      role={state === 'breached' || state === 'escalated' ? 'status' : undefined}
    >
      <Icon aria-hidden="true" className="h-4 w-4" />
      <span>{text}</span>
      {task.due_at ? <span className="sr-only"> (prazo {formatDateTime(task.due_at)})</span> : null}
    </span>
  );
}
