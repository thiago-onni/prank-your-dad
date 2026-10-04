'use client';

import Link from 'next/link';
import { useState } from 'react';
import {
  useAgentRun,
  useApprovals,
  useApproveAction,
  useRejectAction,
  type AgentApproval,
} from '@sus-nexus/api-client';
import {
  Badge,
  Button,
  EmptyState,
  Skeleton,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeaderCell,
  TableRow,
  useToast,
} from '@sus-nexus/design-system';
import { HumanApprovalPanel, formatDateTime } from '@sus-nexus/domain-components';
import { QueryState } from '@/components/QueryState';
import { describeError } from '@/lib/problem';
import { t } from '@/i18n';
import { stringifyJson } from './runAdapter';

/** Painel de decisão para uma ação pendente (um por vez, para IDs únicos e foco previsível). */
export function ActionApproval({
  runId,
  actionId,
  onDone,
}: {
  runId: string;
  actionId: string;
  onDone?: () => void;
}) {
  const run = useAgentRun(runId);
  const approve = useApproveAction();
  const reject = useRejectAction();
  const { toast } = useToast();
  const action = run.data?.actions.find((a) => a.id === actionId);

  const decide = async (kind: 'approve' | 'reject', justification: string) => {
    const mutation = kind === 'approve' ? approve : reject;
    try {
      await mutation.mutateAsync({ runId, actionId, justification });
      toast({ title: kind === 'approve' ? t.agents.approved : t.agents.rejected, tone: 'success' });
      onDone?.();
    } catch (error) {
      const { message, correlationId } = describeError(error);
      toast({
        title: t.agents.decisionFailed,
        description: `${message}${correlationId ? ` (${correlationId})` : ''}`,
        tone: 'danger',
      });
    }
  };

  if (run.isLoading) return <Skeleton className="h-48 w-full" label={t.app.loading} />;
  if (!run.data || !action) return <EmptyState title={t.agents.noPendingForRun} />;
  const pending = action.status === 'pending_approval';

  return (
    <HumanApprovalPanel
      title={t.agents.approvalTitle}
      description={t.agents.approvalDescription}
      approveLabel={t.agents.approve}
      rejectLabel={t.agents.reject}
      minJustification={10}
      maxJustification={1000}
      onApprove={(j) => decide('approve', j)}
      onReject={(j) => decide('reject', j)}
      isSubmitting={approve.isPending || reject.isPending}
      disabled={!pending}
      disabledReason={pending ? undefined : `Ação já decidida (${action.status}).`}
    >
      <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-sm">
        <dt className="text-fg-muted">{t.agents.agent}</dt>
        <dd>
          <code className="font-mono">{run.data.agent_id}</code> v{run.data.agent_version} ·{' '}
          <Link
            href={`/agentes/execucoes/${run.data.id}`}
            className="text-primary-fg-subtle hover:underline"
          >
            {t.agents.openRun}
          </Link>
        </dd>
        <dt className="text-fg-muted">{t.agents.tool}</dt>
        <dd>
          <code className="font-mono font-semibold">{action.tool}</code>
        </dd>
        <dt className="text-fg-muted">{t.agents.args}</dt>
        <dd>
          <ul className="font-mono text-xs">
            {Object.entries(action.args).map(([k, v]) => (
              <li key={k}>
                {k} = {stringifyJson(v)}
              </li>
            ))}
          </ul>
        </dd>
        {action.reasons.length > 0 ? (
          <>
            <dt className="text-fg-muted">{t.agents.policyReasons}</dt>
            <dd>{action.reasons.join('; ')}</dd>
          </>
        ) : null}
      </dl>
    </HumanApprovalPanel>
  );
}

export function ApprovalsPanel({ agentId }: { agentId?: string }) {
  const query = useApprovals({ status: 'pending', agent_id: agentId });
  const [selected, setSelected] = useState<AgentApproval | null>(null);

  return (
    <div className="flex flex-col gap-4">
      <QueryState
        isLoading={query.isLoading}
        error={query.error}
        data={query.data}
        onRetry={() => void query.refetch()}
      >
        {(items) =>
          items.length === 0 ? (
            <EmptyState title={t.agents.noApprovals} />
          ) : (
            <Table aria-label={t.agents.approvals}>
              <TableHead>
                <TableRow>
                  <TableHeaderCell>{t.agents.tool}</TableHeaderCell>
                  <TableHeaderCell>{t.agents.agent}</TableHeaderCell>
                  <TableHeaderCell>{t.agents.requestedAt}</TableHeaderCell>
                  <TableHeaderCell>{t.agents.tenant}</TableHeaderCell>
                  <TableHeaderCell>{t.app.actions}</TableHeaderCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {items.map((a) => (
                  <TableRow
                    key={a.id}
                    className={selected?.id === a.id ? 'bg-primary-subtle' : undefined}
                  >
                    <TableHeaderCell scope="row">
                      <code className="font-mono">{a.tool}</code>
                      <span className="block font-mono text-xs font-normal text-fg-muted">
                        {a.action_id}
                      </span>
                    </TableHeaderCell>
                    <TableCell>
                      <code className="font-mono text-xs">{a.agent_id}</code>
                    </TableCell>
                    <TableCell>{formatDateTime(a.requested_at)}</TableCell>
                    <TableCell className="font-mono text-xs">{a.tenant}</TableCell>
                    <TableCell>
                      <Button
                        size="sm"
                        variant={selected?.id === a.id ? 'primary' : 'secondary'}
                        aria-pressed={selected?.id === a.id}
                        onClick={() => setSelected(selected?.id === a.id ? null : a)}
                      >
                        {selected?.id === a.id ? (
                          <Badge tone="primary">Em revisão</Badge>
                        ) : (
                          'Revisar'
                        )}
                      </Button>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )
        }
      </QueryState>
      {selected ? (
        <ActionApproval
          runId={selected.run_id}
          actionId={selected.action_id}
          onDone={() => setSelected(null)}
        />
      ) : null}
    </div>
  );
}
