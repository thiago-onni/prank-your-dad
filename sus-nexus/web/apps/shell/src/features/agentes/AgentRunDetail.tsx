'use client';

import Link from 'next/link';
import { useState } from 'react';
import { useAgentRun } from '@sus-nexus/api-client';
import { Badge, Button, Card, CardHeader } from '@sus-nexus/design-system';
import { AgentDecisionTrace, actionStatusLabels, runStatusLabels } from '@sus-nexus/domain-components';
import { PageHeader } from '@/components/PageHeader';
import { QueryState } from '@/components/QueryState';
import { t } from '@/i18n';
import { ActionApproval } from './ApprovalsPanel';
import { toAgentDecision } from './runAdapter';

const money = new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'USD', maximumFractionDigits: 4 });

export function AgentRunDetail({ runId }: { runId: string }) {
  const query = useAgentRun(runId);
  const [selectedAction, setSelectedAction] = useState<string | null>(null);

  return (
    <QueryState isLoading={query.isLoading} error={query.error} data={query.data} onRetry={() => void query.refetch()}>
      {(run) => {
        const status = runStatusLabels[run.status];
        const pending = run.actions.filter((a) => a.status === 'pending_approval');
        return (
          <div className="flex flex-col gap-6">
            <PageHeader
              title={t.agents.runDetail}
              description={
                <>
                  <Link href="/agentes" className="text-primary-fg-subtle hover:underline">
                    {t.agents.title}
                  </Link>{' '}
                  · <code className="font-mono text-sm">{run.id}</code>
                </>
              }
              actions={<Badge tone={status.tone}>{status.label}</Badge>}
            />

            <Card as="section" aria-labelledby="run-meta">
              <CardHeader title={<span id="run-meta">{t.app.details}</span>} />
              <dl className="grid gap-x-6 gap-y-2 text-sm sm:grid-cols-2 lg:grid-cols-4">
                <div>
                  <dt className="text-fg-muted">{t.agents.tokens}</dt>
                  <dd>
                    {run.tokens_in} / {run.tokens_out}
                  </dd>
                </div>
                <div>
                  <dt className="text-fg-muted">{t.agents.cost}</dt>
                  <dd>{money.format(run.cost_estimate)}</dd>
                </div>
                <div>
                  <dt className="text-fg-muted">Validação da saída</dt>
                  <dd>
                    {run.validation_status} ({run.validation_attempts} tentativa{run.validation_attempts === 1 ? '' : 's'})
                  </dd>
                </div>
                <div>
                  <dt className="text-fg-muted">Entrada (hash)</dt>
                  <dd className="truncate font-mono text-xs" title={run.input_ref.hash}>
                    {run.input_ref.hash.slice(0, 16)}…
                  </dd>
                </div>
              </dl>
            </Card>

            <AgentDecisionTrace decision={toAgentDecision(run)} />

            <Card as="section" aria-labelledby="run-approvals">
              <CardHeader
                title={<span id="run-approvals">{t.agents.approvals}</span>}
                description={pending.length === 0 ? t.agents.noPendingForRun : t.agents.approvalDescription}
              />
              {pending.length > 0 ? (
                <ul className="mb-4 flex flex-col gap-2">
                  {pending.map((a) => {
                    const meta = actionStatusLabels[a.status];
                    const active = selectedAction === a.id;
                    return (
                      <li key={a.id} className="flex flex-wrap items-center gap-2 rounded-md border border-border p-2 text-sm">
                        <code className="font-mono font-semibold">{a.tool}</code>
                        <Badge tone={meta.tone}>{meta.label}</Badge>
                        <span className="font-mono text-xs text-fg-muted">{a.id}</span>
                        <Button
                          size="sm"
                          variant={active ? 'primary' : 'secondary'}
                          aria-pressed={active}
                          className="ml-auto"
                          onClick={() => setSelectedAction(active ? null : a.id)}
                        >
                          Revisar
                        </Button>
                      </li>
                    );
                  })}
                </ul>
              ) : null}
              {selectedAction ? (
                <ActionApproval runId={run.id} actionId={selectedAction} onDone={() => setSelectedAction(null)} />
              ) : null}
            </Card>
          </div>
        );
      }}
    </QueryState>
  );
}
