'use client';

import { useState } from 'react';
import {
  newCorrelationId,
  useRegisterProductionOutcome,
  type ProductionOutcome,
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
import { productionOutcomeLabels } from '@sus-nexus/domain-components';
import { describeError } from '@/lib/problem';
import { format, t } from '@/i18n';

export type OutcomeScope =
  { kind: 'record'; recordId: string } | { kind: 'batch'; batchId: string };

const RECORD_OUTCOMES: ProductionOutcome[] = [
  'transmitted',
  'received',
  'accepted',
  'rejected',
  'paid',
];
/** Rejeição e pagamento exigem registro; o lote aceita os demais (PRO-008). */
const BATCH_OUTCOMES: ProductionOutcome[] = ['transmitted', 'received', 'accepted'];

function nowLocal(): string {
  const d = new Date();
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

export interface OutcomeDialogProps {
  scope: OutcomeScope;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

/** Registrar retorno do processamento oficial (SIA/SIH) por registro ou lote. */
export function OutcomeDialog({ scope, open, onOpenChange }: OutcomeDialogProps) {
  const outcomes = scope.kind === 'batch' ? BATCH_OUTCOMES : RECORD_OUTCOMES;
  const [outcome, setOutcome] = useState<ProductionOutcome>(outcomes[0] as ProductionOutcome);
  const [processedAt, setProcessedAt] = useState(nowLocal);
  const [protocol, setProtocol] = useState('');
  const [reasonCode, setReasonCode] = useState('');
  const [reason, setReason] = useState('');
  const [paidAmount, setPaidAmount] = useState('');
  const [approvedQuantity, setApprovedQuantity] = useState('');
  const [errors, setErrors] = useState<{ reason?: string; paid?: string }>({});
  const mutation = useRegisterProductionOutcome();
  const { toast } = useToast();

  const reset = () => {
    setOutcome(outcomes[0] as ProductionOutcome);
    setProcessedAt(nowLocal());
    setProtocol('');
    setReasonCode('');
    setReason('');
    setPaidAmount('');
    setApprovedQuantity('');
    setErrors({});
  };

  const submit = () => {
    const next: typeof errors = {};
    if (outcome === 'rejected' && reason.trim().length === 0)
      next.reason = t.production.reasonRequired;
    const paid = paidAmount === '' ? undefined : Number(paidAmount.replace(',', '.'));
    if (outcome === 'paid' && (paid === undefined || Number.isNaN(paid) || paid < 0))
      next.paid = t.production.paidAmountInvalid;
    setErrors(next);
    if (Object.keys(next).length > 0) return;
    const sourceId = `web-${newCorrelationId()}`;
    mutation.mutate(
      {
        source: { system: 'manual', connector: 'sus-nexus-web', source_record_id: sourceId },
        outcome,
        processed_at: new Date(processedAt).toISOString(),
        ...(scope.kind === 'record'
          ? { production_record_id: scope.recordId }
          : { batch_id: scope.batchId }),
        protocol_number: protocol.trim() || undefined,
        reason_code: outcome === 'rejected' ? reasonCode.trim() || undefined : undefined,
        reason: outcome === 'rejected' ? reason.trim() : undefined,
        paid_amount: outcome === 'paid' ? paid : undefined,
        approved_quantity:
          outcome === 'paid' && approvedQuantity !== '' ? Number(approvedQuantity) : undefined,
        idempotencyKey: sourceId,
      },
      {
        onSuccess: (result) => {
          toast({
            title: result.unchanged
              ? t.production.outcomeUnchanged
              : format(t.production.outcomeSaved, { count: result.affected.length }),
            tone: 'success',
          });
          reset();
          onOpenChange(false);
        },
        onError: (err) => {
          const { message, correlationId } = describeError(err);
          toast({
            title: t.production.outcomeFailed,
            description: `${message}${correlationId ? ` (${correlationId})` : ''}`,
            tone: 'danger',
          });
        },
      },
    );
  };

  return (
    <Dialog
      open={open}
      onOpenChange={(o) => {
        if (!o) reset();
        onOpenChange(o);
      }}
    >
      <DialogContent
        title={t.production.outcomeTitle}
        description={t.production.outcomeDescription}
        size="md"
        footer={
          <>
            <Button variant="secondary" onClick={() => onOpenChange(false)}>
              {t.app.cancel}
            </Button>
            <Button variant="primary" onClick={submit} loading={mutation.isPending}>
              {t.production.registerOutcome}
            </Button>
          </>
        }
      >
        <div className="flex flex-col gap-4">
          <Select
            label={t.production.outcome}
            required
            value={outcome}
            onValueChange={(v) => setOutcome(v as ProductionOutcome)}
            options={outcomes.map((o) => ({ value: o, label: productionOutcomeLabels[o] }))}
          />
          <Input
            label={t.production.processedAt}
            type="datetime-local"
            required
            value={processedAt}
            onChange={(e) => setProcessedAt(e.target.value)}
          />
          <Input
            label={t.production.protocolNumber}
            value={protocol}
            maxLength={64}
            onChange={(e) => setProtocol(e.target.value)}
          />
          {outcome === 'rejected' ? (
            <>
              <Input
                label={t.production.reasonCode}
                value={reasonCode}
                maxLength={32}
                onChange={(e) => setReasonCode(e.target.value)}
              />
              <Textarea
                label={t.production.reason}
                required
                value={reason}
                maxLength={500}
                rows={3}
                error={errors.reason}
                onChange={(e) => setReason(e.target.value)}
              />
            </>
          ) : null}
          {outcome === 'paid' ? (
            <>
              <Input
                label={t.production.paidAmount}
                inputMode="decimal"
                required
                value={paidAmount}
                error={errors.paid}
                onChange={(e) => setPaidAmount(e.target.value)}
              />
              <Input
                label={t.production.approvedQuantity}
                type="number"
                min={0}
                value={approvedQuantity}
                onChange={(e) => setApprovedQuantity(e.target.value)}
              />
            </>
          ) : null}
        </div>
      </DialogContent>
    </Dialog>
  );
}
