'use client';

import { useState } from 'react';
import { useAddRegulationIssue, type RegulationIssueKindInput } from '@sus-nexus/api-client';
import { Button, Dialog, DialogContent, Select, Textarea, useToast } from '@sus-nexus/design-system';
import { regulationIssueKindLabels } from '@sus-nexus/domain-components';
import { describeError } from '@/lib/problem';
import { t } from '@/i18n';

const KINDS: RegulationIssueKindInput[] = [
  'missing_document',
  'missing_field',
  'clinical_justification',
  'duplicate',
  'other',
];

export interface AddIssueDialogProps {
  requestId: string;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

/** Registrar pendência documental/administrativa (REG-005). Não decide nem prioriza. */
export function AddIssueDialog({ requestId, open, onOpenChange }: AddIssueDialogProps) {
  const [kind, setKind] = useState<RegulationIssueKindInput>('missing_document');
  const [description, setDescription] = useState('');
  const [error, setError] = useState<string | undefined>();
  const mutation = useAddRegulationIssue();
  const { toast } = useToast();

  const reset = () => {
    setKind('missing_document');
    setDescription('');
    setError(undefined);
  };

  const submit = () => {
    const trimmed = description.trim();
    if (trimmed.length < 10 || trimmed.length > 500) {
      setError(t.regulation.issueDescriptionHelp);
      return;
    }
    mutation.mutate(
      { requestId, kind, description: trimmed },
      {
        onSuccess: () => {
          toast({ title: t.regulation.issueCreated, tone: 'success' });
          reset();
          onOpenChange(false);
        },
        onError: (err) => {
          const { message, correlationId } = describeError(err);
          toast({
            title: t.regulation.issueFailed,
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
        title={t.regulation.addIssue}
        description={t.regulation.decisionNotice}
        size="md"
        footer={
          <>
            <Button variant="secondary" onClick={() => onOpenChange(false)}>
              {t.app.cancel}
            </Button>
            <Button variant="primary" onClick={submit} loading={mutation.isPending}>
              {t.regulation.addIssue}
            </Button>
          </>
        }
      >
        <div className="flex flex-col gap-4">
          <Select
            label={t.regulation.issueKind}
            value={kind}
            onValueChange={(v) => setKind(v as RegulationIssueKindInput)}
            required
            options={KINDS.map((k) => ({ value: k, label: regulationIssueKindLabels[k] }))}
          />
          <Textarea
            label={t.regulation.issueDescription}
            required
            description={t.regulation.issueDescriptionHelp}
            value={description}
            onChange={(e) => {
              setDescription(e.target.value);
              if (error) setError(undefined);
            }}
            error={error}
            maxLength={500}
            rows={4}
          />
        </div>
      </DialogContent>
    </Dialog>
  );
}
