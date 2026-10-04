'use client';

import Link from 'next/link';
import { useState } from 'react';
import { ShieldCheck } from 'lucide-react';
import {
  useCitizen,
  useHealthUnits,
  useProviderCapacity,
  useRegulationRequest,
  type RegulationRequest,
} from '@sus-nexus/api-client';
import {
  Badge,
  Button,
  Card,
  CardHeader,
  Skeleton,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeaderCell,
  TableRow,
} from '@sus-nexus/design-system';
import {
  CitizenHeader,
  TaskSLAIndicator,
  formatDateTime,
  regulationIssueKindLabels,
  regulationKindLabels,
  regulationPriorityLabels,
  regulationStatusLabels,
} from '@sus-nexus/domain-components';
import { PageHeader } from '@/components/PageHeader';
import { PurposeRequired } from '@/components/PurposeRequired';
import { QueryState } from '@/components/QueryState';
import { describeError } from '@/lib/problem';
import { t } from '@/i18n';
import { AddIssueDialog } from './AddIssueDialog';
import { slaTask } from './RegulationCockpit';

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
      healthUnitName={
        units.data?.items.find((u) => u.cnes === citizen.data.health_unit_cnes)?.name
      }
    />
  );
}

function StatusTimeline({ request }: { request: RegulationRequest }) {
  const history = [...(request.status_history ?? [])].sort((a, b) =>
    (a.occurred_at ?? '') < (b.occurred_at ?? '') ? 1 : -1,
  );
  return (
    <Card as="section" aria-labelledby="reg-timeline">
      <CardHeader title={<span id="reg-timeline">{t.regulation.timeline}</span>} />
      {history.length === 0 ? (
        <p className="text-sm text-fg-muted">{t.regulation.noTimeline}</p>
      ) : (
        <ol className="relative flex flex-col gap-3 border-l-2 border-border pl-4">
          {history.map((h, i) => {
            const meta = h.status ? regulationStatusLabels[h.status] : undefined;
            return (
              <li key={`${h.status}-${h.occurred_at}-${i}`} className="relative">
                <span
                  aria-hidden="true"
                  className={`absolute -left-[1.4rem] top-1.5 h-3 w-3 rounded-full ${i === 0 ? 'bg-primary' : 'bg-border-strong'}`}
                />
                <div className="flex flex-wrap items-center gap-2 text-sm">
                  {meta ? <Badge tone={meta.tone}>{meta.label}</Badge> : null}
                  <time dateTime={h.occurred_at}>{formatDateTime(h.occurred_at)}</time>
                  {h.actor ? <span className="text-fg-muted">· {h.actor}</span> : null}
                </div>
                {h.reason ? <p className="mt-1 text-sm text-fg-muted">{h.reason}</p> : null}
              </li>
            );
          })}
        </ol>
      )}
    </Card>
  );
}

function IssuesPanel({ request, onAdd }: { request: RegulationRequest; onAdd: () => void }) {
  const issues = [...(request.issues ?? [])].sort((a, b) =>
    a.status === b.status ? (a.created_at < b.created_at ? 1 : -1) : a.status === 'open' ? -1 : 1,
  );
  const openCount = issues.filter((i) => i.status === 'open').length;
  return (
    <Card as="section" aria-labelledby="reg-issues">
      <CardHeader
        title={
          <span id="reg-issues">
            {t.regulation.issues}{' '}
            <Badge tone={openCount > 0 ? 'warning' : 'neutral'}>
              {openCount} {t.regulation.openIssues.toLowerCase()}
            </Badge>
          </span>
        }
        actions={
          <Button size="sm" variant="secondary" onClick={onAdd}>
            {t.regulation.addIssue}
          </Button>
        }
      />
      {issues.length === 0 ? (
        <p className="text-sm text-fg-muted">{t.regulation.noIssues}</p>
      ) : (
        <ul className="flex flex-col gap-2">
          {issues.map((i) => (
            <li key={i.id} className="rounded-md border border-border p-3 text-sm">
              <div className="flex flex-wrap items-center gap-2">
                <span className="font-semibold">{regulationIssueKindLabels[i.kind]}</span>
                <Badge tone={i.status === 'open' ? 'warning' : 'success'}>
                  {i.status === 'open' ? 'Aberta' : 'Resolvida'}
                </Badge>
                <span className="text-fg-muted">
                  {formatDateTime(i.created_at)}
                  {i.origin?.kind ? ` · ${t.regulation.origin}: ${i.origin.kind}` : ''}
                  {i.origin?.id ? ` (${i.origin.id})` : ''}
                </span>
              </div>
              {i.description ? <p className="mt-1">{i.description}</p> : null}
              {i.resolved_at ? (
                <p className="mt-1 text-xs text-fg-muted">
                  {t.regulation.resolvedAt} {formatDateTime(i.resolved_at)}
                </p>
              ) : null}
            </li>
          ))}
        </ul>
      )}
    </Card>
  );
}

function CapacityPanel({ request }: { request: RegulationRequest }) {
  const capacity = useProviderCapacity(
    { provider_cnes: request.provider_cnes, service_code: request.requested_service_code },
    { enabled: Boolean(request.provider_cnes) },
  );
  return (
    <Card as="section" aria-labelledby="reg-capacity">
      <CardHeader
        title={<span id="reg-capacity">{t.regulation.providerCapacity}</span>}
        description={request.provider_name ?? 'Prestador não definido pelo sistema oficial.'}
      />
      {!request.provider_cnes ? null : (
        <QueryState
          isLoading={capacity.isLoading}
          error={capacity.error}
          data={capacity.data}
          onRetry={() => void capacity.refetch()}
          skeletonRows={2}
        >
          {(page) =>
            page.items.length === 0 ? (
              <p className="text-sm text-fg-muted">{t.regulation.noProviderCapacity}</p>
            ) : (
              <Table aria-label={t.regulation.providerCapacity}>
                <TableHead>
                  <TableRow>
                    <TableHeaderCell>{t.regulation.competence}</TableHeaderCell>
                    <TableHeaderCell>{t.regulation.offered}</TableHeaderCell>
                    <TableHeaderCell>{t.regulation.used}</TableHeaderCell>
                    <TableHeaderCell>{t.regulation.available}</TableHeaderCell>
                    <TableHeaderCell>{t.regulation.updatedAt}</TableHeaderCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {page.items.map((c) => (
                    <TableRow key={`${c.provider_cnes}-${c.service_code}-${c.competence}`}>
                      <TableHeaderCell scope="row">{c.competence}</TableHeaderCell>
                      <TableCell>{c.offered}</TableCell>
                      <TableCell>{c.used ?? '—'}</TableCell>
                      <TableCell
                        className={
                          (c.available ?? 0) === 0 ? 'font-semibold text-danger-fg-subtle' : ''
                        }
                      >
                        {c.available ?? '—'}
                      </TableCell>
                      <TableCell>{formatDateTime(c.updated_at)}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            )
          }
        </QueryState>
      )}
    </Card>
  );
}

export function RegulationRequestDetail({ requestId }: { requestId: string }) {
  const query = useRegulationRequest(requestId);
  const [adding, setAdding] = useState(false);

  return (
    <PurposeRequired>
      <QueryState
        isLoading={query.isLoading}
        error={query.error}
        data={query.data}
        onRetry={() => void query.refetch()}
      >
        {(request) => {
          const status = regulationStatusLabels[request.status];
          const priority = regulationPriorityLabels[request.priority ?? 'elective'];
          return (
            <div className="flex flex-col gap-6">
              <PageHeader
                title={request.service_description ?? request.requested_service_code}
                description={
                  <>
                    {t.regulation.detailTitle} · {regulationKindLabels[request.kind]} ·{' '}
                    {request.specialty ?? '—'} · SIGTAP {request.requested_service_code}
                  </>
                }
                actions={
                  <>
                    <Badge tone={priority.tone}>{priority.label}</Badge>
                    <Badge tone={status.tone}>{status.label}</Badge>
                  </>
                }
              />

              <p
                role="note"
                className="flex items-start gap-2 rounded-md border border-info bg-info-subtle p-3 text-sm text-primary-fg-subtle"
              >
                <ShieldCheck aria-hidden="true" className="mt-0.5 h-5 w-5 shrink-0" />
                <span>{t.regulation.decisionNotice}</span>
              </p>

              <CitizenBlock citizenId={request.citizen_id} />

              <Card as="section" aria-labelledby="reg-summary">
                <CardHeader title={<span id="reg-summary">{t.app.details}</span>} />
                <dl className="grid gap-x-6 gap-y-2 text-sm sm:grid-cols-2 lg:grid-cols-3">
                  <div>
                    <dt className="text-fg-muted">{t.regulation.requestedAt}</dt>
                    <dd>
                      {formatDateTime(request.requested_at)}{' '}
                      <span className="text-fg-muted">
                        ({t.regulation.waitingSince} {request.waiting_days} {t.regulation.waitingDays})
                      </span>
                    </dd>
                  </div>
                  <div>
                    <dt className="text-fg-muted">{t.regulation.sla}</dt>
                    <dd>
                      <TaskSLAIndicator task={slaTask(request)} />
                    </dd>
                  </div>
                  <div>
                    <dt className="text-fg-muted">{t.regulation.requestingUnit}</dt>
                    <dd>
                      {request.requesting_unit_name ?? '—'}
                      {request.requesting_cnes ? (
                        <span className="text-fg-muted"> · CNES {request.requesting_cnes}</span>
                      ) : null}
                    </dd>
                  </div>
                  <div>
                    <dt className="text-fg-muted">{t.regulation.provider}</dt>
                    <dd>
                      {request.provider_name ?? '—'}
                      {request.provider_cnes ? (
                        <span className="text-fg-muted"> · CNES {request.provider_cnes}</span>
                      ) : null}
                    </dd>
                  </div>
                  <div>
                    <dt className="text-fg-muted">{t.regulation.justificationPresent}</dt>
                    <dd>{request.justification_present ? t.app.yes : t.app.no}</dd>
                  </div>
                  <div>
                    <dt className="text-fg-muted">{t.regulation.attachedDocuments}</dt>
                    <dd>{request.attached_documents_count ?? 0}</dd>
                  </div>
                  <div>
                    <dt className="text-fg-muted">{t.regulation.sourceRecord}</dt>
                    <dd className="font-mono text-xs">
                      {request.source_system} · {request.source_record_id ?? '—'}
                    </dd>
                  </div>
                  {request.decision_reason ? (
                    <div className="sm:col-span-2">
                      <dt className="text-fg-muted">{t.regulation.decisionReason}</dt>
                      <dd>{request.decision_reason}</dd>
                    </div>
                  ) : null}
                </dl>
              </Card>

              <div className="grid gap-6 lg:grid-cols-2">
                <StatusTimeline request={request} />
                <IssuesPanel request={request} onAdd={() => setAdding(true)} />
                <CapacityPanel request={request} />
                <Card as="section" aria-labelledby="reg-appointment">
                  <CardHeader title={<span id="reg-appointment">{t.regulation.linkedAppointment}</span>} />
                  {request.appointment_id ? (
                    <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-sm">
                      <dt className="text-fg-muted">ID</dt>
                      <dd className="font-mono text-xs">{request.appointment_id}</dd>
                      <dt className="text-fg-muted">Data</dt>
                      <dd>{formatDateTime(request.scheduled_at)}</dd>
                    </dl>
                  ) : (
                    <p className="text-sm text-fg-muted">{t.regulation.noAppointment}</p>
                  )}
                  <Link
                    href={`/cidadaos/${request.citizen_id}/timeline`}
                    className="mt-3 inline-block text-sm font-medium text-primary-fg-subtle hover:underline"
                  >
                    {t.regulation.openAppointment}
                  </Link>
                </Card>
              </div>

              <AddIssueDialog requestId={request.id} open={adding} onOpenChange={setAdding} />
            </div>
          );
        }}
      </QueryState>
    </PurposeRequired>
  );
}
