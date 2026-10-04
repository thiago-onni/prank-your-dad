'use client';

import Link from 'next/link';
import { useState } from 'react';
import {
  useTasks,
  useTransitionTask,
  type Task,
  type TaskStatus,
  type TaskTransitionAction,
} from '@sus-nexus/api-client';
import {
  Badge,
  Button,
  CursorPagination,
  Dialog,
  DialogContent,
  EmptyState,
  Select,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeaderCell,
  TableRow,
  Textarea,
  useToast,
} from '@sus-nexus/design-system';
import {
  formatDateTime,
  TaskSLAIndicator,
  taskPriorityLabels,
  taskStatusLabels,
} from '@sus-nexus/domain-components';
import { PageHeader } from '@/components/PageHeader';
import { QueryState } from '@/components/QueryState';
import { describeError } from '@/lib/problem';
import { t } from '@/i18n';

const STATUSES = Object.keys(taskStatusLabels) as TaskStatus[];

const ALLOWED: Record<TaskStatus, TaskTransitionAction[]> = {
  open: ['assign', 'start', 'cancel', 'escalate'],
  assigned: ['start', 'complete', 'cancel', 'escalate'],
  in_progress: ['complete', 'cancel', 'escalate'],
  escalated: ['assign', 'start', 'complete', 'cancel'],
  completed: [],
  cancelled: [],
};

const NEEDS_REASON: TaskTransitionAction[] = ['cancel', 'escalate'];

export function TasksPage() {
  const [status, setStatus] = useState<TaskStatus | ''>('');
  const [overdue, setOverdue] = useState(false);
  const [cursors, setCursors] = useState<string[]>([]);
  const query = useTasks({
    status: status || undefined,
    overdue: overdue || undefined,
    cursor: cursors[cursors.length - 1],
    limit: 50,
  });
  const transition = useTransitionTask();
  const { toast } = useToast();
  const [pending, setPending] = useState<{ task: Task; action: TaskTransitionAction } | null>(null);
  const [reason, setReason] = useState('');
  const [outcome, setOutcome] = useState('');
  const [reasonError, setReasonError] = useState<string | undefined>();

  const run = (
    task: Task,
    action: TaskTransitionAction,
    extra?: { reason?: string; outcome?: string },
  ) => {
    transition.mutate(
      { taskId: task.id, action, reason: extra?.reason, outcome: extra?.outcome },
      {
        onSuccess: () => {
          toast({ title: t.tasks.transition.success, tone: 'success' });
          setPending(null);
          setReason('');
          setOutcome('');
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

  return (
    <>
      <PageHeader title={t.tasks.title} description={t.tasks.description} />
      <div className="mb-4 flex flex-wrap items-end gap-3">
        <Select
          label={t.tasks.status}
          value={status || 'all'}
          onValueChange={(v) => {
            setStatus(v === 'all' ? '' : (v as TaskStatus));
            setCursors([]);
          }}
          options={[
            { value: 'all', label: t.app.all },
            ...STATUSES.map((s) => ({ value: s, label: taskStatusLabels[s].label })),
          ]}
          className="w-56"
        />
        <label className="flex h-10 items-center gap-2 text-sm">
          <input
            type="checkbox"
            checked={overdue}
            onChange={(e) => {
              setOverdue(e.target.checked);
              setCursors([]);
            }}
            className="h-4 w-4 accent-[var(--sn-primary)]"
          />
          {t.tasks.onlyOverdue}
        </label>
      </div>

      <QueryState
        isLoading={query.isLoading}
        error={query.error}
        data={query.data}
        onRetry={() => void query.refetch()}
      >
        {(page) =>
          page.items.length === 0 ? (
            <EmptyState title={t.tasks.noTasks} />
          ) : (
            <div className="flex flex-col gap-3">
              <Table aria-label={t.tasks.title}>
                <TableHead>
                  <TableRow>
                    <TableHeaderCell>Tarefa</TableHeaderCell>
                    <TableHeaderCell>{t.tasks.priority}</TableHeaderCell>
                    <TableHeaderCell>{t.tasks.status}</TableHeaderCell>
                    <TableHeaderCell>{t.tasks.sla}</TableHeaderCell>
                    <TableHeaderCell>{t.tasks.assignee}</TableHeaderCell>
                    <TableHeaderCell>{t.tasks.citizen}</TableHeaderCell>
                    <TableHeaderCell>{t.app.actions}</TableHeaderCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {page.items.map((task) => {
                    const st = taskStatusLabels[task.status];
                    const pr = taskPriorityLabels[task.priority];
                    return (
                      <TableRow key={task.id}>
                        <TableHeaderCell scope="row" className="font-medium">
                          {task.title ?? task.task_type}
                          <span className="block text-xs font-normal text-fg-muted">
                            {task.task_type} · {t.tasks.createdAt} {formatDateTime(task.created_at)}
                            {task.origin ? ` · ${t.tasks.origin}: ${task.origin.kind}` : ''}
                          </span>
                        </TableHeaderCell>
                        <TableCell>
                          <Badge tone={pr.tone}>{pr.label}</Badge>
                        </TableCell>
                        <TableCell>
                          <Badge tone={st.tone}>{st.label}</Badge>
                        </TableCell>
                        <TableCell>
                          <TaskSLAIndicator task={task} />
                        </TableCell>
                        <TableCell className="text-xs">
                          {task.assignee ? (
                            <>
                              {task.assignee.kind}{' '}
                              <code className="font-mono">{task.assignee.id}</code>
                            </>
                          ) : (
                            '—'
                          )}
                        </TableCell>
                        <TableCell>
                          {task.citizen_id ? (
                            <Link
                              href={`/cidadaos/${task.citizen_id}`}
                              className="font-mono text-xs text-primary-fg-subtle hover:underline"
                            >
                              {task.citizen_id.slice(0, 12)}…
                            </Link>
                          ) : (
                            '—'
                          )}
                        </TableCell>
                        <TableCell>
                          <div
                            className="flex flex-wrap gap-1"
                            role="group"
                            aria-label={`${t.app.actions}: ${task.title ?? task.task_type}`}
                          >
                            {ALLOWED[task.status].map((action) => (
                              <Button
                                key={action}
                                size="sm"
                                variant={
                                  action === 'cancel'
                                    ? 'ghost'
                                    : action === 'escalate'
                                      ? 'danger'
                                      : 'secondary'
                                }
                                onClick={() => onAction(task, action)}
                                disabled={transition.isPending}
                              >
                                {t.tasks.transition[action]}
                              </Button>
                            ))}
                          </div>
                        </TableCell>
                      </TableRow>
                    );
                  })}
                </TableBody>
              </Table>
              <CursorPagination
                nextCursor={page.next_cursor}
                hasPrevious={cursors.length > 0}
                isLoading={query.isFetching}
                onNext={(c) => setCursors((p) => [...p, c])}
                onPrevious={() => setCursors((p) => p.slice(0, -1))}
              />
            </div>
          )
        }
      </QueryState>

      <Dialog
        open={pending !== null}
        onOpenChange={(open) => {
          if (!open) {
            setPending(null);
            setReasonError(undefined);
          }
        }}
      >
        <DialogContent
          title={pending ? t.tasks.transition[pending.action] : ''}
          description={pending?.task.title}
          size="sm"
          footer={
            <>
              <Button variant="secondary" onClick={() => setPending(null)}>
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
    </>
  );
}
