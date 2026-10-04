'use client';

import Link from 'next/link';
import { useState, type FormEvent } from 'react';
import {
  useRegisterDischargeFollowup,
  useTask,
  type DischargeFollowupOutcome,
  type HospitalEpisode,
} from '@sus-nexus/api-client';
import {
  Badge,
  Button,
  Card,
  CardHeader,
  Select,
  Textarea,
  useToast,
} from '@sus-nexus/design-system';
import {
  TaskSLAIndicator,
  dischargeFollowupOutcomeLabels,
  followupStatusLabels,
  formatDateTime,
  taskStatusLabels,
} from '@sus-nexus/domain-components';
import { describeError } from '@/lib/problem';
import { t } from '@/i18n';

const OUTCOMES = Object.keys(dischargeFollowupOutcomeLabels) as DischargeFollowupOutcome[];

function LinkedTask({ taskId }: { taskId: string }) {
  const task = useTask(taskId);
  if (task.isLoading) return <p className="text-sm text-fg-muted">{t.app.loading}</p>;
  if (!task.data) return <p className="font-mono text-xs">{taskId}</p>;
  const st = taskStatusLabels[task.data.status];
  return (
    <div className="flex flex-col gap-1">
      <span className="font-medium">{task.data.title ?? task.data.task_type}</span>
      <span className="flex flex-wrap items-center gap-2">
        <Badge tone={st.tone}>{st.label}</Badge>
        <TaskSLAIndicator task={task.data} />
      </span>
      <Link href="/tarefas" className="text-sm text-primary-fg-subtle hover:underline">
        {t.hospital.openTasks}
      </Link>
    </div>
  );
}

/** Painel pós-alta: prazo, tarefa vinculada (SLA) e registro do desfecho do contato (CUI-006). */
export function FollowupPanel({ episode }: { episode: HospitalEpisode }) {
  const [outcome, setOutcome] = useState<DischargeFollowupOutcome | ''>('');
  const [note, setNote] = useState('');
  const [error, setError] = useState<string | undefined>();
  const mutation = useRegisterDischargeFollowup();
  const { toast } = useToast();
  const fu = episode.followup;
  const status = fu?.status ? followupStatusLabels[fu.status] : undefined;

  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (!outcome) {
      setError(`${t.hospital.outcome}: ${t.app.required.toLowerCase()}`);
      return;
    }
    mutation.mutate(
      { episodeId: episode.id, outcome, note: note.trim() || undefined },
      {
        onSuccess: () => {
          toast({ title: t.hospital.outcomeSaved, tone: 'success' });
          setOutcome('');
          setNote('');
        },
        onError: (err) => {
          const { message, correlationId } = describeError(err);
          toast({
            title: t.hospital.outcomeFailed,
            description: `${message}${correlationId ? ` (${correlationId})` : ''}`,
            tone: 'danger',
          });
        },
      },
    );
  };

  return (
    <Card as="section" aria-labelledby="hos-followup">
      <CardHeader title={<span id="hos-followup">{t.hospital.followupPanel}</span>} />
      {!fu ? (
        <p className="text-sm text-fg-muted">{t.hospital.followupNone}</p>
      ) : (
        <div className="flex flex-col gap-4">
          <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-2 text-sm">
            <dt className="text-fg-muted">{t.hospital.followupStatus}</dt>
            <dd>{status ? <Badge tone={status.tone}>{status.label}</Badge> : '—'}</dd>
            <dt className="text-fg-muted">{t.hospital.followupDue}</dt>
            <dd>{formatDateTime(fu.due_at)}</dd>
            <dt className="text-fg-muted">{t.hospital.followupOutcome}</dt>
            <dd>
              {fu.outcome
                ? (dischargeFollowupOutcomeLabels[fu.outcome as DischargeFollowupOutcome] ??
                  fu.outcome)
                : '—'}
            </dd>
            <dt className="text-fg-muted">{t.hospital.contactedAt}</dt>
            <dd>{formatDateTime(fu.contacted_at)}</dd>
            <dt className="text-fg-muted">{t.hospital.linkedTask}</dt>
            <dd>{fu.task_id ? <LinkedTask taskId={fu.task_id} /> : '—'}</dd>
            {fu.care_plan_id ? (
              <>
                <dt className="text-fg-muted">{t.hospital.carePlan}</dt>
                <dd>
                  <Link
                    href={`/cidadaos/${episode.citizen_id}?aba=plano`}
                    className="text-primary-fg-subtle hover:underline"
                  >
                    {t.hospital.openCarePlan}
                  </Link>
                </dd>
              </>
            ) : null}
          </dl>
          {fu.status === 'closed' ? (
            <p className="text-sm text-fg-muted">{t.hospital.followupClosed}</p>
          ) : (
            <form
              onSubmit={submit}
              aria-labelledby="hos-followup-form"
              className="flex flex-col gap-3 border-t border-border pt-3"
              noValidate
            >
              <h4 id="hos-followup-form" className="font-semibold">
                {t.hospital.registerOutcome}
              </h4>
              <Select
                label={t.hospital.outcome}
                value={outcome || undefined}
                onValueChange={(v) => {
                  setOutcome(v as DischargeFollowupOutcome);
                  setError(undefined);
                }}
                required
                error={error}
                options={OUTCOMES.map((o) => ({
                  value: o,
                  label: dischargeFollowupOutcomeLabels[o],
                }))}
              />
              <Textarea
                label={t.hospital.note}
                description={t.hospital.noteHelp}
                value={note}
                onChange={(e) => setNote(e.target.value)}
                maxLength={500}
                rows={3}
              />
              <div className="flex justify-end">
                <Button type="submit" loading={mutation.isPending}>
                  {t.hospital.submitOutcome}
                </Button>
              </div>
            </form>
          )}
        </div>
      )}
    </Card>
  );
}
