'use client';

import { useState, type ReactNode } from 'react';
import {
  useTransitionTask,
  type Task,
  type TaskStatus,
  type TaskTransitionAction,
} from '@sus-nexus/api-client';
import { Button, Dialog, DialogContent, Textarea, useToast } from '@sus-nexus/design-system';
import { describeError } from '@/lib/problem';
import { t } from '@/i18n';

export const ALLOWED_TRANSITIONS: Record<TaskStatus, TaskTransitionAction[]> = {
  open: ['assign', 'start', 'cancel', 'escalate'],
  assigned: ['start', 'complete', 'cancel', 'escalate'],
  in_progress: ['complete', 'cancel', 'escalate'],
  escalated: ['assign', 'start', 'complete', 'cancel'],
  completed: [],
  cancelled: [],
};

const NEEDS_REASON: TaskTransitionAction[] = ['cancel', 'escalate'];

export function transitionVariant(action: TaskTransitionAction) {
  return action === 'cancel' ? 'ghost' : action === 'escalate' ? 'danger' : 'secondary';
}

/**
 * Transições de tarefa compartilhadas entre `/tarefas` e o Workbench de Cuidado:
 * assumir/iniciar executam direto; concluir pede desfecho; cancelar/escalonar exigem motivo.
 */
export function useTaskTransition() {
  const transition = useTransitionTask();
  const { toast } = useToast();
  const [pending, setPending] = useState<{ task: Task; action: TaskTransitionAction } | null>(null);
  const [reason, setReason] = useState('');
  const [outcome, setOutcome] = useState('');
  const [reasonError, setReasonError] = useState<string | undefined>();

  const close = () => {
    setPending(null);
    setReason('');
    setOutcome('');
    setReasonError(undefined);
  };

  const run = (task: Task, action: TaskTransitionAction, extra?: { reason?: string; outcome?: string }) => {
    transition.mutate(
      {
        taskId: task.id,
        action,
        reason: extra?.reason,
        outcome: extra?.outcome,
        assignee: action === 'assign' ? { kind: 'user', id: 'me' } : undefined,
      },
      {
        onSuccess: () => {
          toast({ title: t.tasks.transition.success, tone: 'success' });
          close();
        },
        onError: (error) => {
          const { message, correlationId } = describeError(error);
          toast({
            title: t.tasks.transition.failed,
            description: `${message}${correlationId ? ` (${correlationId})` : ''}`,
            tone: 'danger',
          });
        },
      },
    );
  };

  const onAction = (task: Task, action: TaskTransitionAction) => {
    if (NEEDS_REASON.includes(action) || action === 'complete') {
      setPending({ task, action });
      return;
    }
    run(task, action);
  };

  const confirmPending = () => {
    if (!pending) return;
    if (NEEDS_REASON.includes(pending.action) && reason.trim().length < 5) {
      setReasonError(`${t.tasks.transition.reasonLabel}: ${t.app.required.toLowerCase()}`);
      return;
    }
    run(pending.task, pending.action, {
      reason: reason.trim() || undefined,
      outcome: outcome.trim() || undefined,
    });
  };

  const dialog: ReactNode = (
    <Dialog
      open={pending !== null}
      onOpenChange={(open) => {
        if (!open) close();
      }}
    >
      <DialogContent
        title={pending ? t.tasks.transition[pending.action] : ''}
        description={pending?.task.title}
        size="sm"
        footer={
          <>
            <Button variant="secondary" onClick={close}>
              {t.app.cancel}
            </Button>
            <Button variant="primary" onClick={confirmPending} loading={transition.isPending}>
              {t.app.confirm}
            </Button>
          </>
        }
      >
        {pending?.action === 'complete' ? (
          <Textarea
            label={t.tasks.transition.outcomeLabel}
            value={outcome}
            onChange={(e) => setOutcome(e.target.value)}
            maxLength={500}
          />
        ) : (
          <Textarea
            label={t.tasks.transition.reasonLabel}
            required
            value={reason}
            onChange={(e) => {
              setReason(e.target.value);
              if (reasonError) setReasonError(undefined);
            }}
            error={reasonError}
            maxLength={500}
          />
        )}
      </DialogContent>
    </Dialog>
  );

  return { onAction, dialog, isPending: transition.isPending };
}

/** Grupo de botões com as transições permitidas para a situação atual da tarefa. */
export function TaskActions({
  task,
  onAction,
  disabled,
}: {
  task: Task;
  onAction: (task: Task, action: TaskTransitionAction) => void;
  disabled?: boolean;
}) {
  const allowed = ALLOWED_TRANSITIONS[task.status];
  if (allowed.length === 0) return <span className="text-xs text-fg-muted">—</span>;
  return (
    <div className="flex flex-wrap gap-1" role="group" aria-label={`${t.app.actions}: ${task.title ?? task.task_type}`}>
      {allowed.map((action) => (
        <Button
          key={action}
          size="sm"
          variant={transitionVariant(action)}
          onClick={() => onAction(task, action)}
          disabled={disabled}
        >
          {t.tasks.transition[action]}
        </Button>
      ))}
    </div>
  );
}
