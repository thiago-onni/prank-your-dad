'use client';

import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { unwrap } from '../client';
import { coreKeys } from '../keys';
import { useCoreClient } from '../provider';
import type { Assignee, Page, Task, TaskCreate, TaskStatus, TaskTransitionAction } from '../types';

export interface TasksParams {
  status?: TaskStatus;
  task_type?: string;
  assignee_kind?: Assignee['kind'];
  assignee_id?: string;
  citizen_id?: string;
  overdue?: boolean;
  cursor?: string;
  limit?: number;
}

export function useTasks(params: TasksParams = {}) {
  const client = useCoreClient();
  return useQuery({
    queryKey: coreKeys.tasks(params),
    placeholderData: keepPreviousData,
    queryFn: async (): Promise<Page<Task>> =>
      unwrap(
        await client.GET('/api/v1/tasks', {
          params: {
            query: {
              status: params.status,
              task_type: params.task_type || undefined,
              assignee_kind: params.assignee_kind,
              assignee_id: params.assignee_id || undefined,
              citizen_id: params.citizen_id || undefined,
              overdue: params.overdue,
              cursor: params.cursor,
              limit: params.limit ?? 50,
            },
          },
        }),
      ),
  });
}

export function useTask(taskId: string | undefined) {
  const client = useCoreClient();
  return useQuery({
    queryKey: coreKeys.task(taskId ?? ''),
    enabled: Boolean(taskId),
    queryFn: async (): Promise<Task> =>
      unwrap(
        await client.GET('/api/v1/tasks/{taskId}', {
          params: { path: { taskId: taskId as string } },
        }),
      ),
  });
}

export interface TaskTransitionInput {
  taskId: string;
  action: TaskTransitionAction;
  assignee?: Assignee;
  outcome?: string;
  reason?: string;
}

export function useTransitionTask() {
  const client = useCoreClient();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: TaskTransitionInput): Promise<Task> =>
      unwrap(
        await client.POST('/api/v1/tasks/{taskId}/transition', {
          params: { path: { taskId: input.taskId } },
          body: {
            action: input.action,
            assignee: input.assignee,
            outcome: input.outcome,
            reason: input.reason,
          },
        }),
      ),
    onSuccess: async (task) => {
      await qc.invalidateQueries({ queryKey: coreKeys.task(task.id) });
      await qc.invalidateQueries({ queryKey: [...coreKeys.all, 'tasks'] });
    },
  });
}

export function useCreateTask() {
  const client = useCoreClient();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: TaskCreate): Promise<Task> =>
      unwrap(await client.POST('/api/v1/tasks', { body: input })),
    onSuccess: () => qc.invalidateQueries({ queryKey: [...coreKeys.all, 'tasks'] }),
  });
}
