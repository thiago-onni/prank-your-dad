'use client';

import Link from 'next/link';
import { useState } from 'react';
import { useTasks, type TaskStatus } from '@sus-nexus/api-client';
import {
  Badge,
  CursorPagination,
  EmptyState,
  Select,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeaderCell,
  TableRow,
} from '@sus-nexus/design-system';
import {
  formatDateTime,
  TaskSLAIndicator,
  taskPriorityLabels,
  taskStatusLabels,
} from '@sus-nexus/domain-components';
import { PageHeader } from '@/components/PageHeader';
import { QueryState } from '@/components/QueryState';
import { t } from '@/i18n';
import { TaskActions, useTaskTransition } from './useTaskTransition';

const STATUSES = Object.keys(taskStatusLabels) as TaskStatus[];

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
  const { onAction, dialog, isPending } = useTaskTransition();

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
                          <TaskActions task={task} onAction={onAction} disabled={isPending} />
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
      {dialog}
    </>
  );
}
