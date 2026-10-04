'use client';

import Link from 'next/link';
import { useId, useMemo, useState } from 'react';
import { ShieldOff } from 'lucide-react';
import {
  useAgentTools,
  useAgents,
  useKillSwitch,
  useSetKillSwitch,
  type KillSwitchState,
} from '@sus-nexus/api-client';
import { ROLES } from '@sus-nexus/auth';
import { useHasRole } from '@sus-nexus/auth/client';
import {
  Badge,
  Button,
  Card,
  CardHeader,
  Dialog,
  DialogContent,
  EmptyState,
  Input,
  useToast,
} from '@sus-nexus/design-system';
import { PageHeader } from '@/components/PageHeader';
import { QueryState } from '@/components/QueryState';
import { describeError } from '@/lib/problem';
import { t } from '@/i18n';

type ListKey = 'agents' | 'tools' | 'tenants';
const LIST_KEYS: ListKey[] = ['agents', 'tools', 'tenants'];
const LIST_LABEL: Record<ListKey, string> = {
  agents: t.agents.ks.agents,
  tools: t.agents.ks.tools,
  tenants: t.agents.ks.tenants,
};

function sameState(a: KillSwitchState, b: KillSwitchState): boolean {
  const eq = (x: string[], y: string[]) =>
    x.length === y.length && [...x].sort().every((v, i) => v === [...y].sort()[i]);
  return a.global === b.global && LIST_KEYS.every((k) => eq(a[k], b[k]));
}

interface Change {
  label: string;
  before: string;
  after: string;
}

function diff(before: KillSwitchState, after: KillSwitchState): Change[] {
  const changes: Change[] = [];
  if (before.global !== after.global) {
    changes.push({
      label: t.agents.ks.global,
      before: before.global ? t.agents.ks.active : t.agents.ks.inactive,
      after: after.global ? t.agents.ks.active : t.agents.ks.inactive,
    });
  }
  for (const k of LIST_KEYS) {
    const b = [...before[k]].sort().join(', ') || t.agents.ks.noItems;
    const a = [...after[k]].sort().join(', ') || t.agents.ks.noItems;
    if (a !== b) changes.push({ label: LIST_LABEL[k], before: b, after: a });
  }
  return changes;
}

function StateSummary({ state, id }: { state: KillSwitchState; id: string }) {
  return (
    <dl className="grid gap-x-6 gap-y-2 text-sm sm:grid-cols-2" aria-labelledby={id}>
      <div>
        <dt className="text-fg-muted">{t.agents.ks.global}</dt>
        <dd>
          <Badge tone={state.global ? 'danger' : 'success'}>
            {state.global ? t.agents.ks.active : t.agents.ks.inactive}
          </Badge>
        </dd>
      </div>
      {LIST_KEYS.map((k) => (
        <div key={k}>
          <dt className="text-fg-muted">{LIST_LABEL[k]}</dt>
          <dd className="font-mono text-xs">
            {state[k].length === 0 ? (
              <span className="font-sans text-fg-muted">{t.agents.ks.noItems}</span>
            ) : (
              state[k].join(', ')
            )}
          </dd>
        </div>
      ))}
    </dl>
  );
}

function ListEditor({
  listKey,
  values,
  suggestions,
  onChange,
  disabled,
}: {
  listKey: ListKey;
  values: string[];
  suggestions: string[];
  onChange: (next: string[]) => void;
  disabled?: boolean;
}) {
  const [draft, setDraft] = useState('');
  const listId = useId();
  const label = LIST_LABEL[listKey];
  const add = () => {
    const v = draft.trim();
    if (!v || values.includes(v)) return;
    onChange([...values, v]);
    setDraft('');
  };
  return (
    <fieldset className="flex flex-col gap-2" disabled={disabled}>
      <legend className="text-sm font-semibold">{label}</legend>
      <div className="flex items-end gap-2">
        <Input
          label={`${t.agents.ks.addValue}: ${label.toLowerCase()}`}
          hideLabel
          placeholder={t.agents.ks.valuePlaceholder}
          value={draft}
          list={listId}
          onChange={(e) => setDraft(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') {
              e.preventDefault();
              add();
            }
          }}
          containerClassName="flex-1"
          autoComplete="off"
        />
        <datalist id={listId}>
          {suggestions
            .filter((s) => !values.includes(s))
            .map((s) => (
              <option key={s} value={s} />
            ))}
        </datalist>
        <Button variant="secondary" onClick={add} disabled={!draft.trim()}>
          {t.agents.ks.addValue}
        </Button>
      </div>
      {values.length === 0 ? (
        <p className="text-sm text-fg-muted">{t.agents.ks.noItems}</p>
      ) : (
        <ul className="flex flex-wrap gap-2" aria-label={label}>
          {values.map((v) => (
            <li
              key={v}
              className="flex items-center gap-1 rounded-full border border-border bg-bg-muted py-0.5 pl-3 pr-1 text-xs"
            >
              <code className="font-mono">{v}</code>
              <Button
                size="sm"
                variant="ghost"
                aria-label={`${t.agents.ks.remove} ${v}`}
                onClick={() => onChange(values.filter((x) => x !== v))}
              >
                ×
              </Button>
            </li>
          ))}
        </ul>
      )}
    </fieldset>
  );
}

function Editor({
  current,
  onApplied,
}: {
  current: KillSwitchState;
  onApplied: (state: KillSwitchState) => void;
}) {
  const [draft, setDraft] = useState<KillSwitchState>(current);
  const [step, setStep] = useState<0 | 1 | 2>(0);
  const [word, setWord] = useState('');
  const [wordError, setWordError] = useState<string | undefined>();
  const agents = useAgents();
  const tools = useAgentTools();
  const mutation = useSetKillSwitch();
  const { toast } = useToast();
  const globalId = useId();
  const globalHelpId = useId();

  const changes = useMemo(() => diff(current, draft), [current, draft]);
  const dirty = !sameState(current, draft);

  const closeDialog = () => {
    setStep(0);
    setWord('');
    setWordError(undefined);
  };

  const apply = () => {
    if (word.trim() !== t.agents.ks.confirmWord) {
      setWordError(t.agents.ks.confirmMismatch);
      return;
    }
    mutation.mutate(
      {
        ...draft,
        agents: [...draft.agents].sort(),
        tools: [...draft.tools].sort(),
        tenants: [...draft.tenants].sort(),
      },
      {
        onSuccess: (data) => {
          toast({ title: t.agents.ks.applied, tone: 'success' });
          setDraft(data.admin);
          onApplied(data.admin);
          closeDialog();
        },
        onError: (err) => {
          const { message, correlationId } = describeError(err);
          toast({
            title: t.agents.ks.failed,
            description: `${message}${correlationId ? ` (${correlationId})` : ''}`,
            tone: 'danger',
          });
        },
      },
    );
  };

  return (
    <Card as="section" aria-labelledby="ks-admin">
      <CardHeader
        title={<span id="ks-admin">{t.agents.ks.admin}</span>}
        actions={
          dirty ? (
            <Badge tone="warning" role="status">
              {t.agents.ks.unsaved}
            </Badge>
          ) : null
        }
      />
      <div className="flex flex-col gap-5">
        <div className="flex items-start gap-3 rounded-md border border-danger bg-danger-subtle p-3">
          <input
            id={globalId}
            type="checkbox"
            className="mt-1 h-5 w-5 accent-[var(--sn-danger)]"
            checked={draft.global}
            aria-describedby={globalHelpId}
            onChange={(e) => setDraft((d) => ({ ...d, global: e.target.checked }))}
          />
          <label htmlFor={globalId} className="text-sm">
            <span className="block font-semibold">{t.agents.ks.global}</span>
            <span id={globalHelpId} className="text-fg-muted">
              {t.agents.ks.globalHelp}
            </span>
          </label>
        </div>
        <ListEditor
          listKey="agents"
          values={draft.agents}
          suggestions={(agents.data ?? []).map((a) => a.id)}
          onChange={(next) => setDraft((d) => ({ ...d, agents: next }))}
        />
        <ListEditor
          listKey="tools"
          values={draft.tools}
          suggestions={(tools.data ?? []).map((x) => x.name)}
          onChange={(next) => setDraft((d) => ({ ...d, tools: next }))}
        />
        <ListEditor
          listKey="tenants"
          values={draft.tenants}
          suggestions={[]}
          onChange={(next) => setDraft((d) => ({ ...d, tenants: next }))}
        />
        <div className="flex flex-wrap justify-end gap-2">
          <Button variant="secondary" onClick={() => setDraft(current)} disabled={!dirty}>
            {t.app.cancel}
          </Button>
          <Button variant="danger" onClick={() => setStep(1)} disabled={!dirty}>
            {t.agents.ks.review}
          </Button>
        </div>
      </div>

      <Dialog
        open={step > 0}
        onOpenChange={(open) => {
          if (!open) closeDialog();
        }}
      >
        <DialogContent
          title={t.agents.ks.confirmTitle}
          description={step === 1 ? t.agents.ks.step1 : t.agents.ks.step2}
          size="md"
          footer={
            step === 1 ? (
              <>
                <Button variant="secondary" onClick={closeDialog}>
                  {t.app.cancel}
                </Button>
                <Button variant="danger" onClick={() => setStep(2)}>
                  {t.app.confirm}
                </Button>
              </>
            ) : (
              <>
                <Button variant="secondary" onClick={() => setStep(1)}>
                  {t.app.back}
                </Button>
                <Button variant="danger" onClick={apply} loading={mutation.isPending}>
                  {t.agents.ks.save}
                </Button>
              </>
            )
          }
        >
          <ul className="mb-4 flex flex-col gap-2 text-sm" aria-label={t.agents.ks.review}>
            {changes.map((c) => (
              <li key={c.label} className="rounded-md border border-border p-2">
                <span className="block font-semibold">{c.label}</span>
                <span className="text-fg-muted">{c.before}</span>
                <span aria-hidden="true"> → </span>
                <span className="sr-only">, passa a </span>
                <span className="font-medium">{c.after}</span>
              </li>
            ))}
          </ul>
          {step === 2 ? (
            <Input
              label={t.agents.ks.confirmLabel}
              description={t.agents.ks.confirmDescription}
              value={word}
              onChange={(e) => {
                setWord(e.target.value);
                if (wordError) setWordError(undefined);
              }}
              error={wordError}
              autoComplete="off"
              required
            />
          ) : null}
        </DialogContent>
      </Dialog>
    </Card>
  );
}

/** Kill switch dos agentes (AIA-009): dupla confirmação, restrito a DPO/admin. */
export function KillSwitchPage() {
  const allowed = useHasRole(ROLES.DPO, ROLES.ADMIN);
  const query = useKillSwitch({ enabled: allowed });
  // Chave para remontar o editor após aplicar (o rascunho passa a refletir o estado salvo).
  const [editorKey, setEditorKey] = useState(0);

  return (
    <>
      <PageHeader
        title={t.agents.ks.title}
        description={
          <>
            <Link href="/agentes" className="text-primary-fg-subtle hover:underline">
              {t.agents.title}
            </Link>{' '}
            · {t.agents.ks.description}
          </>
        }
      />
      {!allowed ? (
        <EmptyState
          icon={<ShieldOff className="h-10 w-10" />}
          title={t.agents.ks.forbidden}
          description={t.agents.notApprover}
        />
      ) : (
        <QueryState
          isLoading={query.isLoading}
          error={query.error}
          data={query.data}
          onRetry={() => void query.refetch()}
        >
          {(data) => (
            <div className="grid gap-6 lg:grid-cols-2">
              <h2 className="text-xl font-semibold lg:col-span-2">{t.agents.ks.sectionTitle}</h2>
              <Card as="section" aria-labelledby="ks-effective">
                <CardHeader title={<span id="ks-effective">{t.agents.ks.effective}</span>} />
                <StateSummary state={data.effective} id="ks-effective" />
              </Card>
              <Editor
                key={editorKey}
                current={data.admin}
                onApplied={() => setEditorKey((k) => k + 1)}
              />
            </div>
          )}
        </QueryState>
      )}
    </>
  );
}
