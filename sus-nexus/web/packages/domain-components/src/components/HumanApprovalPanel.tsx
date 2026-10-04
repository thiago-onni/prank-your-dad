'use client';

import { useId, useState, type ReactNode } from 'react';
import { Button, Card, CardHeader, Textarea, cn } from '@sus-nexus/design-system';
import { Check, X } from 'lucide-react';

export interface HumanApprovalPanelProps {
  title: ReactNode;
  description?: ReactNode;
  /** Conteúdo (evidências, diffs) exibido acima das ações. */
  children?: ReactNode;
  approveLabel?: string;
  rejectLabel?: string;
  /** Tamanho mínimo da justificativa (contrato: 10). */
  minJustification?: number;
  maxJustification?: number;
  onApprove: (justification: string) => void | Promise<void>;
  onReject: (justification: string) => void | Promise<void>;
  isSubmitting?: boolean;
  disabled?: boolean;
  disabledReason?: string;
  className?: string;
}

/** Aprovar/rejeitar com justificativa obrigatória (auditada). */
export function HumanApprovalPanel({
  title,
  description,
  children,
  approveLabel = 'Aprovar',
  rejectLabel = 'Rejeitar',
  minJustification = 10,
  maxJustification = 500,
  onApprove,
  onReject,
  isSubmitting,
  disabled,
  disabledReason,
  className,
}: HumanApprovalPanelProps) {
  const [justification, setJustification] = useState('');
  const [error, setError] = useState<string | undefined>();
  const [pending, setPending] = useState<'approve' | 'reject' | null>(null);
  const hintId = useId();

  const validate = (): boolean => {
    const trimmed = justification.trim();
    if (trimmed.length < minJustification) {
      setError(`Informe uma justificativa com ao menos ${minJustification} caracteres.`);
      return false;
    }
    if (trimmed.length > maxJustification) {
      setError(`A justificativa deve ter no máximo ${maxJustification} caracteres.`);
      return false;
    }
    setError(undefined);
    return true;
  };

  const run = async (kind: 'approve' | 'reject') => {
    if (!validate()) return;
    setPending(kind);
    try {
      await (kind === 'approve' ? onApprove(justification.trim()) : onReject(justification.trim()));
    } finally {
      setPending(null);
    }
  };

  const busy = isSubmitting || pending !== null;

  return (
    <Card
      as="section"
      className={cn('flex flex-col gap-3', className)}
      aria-labelledby="approval-title"
    >
      <CardHeader title={<span id="approval-title">{title}</span>} description={description} />
      {children}
      <Textarea
        label="Justificativa"
        required
        value={justification}
        onChange={(e) => {
          setJustification(e.target.value);
          if (error) setError(undefined);
        }}
        error={error}
        description={`Obrigatória, entre ${minJustification} e ${maxJustification} caracteres. Fica registrada na trilha de auditoria.`}
        maxLength={maxJustification}
        disabled={disabled || busy}
        aria-describedby={hintId}
      />
      <p id={hintId} className="text-xs text-fg-subtle">
        {justification.trim().length}/{maxJustification}
      </p>
      {disabled && disabledReason ? (
        <p role="status" className="text-sm text-fg-muted">
          {disabledReason}
        </p>
      ) : null}
      <div className="flex flex-wrap justify-end gap-2">
        <Button
          variant="danger"
          onClick={() => void run('reject')}
          disabled={disabled || busy}
          loading={pending === 'reject'}
        >
          <X aria-hidden="true" className="h-4 w-4" />
          {rejectLabel}
        </Button>
        <Button
          variant="primary"
          onClick={() => void run('approve')}
          disabled={disabled || busy}
          loading={pending === 'approve'}
        >
          <Check aria-hidden="true" className="h-4 w-4" />
          {approveLabel}
        </Button>
      </div>
    </Card>
  );
}
