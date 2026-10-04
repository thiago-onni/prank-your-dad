'use client';

import Link from 'next/link';
import { useMemo, useState } from 'react';
import { useQueries } from '@tanstack/react-query';
import {
  coreKeys,
  unwrap,
  useCoreClient,
  useCurrentPurpose,
  useHealthUnits,
  useTasks,
  type CitizenDetail,
  type Purpose,
  type Task,
} from '@sus-nexus/api-client';
import { useSession } from '@sus-nexus/auth/client';
import { Badge, Button, Card, CardHeader, EmptyState, Select, cn } from '@sus-nexus/design-system';
import {
  CARE_TASK_TYPES,
  TaskSLAIndicator,
  careTaskType,
  careTaskTypeLabels,
  taskPriorityLabels,
  taskStatusLabels,
} from '@sus-nexus/domain-components';
import { PageHeader } from '@/components/PageHeader';
import { PurposeRequired } from '@/components/PurposeRequired';
import { QueryState } from '@/components/QueryState';
import { TaskActions, useTaskTransition } from '@/features/tarefas/useTaskTransition';
import { t } from '@/i18n';

const CLOSED: Task['status'][] = ['completed', 'cancelled'];

/**
 * Resolve cidadãos das tarefas visíveis (nome exibido + microárea) para o filtro do ACS.
 * Limitação conhecida: o contrato de tarefas não expõe microárea; o backend deveria oferecer
 * `GET /tasks?microarea=` para evitar N consultas.
 */
function useCitizenLookup(ids: string[]) {
  const client = useCoreClient();
  const purpose = useCurrentPurpose();
  const results = useQueries({
    queries: ids.map((id) => ({
      queryKey: coreKeys.citizen(id),
      enabled: Boolean(purpose),
      staleTime: 5 * 60_000,
      queryFn: async (): Promise<CitizenDetail> =>
        unwrap(
          await client.GET('/api/v1/citizens/{citizenId}', {
            params: { path: { citizenId: id }, header: { 'X-Purpose-Of-Use': purpose as Purpose } },
          }),
        ),
    })),
  });
  const map = new Map<string, CitizenDetail>();
  results.forEach((r, i) => {
    if (r.data) map.set(ids[i]!, r.data);
  });
  return { citizens: map, isLoading: results.some((r) => r.isLoading) };
}

function scopeLabel(id: string, unitName: (cnes: string) => string | undefined): string {
  if (id.startsWith('team_')) return `${t.care.team}: ${id.replace('team_', '')}`;
  if (id.startsWith('hu_')) return `${t.care.unit}: ${unitName(id.replace('hu_', '')) ?? id}`;
  return id;
}

export function CareWorkbench() {
  const { session } = useSession();
  const [scope, setScope] = useState('all');
  const [microarea, setMicroarea] = useState('all');
  const [showClosed, setShowClosed] = useState(false);
  const units = useHealthUnits({ limit: 100 });
  const unitName = (cnes: string) => units.data?.items.find((u) => u.cnes === cnes)?.name;
  const query = useTasks({ limit: 200 });
  const { onAction, dialog, isPending } = useTaskTransition();

  const careTasks = useMemo(
    () => (query.data?.items ?? []).filter((task) => CARE_TASK_TYPES.includes(careTaskType(task.task_type))),
    [query.data],
  );
  const scopes = useMemo(() => {
    const ids = new Set<string>();
    for (const task of careTasks) {
      if (task.assignee?.kind === 'team' || task.assignee?.kind === 'health_unit') ids.add(task.assignee.id);
    }
    return [...ids].sort();
  }, [careTasks]);

  const inScope = careTasks.filter((task) => {
    if (!showClosed && CLOSED.includes(task.status)) return false;
    if (scope === 'mine') return task.assignee?.kind === 'user' && task.assignee.id === (session?.user.id ?? 'me');
    if (scope !== 'all' && task.assignee?.id !== scope) return false;
    return true;
  });
  const citizenIds = useMemo(
    () => [...new Set(inScope.map((x) => x.citizen_id).filter((x): x is string => Boolean(x)))],
    [inScope],
  );
  const lookup = useCitizenLookup(citizenIds);
  const microareas = useMemo(
    () =>
      [...new Set([...lookup.citizens.values()].map((c) => c.microarea).filter((m): m is string => Boolean(m)))].sort(),
    [lookup.citizens],
  );
  const visible = inScope.filter((task) => {
    if (microarea === 'all') return true;
    const c = task.citizen_id ? lookup.citizens.get(task.citizen_id) : undefined;
    return c?.microarea === microarea;
  });
  const groups = CARE_TASK_TYPES.map((type) => ({
    type,
    tasks: visible
      .filter((task) => careTaskType(task.task_type) === type)
      .sort((a, b) => ((a.due_at ?? '') < (b.due_at ?? '') ? -1 : 1)),
  })).filter((g) => g.tasks.length > 0);
  const overdue = visible.filter((task) => task.overdue).length;

  return (
    <PurposeRequired>
      <PageHeader
        title={t.care.title}
        description={t.care.description}
        actions={
          <p role="status" className="text-sm">
            <Badge tone="primary">
              {visible.length} {t.care.openCount}
            </Badge>{' '}
            <Badge tone={overdue > 0 ? 'danger' : 'neutral'}>
              {overdue} {t.care.overdueCount}
            </Badge>
          </p>
        }
      />
      <fieldset className="mb-6 grid gap-3 sm:grid-cols-3">
        <legend className="sr-only">Filtros</legend>
        <Select
          label={t.care.scope}
          value={scope}
          onValueChange={setScope}
          options={[
            { value: 'all', label: t.care.scopeAll },
            { value: 'mine', label: t.care.assigneeMe },
            ...scopes.map((id) => ({ value: id, label: scopeLabel(id, unitName) })),
          ]}
        />
        <Select
          label={t.care.microarea}
          value={microarea}
          onValueChange={setMicroarea}
          description={lookup.isLoading ? t.app.loading : undefined}
          options={[
            { value: 'all', label: t.care.allMicroareas },
            ...microareas.map((m) => ({ value: m, label: `${t.care.microarea} ${m}` })),
          ]}
        />
        <label className="flex h-10 items-center gap-2 self-end text-sm">
          <input
            type="checkbox"
            checked={showClosed}
            onChange={(e) => setShowClosed(e.target.checked)}
            className="h-4 w-4 accent-[var(--sn-primary)]"
          />
          {t.care.showClosed}
        </label>
      </fieldset>

      <QueryState isLoading={query.isLoading} error={query.error} data={query.data} onRetry={() => void query.refetch()}>
        {() =>
          groups.length === 0 ? (
            <EmptyState title={t.care.noTasks} />
          ) : (
            <div className="flex flex-col gap-6">
              {groups.map((g) => {
                const meta = careTaskTypeLabels[g.type];
                const groupOverdue = g.tasks.filter((x) => x.overdue).length;
                return (
                  <Card key={g.type} as="section" aria-labelledby={`care-${g.type}`}>
                    <CardHeader
                      title={
                        <span id={`care-${g.type}`}>
                          {meta.label} <Badge tone="neutral">{g.tasks.length}</Badge>{' '}
                          {groupOverdue > 0 ? (
                            <Badge tone="danger">
                              {groupOverdue} {t.care.overdueCount}
                            </Badge>
                          ) : null}
                        </span>
                      }
                      description={meta.description}
                    />
                    <ul className="flex flex-col divide-y divide-border">
                      {g.tasks.map((task) => {
                        const citizen = task.citizen_id ? lookup.citizens.get(task.citizen_id) : undefined;
                        const pr = taskPriorityLabels[task.priority];
                        const st = taskStatusLabels[task.status];
                        return (
                          <li
                            key={task.id}
                            className={cn('flex flex-wrap items-center gap-3 py-3', task.overdue && 'bg-danger-subtle/40')}
                          >
                            <div className="min-w-0 flex-1 basis-64">
                              <p className="font-medium">{task.title ?? task.task_type}</p>
                              <p className="text-xs text-fg-muted">
                                {citizen ? (
                                  <>
                                    {citizen.display_name}
                                    {citizen.microarea ? ` · ${t.care.microarea} ${citizen.microarea}` : ''}
                                  </>
                                ) : task.citizen_id ? (
                                  <span className="font-mono">{task.citizen_id.slice(0, 12)}…</span>
                                ) : (
                                  '—'
                                )}
                                {task.assignee ? ` · ${scopeLabel(task.assignee.id, unitName)}` : ` · ${t.care.unassigned}`}
                              </p>
                            </div>
                            <Badge tone={pr.tone}>{pr.label}</Badge>
                            <Badge tone={st.tone}>{st.label}</Badge>
                            <TaskSLAIndicator task={task} />
                            {task.citizen_id ? (
                              <Button asChild size="sm" variant="tertiary">
                                <Link href={`/cidadaos/${task.citizen_id}`}>{t.care.openCitizen}</Link>
                              </Button>
                            ) : null}
                            <TaskActions task={task} onAction={onAction} disabled={isPending} />
                          </li>
                        );
                      })}
                    </ul>
                  </Card>
                );
              })}
            </div>
          )
        }
      </QueryState>
      {dialog}
    </PurposeRequired>
  );
}
