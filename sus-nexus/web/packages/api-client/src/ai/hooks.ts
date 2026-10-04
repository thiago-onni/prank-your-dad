'use client';

import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useAiClient } from '../provider';
import type { AgentRunsQuery, ApprovalsQuery } from './client';
import { aiKeys } from './keys';
import type {
  AgentApproval,
  AgentDescriptor,
  AgentRunRecord,
  BiSituationInput,
  KillSwitchResponse,
  KillSwitchState,
  ToolDescriptor,
} from './types';

export function useAgents() {
  const ai = useAiClient();
  return useQuery({
    queryKey: aiKeys.agents(),
    queryFn: (): Promise<AgentDescriptor[]> => ai.listAgents(),
  });
}

export function useAgentTools() {
  const ai = useAiClient();
  return useQuery({
    queryKey: aiKeys.tools(),
    queryFn: (): Promise<ToolDescriptor[]> => ai.listTools(),
  });
}

export type AgentRunsParams = AgentRunsQuery;

/** Execuções recentes (AIA-010). O ai-service limita a 200 por chamada. */
export function useAgentRuns(params: AgentRunsParams = {}) {
  const ai = useAiClient();
  return useQuery({
    queryKey: aiKeys.runs(params),
    placeholderData: keepPreviousData,
    queryFn: (): Promise<AgentRunRecord[]> =>
      ai.listRuns({
        agent_id: params.agent_id || undefined,
        status: params.status,
        limit: params.limit,
      }),
  });
}

export function useAgentRun(runId: string | undefined) {
  const ai = useAiClient();
  return useQuery({
    queryKey: aiKeys.run(runId ?? ''),
    enabled: Boolean(runId),
    queryFn: (): Promise<AgentRunRecord> => ai.getRun(runId as string),
  });
}

export function useApprovals(params: ApprovalsQuery = {}) {
  const ai = useAiClient();
  return useQuery({
    queryKey: aiKeys.approvals(params),
    placeholderData: keepPreviousData,
    queryFn: (): Promise<AgentApproval[]> =>
      ai.listApprovals({ status: params.status ?? 'pending', agent_id: params.agent_id }),
  });
}

export interface ActionDecisionInput {
  runId: string;
  actionId: string;
  /** Obrigatória (10–1000 caracteres); registrada na trilha de auditoria (AIA-005). */
  justification: string;
}

function useActionDecision(kind: 'approve' | 'reject') {
  const ai = useAiClient();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (input: ActionDecisionInput): Promise<AgentRunRecord> =>
      kind === 'approve'
        ? ai.approveAction(input.runId, input.actionId, input.justification)
        : ai.rejectAction(input.runId, input.actionId, input.justification),
    onSuccess: async (run) => {
      qc.setQueryData(aiKeys.run(run.id), run);
      await Promise.all([
        qc.invalidateQueries({ queryKey: [...aiKeys.all, 'approvals'] }),
        qc.invalidateQueries({ queryKey: [...aiKeys.all, 'runs'] }),
      ]);
    },
  });
}

export function useApproveAction() {
  return useActionDecision('approve');
}

export function useRejectAction() {
  return useActionDecision('reject');
}

export function useKillSwitch(options?: { enabled?: boolean }) {
  const ai = useAiClient();
  return useQuery({
    queryKey: aiKeys.killSwitch(),
    enabled: options?.enabled ?? true,
    queryFn: (): Promise<KillSwitchResponse> => ai.getKillSwitch(),
  });
}

/** Define o estado administrativo do kill switch (AIA-009). Restrito a dpo/admin no backend. */
export function useSetKillSwitch() {
  const ai = useAiClient();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (state: KillSwitchState): Promise<KillSwitchResponse> => ai.setKillSwitch(state),
    onSuccess: (data) => {
      qc.setQueryData(aiKeys.killSwitch(), data);
    },
  });
}

/** Executa o agente de BI da Sala de Situação (análise assistida; sem ações). */
export function useRunBiSituationAnalyst() {
  const ai = useAiClient();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (input: BiSituationInput): Promise<AgentRunRecord> =>
      ai.runBiSituationAnalyst(input),
    onSuccess: async () => {
      await qc.invalidateQueries({ queryKey: [...aiKeys.all, 'runs'] });
    },
  });
}
