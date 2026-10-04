'use client';

import { useState } from 'react';
import {
  useCurrentPurpose,
  useRevealIdentifier,
  type MaskedIdentifier,
} from '@sus-nexus/api-client';
import { Button, Dialog, DialogContent, Textarea, useToast } from '@sus-nexus/design-system';
import { formatCns, formatCpf, purposeLabels } from '@sus-nexus/domain-components';
import { describeError } from '@/lib/problem';
import { t } from '@/i18n';

export interface RevealIdentifierDialogProps {
  citizenId: string;
  identifier: MaskedIdentifier | null;
  onClose: () => void;
  onRevealed: (identifierId: string, formatted: string) => void;
}

/** Ação explícita "revelar": finalidade (da sessão) + justificativa obrigatória → access_log no backend. */
export function RevealIdentifierDialog({
  citizenId,
  identifier,
  onClose,
  onRevealed,
}: RevealIdentifierDialogProps) {
  const purpose = useCurrentPurpose();
  const reveal = useRevealIdentifier();
  const { toast } = useToast();
  const [justification, setJustification] = useState('');
  const [error, setError] = useState<string | undefined>();

  const submit = () => {
    const trimmed = justification.trim();
    if (trimmed.length < 10 || trimmed.length > 500) {
      setError(t.citizen.reveal.justificationHelp);
      return;
    }
    if (!identifier || !purpose) return;
    reveal.mutate(
      { citizenId, identifierId: identifier.id, purpose, justification: trimmed },
      {
        onSuccess: (data) => {
          const formatted =
            data.system === 'CPF'
              ? formatCpf(data.value)
              : data.system === 'CNS'
                ? formatCns(data.value)
                : data.value;
          onRevealed(identifier.id, formatted);
          toast({ title: t.citizen.reveal.success, tone: 'info' });
          setJustification('');
          onClose();
        },
        onError: (err) => {
          const { message, correlationId } = describeError(err);
          toast({
            title: t.citizen.reveal.failed,
            description: `${message}${correlationId ? ` (${correlationId})` : ''}`,
            tone: 'danger',
          });
        },
      },
    );
  };

  return (
    <Dialog
      open={identifier !== null}
      onOpenChange={(open) => {
        if (!open) {
          setJustification('');
          setError(undefined);
          onClose();
        }
      }}
    >
      <DialogContent
        title={`${t.citizen.reveal.title}: ${identifier?.system ?? ''}`}
        description={t.citizen.reveal.description}
        size="sm"
        footer={
          <>
            <Button variant="secondary" onClick={onClose}>
              {t.app.cancel}
            </Button>
            <Button
              variant="primary"
              onClick={submit}
              loading={reveal.isPending}
              disabled={!purpose}
            >
              {t.citizen.reveal.submit}
            </Button>
          </>
        }
      >
        <dl className="mb-3 grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-sm">
          <dt className="text-fg-muted">{t.purpose.label}</dt>
          <dd className="font-medium">{purpose ? purposeLabels[purpose] : t.purpose.required}</dd>
          <dt className="text-fg-muted">Valor atual</dt>
          <dd className="font-mono">{identifier?.value_masked}</dd>
        </dl>
        <Textarea
          label={t.citizen.reveal.justification}
          description={t.citizen.reveal.justificationHelp}
          required
          value={justification}
          onChange={(e) => {
            setJustification(e.target.value);
            if (error) setError(undefined);
          }}
          error={error}
          maxLength={500}
        />
      </DialogContent>
    </Dialog>
  );
}
