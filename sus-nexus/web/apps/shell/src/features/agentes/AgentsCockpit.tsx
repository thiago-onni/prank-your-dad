'use client';

import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useState } from 'react';
import {
  deriveAutonomy,
  useAgentRuns,
  useAgentTools,
  useAgents,
  type AgentRunRecord,
  type RunStatus,
} from '@sus-nexus/api-client';
import { ROLES } from '@sus-nexus/auth';
import { useHasRole } from '@sus-nexus/auth/client';
import {
  Badge,
  Button,
  Card,
  CardHeader,
  EmptyState,
  Select,
  Tabs,
  TabsContent,
  TabsList,
  TabsTrigger,
  VirtualizedTable,
  type VirtualColumn,
} from '@sus-nexus/design-system';
import {
  actionClassLabels,
  autonomyLabels,
  formatDateTime,
  runStatusLabels,
} from '@sus-nexus/domain-components';
import { PageHeader } from '@/components/PageHeader';
import { QueryState } from '@/components/QueryState';
import { t } from '@/i18n';
import { ApprovalsPanel } from './ApprovalsPanel';

const RUN_STATUSES = Object.keys(runStatusLabels) as RunStatus[];

function AgentCatalog() {
  const agents = useAgents();
  const tools = useAgentTools();
  return (
    <QueryState
      isLoading={agents.isLoading}
      error={agents.error}
      data={agents.data}
      onRetry={() => void agents.refetch()}
    >
      {(items) =>
        items.length === 0 ? (
          <EmptyState title={t.agents.noAgents} />
        ) : (
          <ul className="grid gap-4 lg:grid-cols-2">
            {items.map((a) => {
              const autonomy = autonomyLabels[deriveAutonomy(a, tools.data ?? [])];
              return (
                <li key={a.id}>
                  <Card as="article" className="h-full" aria-labelledby={`agent-${a.id}`}>
                    <CardHeader
                      title={
                        <code id={`agent-${a.id}`} className="font-mono">
                          {a.id}
                        </code>
                      }
                      description={a.description}
                      actions={<Badge tone={autonomy.tone}>{autonomy.label}</Badge>}
                    />
                    <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-sm">
                      <dt className="text-fg-muted">{t.agents.version}</dt>
                      <dd>v{a.version}</dd>
                      <dt className="text-fg-muted">{t.agents.promptVersion}</dt>
                      <dd>{a.prompt_version}</dd>
                      <dt className="text-fg-muted">{t.agents.ruleVersions}</dt>
                      <dd>
                        {Object.entries(a.rule_versions).length === 0
                          ? '—'
                          : Object.entries(a.rule_versions)
                              .map(([k, v]) => `${k}: ${v}`)
                              .join(' · ')}
                      </dd>
                      <dt className="text-fg-muted">{t.agents.tools}</dt>
                      <dd>
                        <ul className="flex flex-wrap gap-1">
                          {a.tools.map((name) => {
                            const spec = tools.data?.find((x) => x.name === name);
                            const meta = spec ? actionClassLabels[spec.action_class] : undefined;
                            return (
                              <li key={name}>
                                <Badge tone={meta?.tone ?? 'neutral'}>
                                  <code className="font-mono">{name}</code>
                                  {meta ? ` · ${meta.label}` : ''}
                                </Badge>
                              </li>
                            );
                          })}
                        </ul>
                      </dd>
                    </dl>
                  </Card>
                </li>
              );
            })}
          </ul>
        )
      }
    </QueryState>
  );
}

function RunsPanel() {
  const router = useRouter();
  const agents = useAgents();
  const [agentId, setAgentId] = useState('');
  const [status, setStatus] = useState<RunStatus | ''>('');
  const runs = useAgentRuns({ agent_id: agentId, status: status || undefined, limit: 100 });

  const columns: VirtualColumn<AgentRunRecord>[] = [
    {
      id: 'run',
      header: t.agents.run,
      width: 'minmax(220px, 1.5fr)',
      cell: (r) => (
        <span className="block truncate">
          <Link
            href={`/agentes/execucoes/${r.id}`}
            className="font-mono text-xs text-primary-fg-subtle hover:underline"
            onClick={(e) => e.stopPropagation()}
          >
            {r.id}
          </Link>
          <span className="block text-xs text-fg-muted">
            {r.trigger.kind}
            {r.trigger.ref ? ` · ${r.trigger.ref}` : ''}
          </span>
        </span>
      ),
    },
    {
      id: 'agent',
      header: t.agents.agent,
      width: 'minmax(180px, 1fr)',
      cell: (r) => (
        <span className="font-mono text-xs">
          {r.agent_id} v{r.agent_version}
        </span>
      ),
    },
    {
      id: 'status',
      header: t.agents.status,
      width: '150px',
      cell: (r) => {
        const meta = runStatusLabels[r.status];
        return <Badge tone={meta.tone}>{meta.label}</Badge>;
      },
    },
    {
      id: 'actions',
      header: t.agents.actions,
      width: '170px',
      cell: (r) => {
        const pending = r.actions.filter((a) => a.status === 'pending_approval').length;
        return pending > 0 ? (
          <Badge tone="warning">
            {pending} {t.agents.pendingActions}
          </Badge>
        ) : (
          <span className="text-fg-muted">{r.actions.length}</span>
        );
      },
    },
    {
      id: 'started',
      header: t.agents.startedAt,
      width: '150px',
      cell: (r) => formatDateTime(r.started_at),
    },
    {
      id: 'model',
      header: t.agents.model,
      width: '160px',
      cell: (r) => <span className="text-xs">{r.model}</span>,
    },
  ];

  return (
    <div className="flex flex-col gap-4">
      <fieldset className="grid gap-3 sm:grid-cols-2">
        <legend className="sr-only">Filtros</legend>
        <Select
          label={t.agents.filterAgent}
          value={agentId || 'all'}
          onValueChange={(v) => setAgentId(v === 'all' ? '' : v)}
          options={[
            { value: 'all', label: t.app.all },
            ...(agents.data ?? []).map((a) => ({ value: a.id, label: a.id })),
          ]}
        />
        <Select
          label={t.agents.filterStatus}
          value={status || 'all'}
          onValueChange={(v) => setStatus(v === 'all' ? '' : (v as RunStatus))}
          options={[
            { value: 'all', label: t.app.all },
            ...RUN_STATUSES.map((s) => ({ value: s, label: runStatusLabels[s].label })),
          ]}
        />
      </fieldset>
      <QueryState
        isLoading={runs.isLoading}
        error={runs.error}
        data={runs.data}
        onRetry={() => void runs.refetch()}
      >
        {(items) => (
          <VirtualizedTable
            rows={items}
            columns={columns}
            getRowId={(r) => r.id}
            aria-label={t.agents.runs}
            rowHeight={56}
            height={520}
            onRowActivate={(r) => router.push(`/agentes/execucoes/${r.id}`)}
            emptyMessage={t.agents.noRuns}
          />
        )}
      </QueryState>
    </div>
  );
}

export function AgentsCockpit({
  initialTab = 'agentes',
}: {
  initialTab?: 'agentes' | 'execucoes' | 'aprovacoes';
}) {
  const canKillSwitch = useHasRole(ROLES.DPO, ROLES.ADMIN);
  return (
    <>
      <PageHeader
        title={t.agents.title}
        description={t.agents.description}
        actions={
          canKillSwitch ? (
            <Button asChild variant="danger" size="sm">
              <Link href="/agentes/kill-switch">{t.agents.killSwitch}</Link>
            </Button>
          ) : null
        }
      />
      <Tabs defaultValue={initialTab}>
        <TabsList aria-label={t.agents.title}>
          <TabsTrigger value="agentes">{t.agents.catalog}</TabsTrigger>
          <TabsTrigger value="execucoes">{t.agents.runs}</TabsTrigger>
          <TabsTrigger value="aprovacoes">{t.agents.approvals}</TabsTrigger>
        </TabsList>
        <TabsContent value="agentes">
          <h2 className="sr-only">{t.agents.catalog}</h2>
          <AgentCatalog />
        </TabsContent>
        <TabsContent value="execucoes">
          <h2 className="sr-only">{t.agents.runs}</h2>
          <RunsPanel />
        </TabsContent>
        <TabsContent value="aprovacoes">
          <h2 className="sr-only">{t.agents.approvals}</h2>
          <ApprovalsPanel />
        </TabsContent>
      </Tabs>
    </>
  );
}
