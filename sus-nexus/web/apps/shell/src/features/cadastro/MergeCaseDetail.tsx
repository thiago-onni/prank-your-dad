'use client';

import Link from 'next/link';
import { useState } from 'react';
import {
  useMergeCase,
  useMergeCaseDecision,
  type CitizenSummary,
  type MergeCase,
} from '@sus-nexus/api-client';
import {
  Badge,
  Card,
  CardHeader,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeaderCell,
  TableRow,
  useToast,
} from '@sus-nexus/design-system';
import {
  formatDate,
  formatDateTime,
  HumanApprovalPanel,
  IdentityConfidenceBadge,
  mergeCaseStatusLabels,
  registrationStateLabels,
} from '@sus-nexus/domain-components';
import { PageHeader } from '@/components/PageHeader';
import { QueryState } from '@/components/QueryState';
import { describeError } from '@/lib/problem';
import { t } from '@/i18n';

export function MergeCaseDetail({ caseId }: { caseId: string }) {
  const query = useMergeCase(caseId);
  return (
    <QueryState
      isLoading={query.isLoading}
      error={query.error}
      data={query.data}
      onRetry={() => void query.refetch()}
    >
      {(mergeCase) => <CaseView mergeCase={mergeCase} />}
    </QueryState>
  );
}

const attributeRows: { key: string; label: string; render: (c: CitizenSummary) => string }[] = [
  { key: 'name', label: 'Nome', render: (c) => c.display_name },
  { key: 'birthdate', label: 'Nascimento', render: (c) => formatDate(c.birthdate) },
  { key: 'mother', label: 'Mãe', render: (c) => c.mother_name_masked ?? '—' },
  {
    key: 'cns',
    label: 'CNS',
    render: (c) => c.identifiers.find((i) => i.system === 'CNS')?.value_masked ?? '—',
  },
  {
    key: 'cpf',
    label: 'CPF',
    render: (c) => c.identifiers.find((i) => i.system === 'CPF')?.value_masked ?? '—',
  },
  { key: 'unit', label: 'UBS (CNES)', render: (c) => c.health_unit_cnes ?? '—' },
  { key: 'team', label: 'Equipe (INE)', render: (c) => c.team_ine ?? '—' },
  { key: 'microarea', label: 'Microárea', render: (c) => c.microarea ?? '—' },
];

function CaseView({ mergeCase }: { mergeCase: MergeCase }) {
  const { merge, reject } = useMergeCaseDecision(mergeCase.id);
  const { toast } = useToast();
  const [survivor, setSurvivor] = useState<string>(mergeCase.candidates[0]?.id ?? '');
  const meta = mergeCaseStatusLabels[mergeCase.status];
  const open = mergeCase.status === 'open' || mergeCase.status === 'in_review';
  const agreementLabel = {
    agree: t.registry.agree,
    disagree: t.registry.disagree,
    missing: t.registry.missing,
  } as const;

  const fail = (error: unknown) => {
    const { message, correlationId } = describeError(error);
    toast({
      title: t.registry.decisionFailed,
      description: `${message}${correlationId ? ` (${correlationId})` : ''}`,
      tone: 'danger',
    });
  };

  return (
    <>
      <PageHeader
        title={
          <>
            {t.registry.caseDetail}{' '}
            <code className="font-mono text-lg text-fg-muted">{mergeCase.id}</code>
          </>
        }
        description={
          <Link href="/cadastro?tab=fila" className="text-primary-fg-subtle hover:underline">
            ← {t.registry.reviewQueue}
          </Link>
        }
        actions={<Badge tone={meta.tone}>{meta.label}</Badge>}
      />

      <div className="grid gap-4 xl:grid-cols-[2fr_1fr]">
        <div className="flex flex-col gap-4">
          <Card as="section" aria-labelledby="candidates">
            <CardHeader
              title={<span id="candidates">{t.registry.candidates}</span>}
              description={`${mergeCase.reason ?? ''} · ${t.registry.score}: ${mergeCase.score?.toLocaleString('pt-BR') ?? '—'} · ${t.registry.ruleVersion}: ${mergeCase.rule_version ?? '—'}`}
            />
            <Table aria-label={t.registry.candidates}>
              <TableHead>
                <TableRow>
                  <TableHeaderCell>{t.registry.attribute}</TableHeaderCell>
                  {mergeCase.candidates.map((c) => (
                    <TableHeaderCell key={c.id}>
                      <div className="flex flex-col gap-1">
                        <Link
                          href={`/cidadaos/${c.id}`}
                          className="font-mono text-xs normal-case text-primary-fg-subtle hover:underline"
                        >
                          {c.id}
                        </Link>
                        <span className="flex flex-wrap gap-1">
                          <Badge tone={registrationStateLabels[c.registration_state].tone}>
                            {registrationStateLabels[c.registration_state].label}
                          </Badge>
                          {c.identity_confidence ? (
                            <IdentityConfidenceBadge confidence={c.identity_confidence} />
                          ) : null}
                        </span>
                      </div>
                    </TableHeaderCell>
                  ))}
                </TableRow>
              </TableHead>
              <TableBody>
                {attributeRows.map((row) => {
                  const values = mergeCase.candidates.map(row.render);
                  const differs = new Set(values).size > 1;
                  return (
                    <TableRow
                      key={row.key}
                      className={differs ? 'bg-warning-subtle/40' : undefined}
                    >
                      <TableHeaderCell scope="row">
                        {row.label}
                        {differs ? <span className="sr-only"> (divergente)</span> : null}
                      </TableHeaderCell>
                      {values.map((v, i) => (
                        <TableCell key={i} className={differs ? 'font-semibold' : undefined}>
                          {v}
                        </TableCell>
                      ))}
                    </TableRow>
                  );
                })}
                {open ? (
                  <TableRow>
                    <TableHeaderCell scope="row">{t.registry.survivor}</TableHeaderCell>
                    {mergeCase.candidates.map((c) => (
                      <TableCell key={c.id}>
                        <label className="flex items-center gap-2">
                          <input
                            type="radio"
                            name="survivor"
                            value={c.id}
                            checked={survivor === c.id}
                            onChange={() => setSurvivor(c.id)}
                            className="h-4 w-4 accent-[var(--sn-primary)]"
                          />
                          <span>{c.display_name}</span>
                        </label>
                      </TableCell>
                    ))}
                  </TableRow>
                ) : null}
              </TableBody>
            </Table>
            {open ? <p className="mt-2 text-xs text-fg-muted">{t.registry.survivorHelp}</p> : null}
          </Card>

          <Card as="section" aria-labelledby="evidence">
            <CardHeader title={<span id="evidence">{t.registry.evidence}</span>} />
            {mergeCase.evidence && mergeCase.evidence.length > 0 ? (
              <Table aria-label={t.registry.evidence}>
                <TableHead>
                  <TableRow>
                    <TableHeaderCell>{t.registry.attribute}</TableHeaderCell>
                    <TableHeaderCell>Comparação</TableHeaderCell>
                    <TableHeaderCell>{t.registry.agreement}</TableHeaderCell>
                    <TableHeaderCell>{t.registry.weight}</TableHeaderCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {mergeCase.evidence.map((e, i) => (
                    <TableRow key={i}>
                      <TableCell>{e.attribute}</TableCell>
                      <TableCell>{e.comparison}</TableCell>
                      <TableCell>
                        {e.agreement ? (
                          <Badge
                            tone={
                              e.agreement === 'agree'
                                ? 'success'
                                : e.agreement === 'disagree'
                                  ? 'danger'
                                  : 'neutral'
                            }
                          >
                            {agreementLabel[e.agreement]}
                          </Badge>
                        ) : (
                          '—'
                        )}
                      </TableCell>
                      <TableCell className="tabular-nums">{e.weight ?? '—'}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            ) : (
              <p className="text-sm text-fg-muted">Sem evidências registradas.</p>
            )}
            {mergeCase.conflicts && mergeCase.conflicts.length > 0 ? (
              <p className="mt-3 text-sm">
                <span className="font-semibold text-danger-fg-subtle">{t.registry.conflicts}:</span>{' '}
                {mergeCase.conflicts.join(', ')}
              </p>
            ) : null}
          </Card>
        </div>

        <div className="flex flex-col gap-4">
          {open ? (
            <HumanApprovalPanel
              title={t.registry.merge}
              description="A decisão é auditada (quem, quando, justificativa) e gera evento no barramento."
              approveLabel={t.registry.merge}
              rejectLabel={t.registry.reject}
              isSubmitting={merge.isPending || reject.isPending}
              onApprove={async (reason) => {
                try {
                  await merge.mutateAsync({ surviving_citizen_id: survivor, reason });
                  toast({ title: t.registry.mergeSuccess, tone: 'success' });
                } catch (error) {
                  fail(error);
                }
              }}
              onReject={async (reason) => {
                try {
                  await reject.mutateAsync({ reason });
                  toast({ title: t.registry.rejectSuccess, tone: 'success' });
                } catch (error) {
                  fail(error);
                }
              }}
            />
          ) : (
            <Card as="section" aria-labelledby="decision">
              <CardHeader title={<span id="decision">{t.registry.caseClosed}</span>} />
              <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-sm">
                <dt className="text-fg-muted">{t.registry.decidedAt}</dt>
                <dd>{formatDateTime(mergeCase.decided_at)}</dd>
                <dt className="text-fg-muted">{t.registry.decidedBy}</dt>
                <dd>{mergeCase.decided_by ?? '—'}</dd>
                <dt className="text-fg-muted">{t.registry.decisionReason}</dt>
                <dd>{mergeCase.decision_reason ?? '—'}</dd>
              </dl>
            </Card>
          )}
          <Card as="section" aria-labelledby="case-meta">
            <CardHeader title={<span id="case-meta">{t.app.details}</span>} />
            <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-sm">
              <dt className="text-fg-muted">{t.registry.openedAt}</dt>
              <dd>{formatDateTime(mergeCase.opened_at)}</dd>
              <dt className="text-fg-muted">{t.registry.ruleVersion}</dt>
              <dd className="font-mono">{mergeCase.rule_version ?? '—'}</dd>
            </dl>
          </Card>
        </div>
      </div>
    </>
  );
}
