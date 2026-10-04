'use client';

import { useMemo, useState } from 'react';
import {
  useCloseCarePlan,
  useCreateCarePlan,
  useProtocols,
  useUpdateCarePlanItem,
  type CarePlan,
  type CarePlanItem,
  type CarePlanItemStatus,
  type CitizenDetail,
  type Protocol,
} from '@sus-nexus/api-client';
import {
  Button,
  Dialog,
  DialogContent,
  Input,
  Select,
  Textarea,
  useToast,
} from '@sus-nexus/design-system';
import {
  calculateAge,
  carePlanItemStatusLabels,
  careLineLabel,
} from '@sus-nexus/domain-components';
import { describeError } from '@/lib/problem';
import { t } from '@/i18n';

const ITEM_STATUSES = Object.keys(carePlanItemStatusLabels) as CarePlanItemStatus[];

function useFailureToast() {
  const { toast } = useToast();
  return (title: string, err: unknown) => {
    const { message, correlationId } = describeError(err);
    toast({
      title,
      description: `${message}${correlationId ? ` (${correlationId})` : ''}`,
      tone: 'danger',
    });
  };
}

/**
 * Pré-filtro de elegibilidade (sexo/idade) só para usabilidade: a decisão final é do core,
 * que avalia a expressão restrita do protocolo.
 */
export function eligibleForCitizen(protocol: Protocol, citizen: CitizenDetail): boolean {
  const e = protocol.eligibility ?? {};
  const age = calculateAge(citizen.birthdate);
  if (typeof e.sex === 'string' && citizen.sex !== e.sex) return false;
  if (typeof e.min_age === 'number' && (age === undefined || age < e.min_age)) return false;
  if (typeof e.max_age === 'number' && (age === undefined || age > e.max_age)) return false;
  return true;
}

// ---------- atualizar item ----------

export function UpdateItemDialog({
  plan,
  item,
  onClose,
}: {
  plan: CarePlan;
  item: CarePlanItem | null;
  onClose: () => void;
}) {
  const [status, setStatus] = useState<CarePlanItemStatus>('done');
  const [performedAt, setPerformedAt] = useState('');
  const [evidence, setEvidence] = useState('');
  const [note, setNote] = useState('');
  const mutation = useUpdateCarePlanItem();
  const { toast } = useToast();
  const fail = useFailureToast();

  const close = () => {
    setStatus('done');
    setPerformedAt('');
    setEvidence('');
    setNote('');
    onClose();
  };

  const submit = () => {
    if (!item) return;
    mutation.mutate(
      {
        carePlanId: plan.id,
        itemId: item.id,
        status,
        performed_at:
          status === 'done' && performedAt
            ? new Date(`${performedAt}T12:00:00-03:00`).toISOString()
            : undefined,
        evidence_ref: evidence.trim() || undefined,
        note: note.trim() || undefined,
      },
      {
        onSuccess: () => {
          toast({ title: t.carePlan.itemUpdated, tone: 'success' });
          close();
        },
        onError: (err) => fail(t.carePlan.itemFailed, err),
      },
    );
  };

  return (
    <Dialog open={item !== null} onOpenChange={(o) => (!o ? close() : undefined)}>
      {item ? (
        <DialogContent
          title={t.carePlan.updateItemTitle}
          description={item.title}
          footer={
            <>
              <Button variant="secondary" onClick={close}>
                {t.app.cancel}
              </Button>
              <Button onClick={submit} loading={mutation.isPending}>
                {t.app.save}
              </Button>
            </>
          }
        >
          <div className="flex flex-col gap-4">
            <Select
              label={t.carePlan.status}
              value={status}
              onValueChange={(v) => setStatus(v as CarePlanItemStatus)}
              required
              options={ITEM_STATUSES.map((s) => ({
                value: s,
                label: carePlanItemStatusLabels[s].label,
              }))}
            />
            {status === 'done' ? (
              <Input
                label={t.carePlan.performedAt}
                type="date"
                value={performedAt}
                onChange={(e) => setPerformedAt(e.target.value)}
              />
            ) : null}
            <Input
              label={t.carePlan.evidence}
              description={t.carePlan.evidenceHelp}
              value={evidence}
              onChange={(e) => setEvidence(e.target.value)}
              maxLength={200}
            />
            <Textarea
              label={t.hospital.note}
              description={t.hospital.noteHelp}
              value={note}
              onChange={(e) => setNote(e.target.value)}
              maxLength={500}
              rows={3}
            />
          </div>
        </DialogContent>
      ) : null}
    </Dialog>
  );
}

// ---------- encerrar plano ----------

export function ClosePlanDialog({ plan, onClose }: { plan: CarePlan | null; onClose: () => void }) {
  const [status, setStatus] = useState<'completed' | 'cancelled'>('completed');
  const [reason, setReason] = useState('');
  const [error, setError] = useState<string | undefined>();
  const mutation = useCloseCarePlan();
  const { toast } = useToast();
  const fail = useFailureToast();

  const close = () => {
    setStatus('completed');
    setReason('');
    setError(undefined);
    onClose();
  };

  const submit = () => {
    if (!plan) return;
    const trimmed = reason.trim();
    if (trimmed.length < 5 || trimmed.length > 500) {
      setError(t.carePlan.closeReasonHelp);
      return;
    }
    mutation.mutate(
      { carePlanId: plan.id, status, reason: trimmed },
      {
        onSuccess: () => {
          toast({ title: t.carePlan.closed, tone: 'success' });
          close();
        },
        onError: (err) => fail(t.carePlan.closeFailed, err),
      },
    );
  };

  return (
    <Dialog open={plan !== null} onOpenChange={(o) => (!o ? close() : undefined)}>
      {plan ? (
        <DialogContent
          title={t.carePlan.closeTitle}
          description={`${careLineLabel(plan.care_line)} · v${plan.protocol_version}`}
          footer={
            <>
              <Button variant="secondary" onClick={close}>
                {t.app.cancel}
              </Button>
              <Button variant="danger" onClick={submit} loading={mutation.isPending}>
                {t.carePlan.close}
              </Button>
            </>
          }
        >
          <div className="flex flex-col gap-4">
            <Select
              label={t.carePlan.closeStatus}
              value={status}
              onValueChange={(v) => setStatus(v as 'completed' | 'cancelled')}
              required
              options={[
                { value: 'completed', label: t.carePlan.completed },
                { value: 'cancelled', label: t.carePlan.cancelled },
              ]}
            />
            <Textarea
              label={t.carePlan.closeReason}
              required
              description={t.carePlan.closeReasonHelp}
              value={reason}
              onChange={(e) => {
                setReason(e.target.value);
                if (error) setError(undefined);
              }}
              error={error}
              maxLength={500}
              rows={3}
            />
          </div>
        </DialogContent>
      ) : null}
    </Dialog>
  );
}

// ---------- criar plano ----------

export function CreatePlanDialog({
  citizen,
  activeCareLines,
  open,
  onOpenChange,
}: {
  citizen: CitizenDetail;
  activeCareLines: string[];
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const protocols = useProtocols({ status: 'active' }, { enabled: open });
  const [protocolId, setProtocolId] = useState('');
  const mutation = useCreateCarePlan();
  const { toast } = useToast();
  const fail = useFailureToast();
  const eligible = useMemo(
    () =>
      (protocols.data ?? []).filter(
        (p) =>
          p.status === 'active' &&
          !activeCareLines.includes(p.care_line) &&
          eligibleForCitizen(p, citizen),
      ),
    [protocols.data, activeCareLines, citizen],
  );

  const close = () => {
    setProtocolId('');
    onOpenChange(false);
  };

  const submit = () => {
    const protocol = eligible.find((p) => p.id === protocolId);
    if (!protocol) return;
    mutation.mutate(
      {
        citizen_id: citizen.id,
        protocol_id: protocol.id,
        protocol_version: protocol.version,
        health_unit_cnes: citizen.health_unit_cnes,
        team_ine: citizen.team_ine,
        origin: { kind: 'professional' },
      },
      {
        onSuccess: () => {
          toast({ title: t.carePlan.created, tone: 'success' });
          close();
        },
        onError: (err) => fail(t.carePlan.createFailed, err),
      },
    );
  };

  return (
    <Dialog open={open} onOpenChange={(o) => (!o ? close() : onOpenChange(true))}>
      <DialogContent
        title={t.carePlan.createTitle}
        description={t.carePlan.createDescription}
        footer={
          <>
            <Button variant="secondary" onClick={close}>
              {t.app.cancel}
            </Button>
            <Button onClick={submit} loading={mutation.isPending} disabled={!protocolId}>
              {t.carePlan.create}
            </Button>
          </>
        }
      >
        {protocols.isLoading ? (
          <p className="text-sm text-fg-muted">{t.app.loading}</p>
        ) : eligible.length === 0 ? (
          <p className="text-sm">{t.carePlan.noEligible}</p>
        ) : (
          <Select
            label={t.carePlan.protocol}
            value={protocolId || undefined}
            onValueChange={setProtocolId}
            required
            options={eligible.map((p) => ({
              value: p.id,
              label: `${careLineLabel(p.care_line)} — ${p.name} (v${p.version})`,
            }))}
          />
        )}
      </DialogContent>
    </Dialog>
  );
}
