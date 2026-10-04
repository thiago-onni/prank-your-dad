'use client';

import { useState } from 'react';
import {
  useCarePlans,
  type CarePlan,
  type CarePlanItem,
  type CitizenDetail,
} from '@sus-nexus/api-client';
import {
  Badge,
  Button,
  Card,
  CardHeader,
  EmptyState,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeaderCell,
  TableRow,
} from '@sus-nexus/design-system';
import {
  carePlanItemKindLabels,
  carePlanItemStatusLabels,
  carePlanOriginLabels,
  carePlanStatusLabels,
  careLineLabel,
  formatDate,
  formatDateTime,
} from '@sus-nexus/domain-components';
import { QueryState } from '@/components/QueryState';
import { format, t } from '@/i18n';
import { ClosePlanDialog, CreatePlanDialog, UpdateItemDialog } from './CarePlanDialogs';

function PlanCard({
  plan,
  onUpdateItem,
  onClose,
}: {
  plan: CarePlan;
  onUpdateItem: (item: CarePlanItem) => void;
  onClose: () => void;
}) {
  const st = carePlanStatusLabels[plan.status];
  const active = plan.status === 'active' || plan.status === 'on_hold';
  const done = plan.items.filter((i) => i.status === 'done').length;
  const headingId = `plan-${plan.id}`;
  return (
    <Card as="section" aria-labelledby={headingId}>
      <CardHeader
        title={
          <span id={headingId}>
            {careLineLabel(plan.care_line)} <Badge tone={st.tone}>{st.label}</Badge>{' '}
            {plan.open_gaps ? (
              <Badge tone="warning">
                {plan.open_gaps} {t.carePlan.openGaps}
              </Badge>
            ) : null}
          </span>
        }
        description={`${t.carePlan.protocolVersion} ${plan.protocol_id} v${plan.protocol_version} · ${t.carePlan.origin}: ${plan.origin?.kind ? carePlanOriginLabels[plan.origin.kind] : '—'} · ${t.carePlan.createdAt} ${formatDateTime(plan.created_at)}`}
        actions={
          active ? (
            <Button size="sm" variant="secondary" onClick={onClose}>
              {t.carePlan.close}
            </Button>
          ) : null
        }
      />
      <p className="mb-2 text-sm text-fg-muted">
        {format(t.carePlan.progress, { done, total: plan.items.length })}
      </p>
      {plan.closed_reason ? (
        <p className="mb-2 text-sm">
          <span className="text-fg-muted">{t.carePlan.closedReason}:</span> {plan.closed_reason}
        </p>
      ) : null}
      <Table aria-label={`${t.carePlan.items}: ${careLineLabel(plan.care_line)}`}>
        <TableHead>
          <TableRow>
            <TableHeaderCell>{t.carePlan.item}</TableHeaderCell>
            <TableHeaderCell>{t.carePlan.expectedBy}</TableHeaderCell>
            <TableHeaderCell>{t.carePlan.status}</TableHeaderCell>
            <TableHeaderCell>{t.carePlan.performedAt}</TableHeaderCell>
            <TableHeaderCell>
              <span className="sr-only">{t.app.actions}</span>
            </TableHeaderCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {plan.items.map((item) => {
            const ist = carePlanItemStatusLabels[item.status];
            return (
              <TableRow key={item.id}>
                <TableCell>
                  <span className="font-medium">{item.title}</span>
                  <span className="block text-xs text-fg-muted">
                    {carePlanItemKindLabels[item.kind]}
                    {item.code ? ` · ${t.carePlan.code} ${item.code}` : ''}
                    {item.periodicity_days ? ` · a cada ${item.periodicity_days} dias` : ''}
                  </span>
                </TableCell>
                <TableCell>{formatDate(item.expected_by)}</TableCell>
                <TableCell>
                  <span className="flex flex-wrap gap-1">
                    <Badge tone={ist.tone}>{ist.label}</Badge>
                    {item.overdue ? <Badge tone="danger">{t.carePlan.overdue}</Badge> : null}
                  </span>
                </TableCell>
                <TableCell>{formatDate(item.performed_at)}</TableCell>
                <TableCell>
                  {active ? (
                    <Button
                      size="sm"
                      variant="tertiary"
                      onClick={() => onUpdateItem(item)}
                      aria-label={`${t.carePlan.updateItem}: ${item.title}`}
                    >
                      {t.carePlan.updateItem}
                    </Button>
                  ) : null}
                </TableCell>
              </TableRow>
            );
          })}
        </TableBody>
      </Table>
    </Card>
  );
}

/** Aba "Plano de cuidado" do cidadão (CUI-001/002). */
export function CarePlansTab({ citizen }: { citizen: CitizenDetail }) {
  const query = useCarePlans({ citizen_id: citizen.id });
  const [editing, setEditing] = useState<{ plan: CarePlan; item: CarePlanItem } | null>(null);
  const [closing, setClosing] = useState<CarePlan | null>(null);
  const [creating, setCreating] = useState(false);
  const plans = query.data?.items ?? [];
  const activeLines = plans
    .filter((p) => p.status === 'active' || p.status === 'on_hold')
    .map((p) => p.care_line);
  // Planos ativos primeiro.
  const sorted = [...plans].sort((a, b) =>
    a.status === b.status ? 0 : a.status === 'active' ? -1 : b.status === 'active' ? 1 : 0,
  );

  return (
    <section aria-labelledby="care-plans-title" className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 id="care-plans-title" className="text-xl font-semibold">
          {t.carePlan.title}
        </h2>
        <Button size="sm" onClick={() => setCreating(true)}>
          {t.carePlan.create}
        </Button>
      </div>
      <QueryState
        isLoading={query.isLoading}
        error={query.error}
        data={query.data}
        onRetry={() => void query.refetch()}
      >
        {() =>
          sorted.length === 0 ? (
            <EmptyState title={t.carePlan.none} />
          ) : (
            <div className="flex flex-col gap-4">
              {sorted.map((plan) => (
                <PlanCard
                  key={plan.id}
                  plan={plan}
                  onUpdateItem={(item) => setEditing({ plan, item })}
                  onClose={() => setClosing(plan)}
                />
              ))}
            </div>
          )
        }
      </QueryState>
      {editing ? (
        <UpdateItemDialog
          plan={editing.plan}
          item={editing.item}
          onClose={() => setEditing(null)}
        />
      ) : null}
      <ClosePlanDialog plan={closing} onClose={() => setClosing(null)} />
      <CreatePlanDialog
        citizen={citizen}
        activeCareLines={activeLines}
        open={creating}
        onOpenChange={setCreating}
      />
    </section>
  );
}
