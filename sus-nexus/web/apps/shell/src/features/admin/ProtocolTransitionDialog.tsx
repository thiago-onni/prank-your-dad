'use client';

import { useState } from 'react';
import { AlertTriangle } from 'lucide-react';
import {
  useTransitionProtocol,
  type Protocol,
  type ProtocolTransitionAction,
} from '@sus-nexus/api-client';
import { Button, Dialog, DialogContent, Input, Textarea, useToast } from '@sus-nexus/design-system';
import { protocolTransitionLabels } from '@sus-nexus/domain-components';
import { describeError } from '@/lib/problem';
import { format, t } from '@/i18n';

/** Ações que exigem justificativa auditada (10–500). */
const JUSTIFICATION_REQUIRED: ProtocolTransitionAction[] = ['approve', 'revoke'];

export interface PendingTransition {
  protocol: Protocol;
  action: ProtocolTransitionAction;
}

/**
 * Transição do ciclo de aprovação com justificativa. Aprovar sem casos de teste é bloqueado
 * na UI (e no core — plano 8.3).
 */
export function ProtocolTransitionDialog({
  pending,
  onClose,
}: {
  pending: PendingTransition | null;
  onClose: () => void;
}) {
  const [justification, setJustification] = useState('');
  const [effectiveFrom, setEffectiveFrom] = useState('');
  const [error, setError] = useState<string | undefined>();
  const mutation = useTransitionProtocol();
  const { toast } = useToast();

  const close = () => {
    setJustification('');
    setEffectiveFrom('');
    setError(undefined);
    onClose();
  };

  const action = pending?.action;
  const protocol = pending?.protocol;
  const noTests = !protocol?.test_cases_count;
  const blocked = action === 'approve' && noTests;

  const submit = () => {
    if (!protocol || !action || blocked) return;
    const text = justification.trim();
    const required = JUSTIFICATION_REQUIRED.includes(action);
    if ((required && text.length < 10) || text.length > 500) {
      setError(t.protocols.justificationHelp);
      return;
    }
    mutation.mutate(
      {
        protocolId: protocol.id,
        version: protocol.version,
        action,
        justification: text || undefined,
        effective_from:
          action === 'activate' && effectiveFrom
            ? new Date(`${effectiveFrom}T00:00:00-03:00`).toISOString()
            : undefined,
      },
      {
        onSuccess: () => {
          toast({ title: t.protocols.transitioned, tone: 'success' });
          close();
        },
        onError: (err) => {
          const { message, correlationId } = describeError(err);
          toast({
            title: t.protocols.transitionFailed,
            description: `${message}${correlationId ? ` (${correlationId})` : ''}`,
            tone: 'danger',
          });
        },
      },
    );
  };

  return (
    <Dialog open={pending !== null} onOpenChange={(o) => (!o ? close() : undefined)}>
      {protocol && action ? (
        <DialogContent
          title={format(t.protocols.transitionTitle, {
            action: protocolTransitionLabels[action],
            name: protocol.name,
            version: protocol.version,
          })}
          description={`${t.protocols.testCases}: ${protocol.test_cases_count ?? 0}`}
          footer={
            <>
              <Button variant="secondary" onClick={close}>
                {t.app.cancel}
              </Button>
              <Button
                variant={action === 'revoke' ? 'danger' : 'primary'}
                onClick={submit}
                loading={mutation.isPending}
                disabled={blocked}
                aria-describedby={blocked ? 'protocol-tests-required' : undefined}
              >
                {protocolTransitionLabels[action]}
              </Button>
            </>
          }
        >
          <div className="flex flex-col gap-4">
            {blocked ? (
              <p
                id="protocol-tests-required"
                role="alert"
                className="flex gap-2 rounded-md border border-danger bg-danger-subtle p-3 text-sm"
              >
                <AlertTriangle aria-hidden="true" className="h-5 w-5 shrink-0" />
                {t.protocols.testsRequired}
              </p>
            ) : action === 'submit' && noTests ? (
              <p className="flex gap-2 rounded-md border border-border bg-warning-subtle p-3 text-sm">
                <AlertTriangle aria-hidden="true" className="h-5 w-5 shrink-0" />
                {t.protocols.testsWarning}
              </p>
            ) : null}
            {!blocked ? (
              <>
                <Textarea
                  label={t.protocols.justification}
                  required={JUSTIFICATION_REQUIRED.includes(action)}
                  description={t.protocols.justificationHelp}
                  value={justification}
                  onChange={(e) => {
                    setJustification(e.target.value);
                    if (error) setError(undefined);
                  }}
                  error={error}
                  maxLength={500}
                  rows={3}
                />
                {action === 'activate' ? (
                  <Input
                    label={t.protocols.effectiveFromField}
                    description={t.protocols.effectiveFromHelp}
                    type="date"
                    value={effectiveFrom}
                    onChange={(e) => setEffectiveFrom(e.target.value)}
                  />
                ) : null}
              </>
            ) : null}
          </div>
        </DialogContent>
      ) : null}
    </Dialog>
  );
}
