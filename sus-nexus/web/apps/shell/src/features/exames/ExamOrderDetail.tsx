'use client';

import Link from 'next/link';
import { AlertOctagon, Check, Circle, ExternalLink } from 'lucide-react';
import {
  useCitizen,
  useCurrentPurpose,
  useExamOrder,
  useExamResultDocument,
  useHealthUnits,
  type ExamOrder,
  type ExamOrderStatus,
  type ExamResult,
} from '@sus-nexus/api-client';
import { ROLES } from '@sus-nexus/auth';
import { useHasRole } from '@sus-nexus/auth/client';
import { Badge, Button, Card, CardHeader, Skeleton, cn, useToast } from '@sus-nexus/design-system';
import {
  CitizenHeader,
  EXAM_CYCLE,
  examIssueLabels,
  examResultStatusLabels,
  examStatusLabels,
  formatDateTime,
  purposeLabels,
} from '@sus-nexus/domain-components';
import { PageHeader } from '@/components/PageHeader';
import { PurposeRequired } from '@/components/PurposeRequired';
import { QueryState } from '@/components/QueryState';
import { describeError } from '@/lib/problem';
import { t } from '@/i18n';

function CitizenBlock({ citizenId }: { citizenId: string }) {
  const citizen = useCitizen(citizenId);
  const units = useHealthUnits({ limit: 100 });
  if (citizen.isLoading) return <Skeleton className="h-32 w-full" label={t.app.loading} />;
  if (citizen.error || !citizen.data) {
    const { message } = describeError(citizen.error);
    return (
      <p role="alert" className="rounded-md border border-danger bg-danger-subtle p-3 text-sm">
        {t.citizen.notFound} {message}
      </p>
    );
  }
  return (
    <CitizenHeader
      citizen={citizen.data}
      healthUnitName={units.data?.items.find((u) => u.cnes === citizen.data.health_unit_cnes)?.name}
    />
  );
}

/** Stepper acessível do ciclo (lista ordenada com estado por etapa). */
function CycleStepper({ order }: { order: ExamOrder }) {
  const terminal = order.status === 'cancelled' || order.status === 'not_performed';
  const currentIdx = EXAM_CYCLE.indexOf(order.status);
  const dateOf = (s: ExamOrderStatus) =>
    order.status_history?.find((h) => h.status === s)?.occurred_at;
  return (
    <Card as="section" aria-labelledby="exam-cycle">
      <CardHeader title={<span id="exam-cycle">{t.exams.cycle}</span>} />
      <ol className="grid gap-2 sm:grid-cols-3 lg:grid-cols-6">
        {EXAM_CYCLE.map((s, i) => {
          const state = terminal
            ? dateOf(s)
              ? 'done'
              : 'pending'
            : i < currentIdx
              ? 'done'
              : i === currentIdx
                ? 'current'
                : 'pending';
          const label = examStatusLabels[s].label;
          const stateLabel =
            state === 'done'
              ? t.exams.doneStep
              : state === 'current'
                ? t.exams.currentStep
                : t.exams.pendingStep;
          return (
            <li
              key={s}
              aria-current={state === 'current' ? 'step' : undefined}
              className={cn(
                'rounded-md border p-2 text-sm',
                state === 'done' && 'border-success bg-success-subtle',
                state === 'current' && 'border-primary bg-primary-subtle font-semibold',
                state === 'pending' && 'border-border text-fg-muted',
              )}
            >
              <span className="flex items-center gap-1">
                {state === 'done' ? (
                  <Check aria-hidden="true" className="h-4 w-4" />
                ) : (
                  <Circle aria-hidden="true" className="h-3 w-3" />
                )}
                {label}
                <span className="sr-only"> ({stateLabel})</span>
              </span>
              <time className="block text-xs font-normal text-fg-muted" dateTime={dateOf(s)}>
                {formatDateTime(dateOf(s))}
              </time>
            </li>
          );
        })}
      </ol>
      {terminal ? (
        <p role="status" className="mt-3 text-sm font-medium text-danger-fg-subtle">
          {examStatusLabels[order.status].label}
          {order.status_history?.find((h) => h.status === order.status)?.reason
            ? `: ${order.status_history.find((h) => h.status === order.status)?.reason}`
            : ''}
        </p>
      ) : null}
    </Card>
  );
}

function CycleTimes({ order }: { order: ExamOrder }) {
  const ct = order.cycle_times ?? {};
  const rows: { label: string; value: number | undefined }[] = [
    { label: t.exams.requestToSchedule, value: ct.request_to_schedule },
    { label: t.exams.scheduleToPerform, value: ct.schedule_to_perform },
    { label: t.exams.performToReport, value: ct.perform_to_report },
    { label: t.exams.reportToFollowup, value: ct.report_to_followup },
  ];
  return (
    <Card as="section" aria-labelledby="exam-times">
      <CardHeader title={<span id="exam-times">{t.exams.cycleTimes}</span>} />
      <dl className="grid grid-cols-2 gap-3 text-sm sm:grid-cols-4">
        {rows.map((r) => (
          <div key={r.label}>
            <dt className="text-fg-muted">{r.label}</dt>
            <dd className="text-xl font-semibold">
              {r.value === undefined ? '—' : `${r.value} h`}
            </dd>
          </div>
        ))}
      </dl>
    </Card>
  );
}

function ResultItem({
  order,
  result,
  clinical,
}: {
  order: ExamOrder;
  result: ExamResult;
  clinical: boolean;
}) {
  const purpose = useCurrentPurpose();
  const document = useExamResultDocument();
  const { toast } = useToast();
  const meta = examResultStatusLabels[result.status];

  const open = () => {
    if (!purpose) return;
    document.mutate(
      { orderId: order.id, resultId: result.id, purpose },
      {
        onSuccess: (doc) => {
          window.open(doc.url, '_blank', 'noopener,noreferrer');
          toast({
            title: t.exams.documentOpened,
            description: `${t.exams.documentExpires} ${formatDateTime(doc.expires_at)}.`,
            tone: 'success',
          });
        },
        onError: (err) => {
          const { message, correlationId } = describeError(err);
          toast({
            title: t.exams.documentFailed,
            description: `${message}${correlationId ? ` (${correlationId})` : ''}`,
            tone: 'danger',
          });
        },
      },
    );
  };

  return (
    <li
      className={cn(
        'rounded-md border p-3 text-sm',
        result.critical ? 'border-danger bg-danger-subtle' : 'border-border',
      )}
      aria-label={`${t.exams.results}: ${meta.label}${result.critical ? ` — ${t.exams.critical}` : ''}`}
    >
      {result.critical ? (
        <p
          role="alert"
          className="mb-2 flex items-center gap-2 font-semibold text-danger-fg-subtle"
        >
          <AlertOctagon aria-hidden="true" className="h-5 w-5" />
          {t.exams.critical}
        </p>
      ) : null}
      <div className="flex flex-wrap items-center gap-2">
        <Badge tone={meta.tone}>{meta.label}</Badge>
        <span className="text-fg-muted">
          {t.exams.reportedAt} {formatDateTime(result.reported_at)}
        </span>
        <span className="text-fg-muted">
          · {t.exams.source}: {result.source_system}
          {result.performer_cnes ? ` · ${t.exams.performer}: CNES ${result.performer_cnes}` : ''}
        </span>
      </div>
      <dl className="mt-2 grid gap-x-6 gap-y-1 sm:grid-cols-3">
        <div>
          <dt className="text-fg-muted">{t.exams.observations}</dt>
          <dd>{result.observations_count ?? 0}</dd>
        </div>
        <div>
          <dt className="text-fg-muted">{t.exams.followupTask}</dt>
          <dd>
            {result.followup_task_id ? (
              <Link
                href="/tarefas"
                className="font-mono text-xs text-primary-fg-subtle hover:underline"
              >
                {result.followup_task_id}
              </Link>
            ) : (
              '—'
            )}
          </dd>
        </div>
        <div>
          <dt className="text-fg-muted">Laudo</dt>
          <dd>
            {!result.has_document ? (
              <span className="text-fg-muted">{t.exams.noDocument}</span>
            ) : clinical ? (
              <Button
                size="sm"
                variant="secondary"
                onClick={open}
                loading={document.isPending}
                disabled={!purpose}
                aria-describedby={`doc-purpose-${result.id}`}
              >
                <ExternalLink aria-hidden="true" className="h-4 w-4" />
                {t.exams.openDocument}
              </Button>
            ) : (
              <span className="text-fg-muted">—</span>
            )}
            {clinical && result.has_document ? (
              <span id={`doc-purpose-${result.id}`} className="block text-xs text-fg-muted">
                {t.exams.documentPurpose}: {purpose ? purposeLabels[purpose] : '—'}
              </span>
            ) : null}
          </dd>
        </div>
      </dl>
      {!clinical ? (
        <p className="mt-2 text-xs text-fg-muted">{t.exams.observationsCountOnly}</p>
      ) : null}
    </li>
  );
}

export function ExamOrderDetail({ orderId }: { orderId: string }) {
  const query = useExamOrder(orderId);
  const clinical = useHasRole(ROLES.MEDICO, ROLES.ENFERMAGEM);

  return (
    <PurposeRequired>
      <QueryState
        isLoading={query.isLoading}
        error={query.error}
        data={query.data}
        onRetry={() => void query.refetch()}
      >
        {(order) => {
          const status = examStatusLabels[order.status];
          const hasCritical =
            order.results?.some((r) => r.critical) || order.issues?.includes('critical');
          return (
            <div className="flex flex-col gap-6">
              <PageHeader
                title={order.exam_description ?? order.exam_code}
                description={
                  <>
                    {t.exams.detailTitle} · {order.code_system ?? 'SIGTAP'} {order.exam_code}
                    {order.category ? ` · ${t.exams.category}: ${order.category}` : ''}
                    {order.care_line ? ` · ${t.exams.careLine}: ${order.care_line}` : ''}
                  </>
                }
                actions={
                  <>
                    <Badge tone={status.tone}>{status.label}</Badge>
                    {(order.issues ?? []).map((i) => (
                      <Badge key={i} tone={examIssueLabels[i].tone}>
                        {examIssueLabels[i].label}
                      </Badge>
                    ))}
                  </>
                }
              />
              {hasCritical ? (
                <p
                  role="alert"
                  className="flex items-start gap-2 rounded-md border border-danger bg-danger-subtle p-3 text-sm font-medium text-danger-fg-subtle"
                >
                  <AlertOctagon aria-hidden="true" className="mt-0.5 h-5 w-5 shrink-0" />
                  {t.exams.criticalNotice}
                </p>
              ) : null}

              <CitizenBlock citizenId={order.citizen_id} />
              <CycleStepper order={order} />
              <CycleTimes order={order} />

              <Card as="section" aria-labelledby="exam-meta">
                <CardHeader title={<span id="exam-meta">{t.app.details}</span>} />
                <dl className="grid gap-x-6 gap-y-2 text-sm sm:grid-cols-2 lg:grid-cols-3">
                  <div>
                    <dt className="text-fg-muted">{t.exams.requestedAt}</dt>
                    <dd>
                      {formatDateTime(order.requested_at)} ·{' '}
                      {order.requesting_unit_name ?? order.requesting_cnes ?? '—'}
                    </dd>
                  </div>
                  <div>
                    <dt className="text-fg-muted">{t.exams.scheduledAt}</dt>
                    <dd>{formatDateTime(order.scheduled_at)}</dd>
                  </div>
                  <div>
                    <dt className="text-fg-muted">{t.exams.performedAt}</dt>
                    <dd>
                      {formatDateTime(order.performed_at)}
                      {order.performer_cnes ? ` · CNES ${order.performer_cnes}` : ''}
                    </dd>
                  </div>
                  <div>
                    <dt className="text-fg-muted">{t.exams.priority}</dt>
                    <dd>{order.priority ?? '—'}</dd>
                  </div>
                  <div>
                    <dt className="text-fg-muted">{t.exams.linkedRegulation}</dt>
                    <dd>
                      {order.regulation_request_id ? (
                        <Link
                          href={`/regulacao/${order.regulation_request_id}`}
                          className="font-mono text-xs text-primary-fg-subtle hover:underline"
                        >
                          {order.regulation_request_id}
                        </Link>
                      ) : (
                        '—'
                      )}
                    </dd>
                  </div>
                  <div>
                    <dt className="text-fg-muted">{t.exams.source}</dt>
                    <dd className="font-mono text-xs">
                      {order.source_system} · {order.source_record_id ?? '—'}
                    </dd>
                  </div>
                </dl>
              </Card>

              <Card as="section" aria-labelledby="exam-results">
                <CardHeader
                  title={<span id="exam-results">{t.exams.results}</span>}
                  description={
                    clinical
                      ? 'Metadados do laudo. O documento abre em nova aba por URL assinada e expira em minutos.'
                      : t.exams.observationsCountOnly
                  }
                />
                {(order.results ?? []).length === 0 ? (
                  <p className="text-sm text-fg-muted">{t.exams.noResults}</p>
                ) : (
                  <ul className="flex flex-col gap-2">
                    {(order.results ?? []).map((r) => (
                      <ResultItem key={r.id} order={order} result={r} clinical={clinical} />
                    ))}
                  </ul>
                )}
              </Card>
            </div>
          );
        }}
      </QueryState>
    </PurposeRequired>
  );
}
