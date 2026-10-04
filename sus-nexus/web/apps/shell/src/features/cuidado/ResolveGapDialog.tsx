'use client';

import { useState } from 'react';
import { useResolveCareGap, type CareGap, type CareGapResolution } from '@sus-nexus/api-client';
import {
  Button,
  Dialog,
  DialogContent,
  Select,
  Textarea,
  useToast,
} from '@sus-nexus/design-system';
import { careGapKindLabels, careGapResolutionLabels } from '@sus-nexus/domain-components';
import { describeError } from '@/lib/problem';
import { t } from '@/i18n';

const RESOLUTIONS = Object.keys(careGapResolutionLabels) as CareGapResolution[];

/** Registra o desfecho da busca ativa (CUI-006): resolve a lacuna no core. */
export function ResolveGapDialog({ gap, onClose }: { gap: CareGap | null; onClose: () => void }) {
  const [resolution, setResolution] = useState<CareGapResolution>('contact_made');
  const [note, setNote] = useState('');
  const mutation = useResolveCareGap();
  const { toast } = useToast();

  const close = () => {
    setResolution('contact_made');
    setNote('');
    onClose();
  };

  const submit = () => {
    if (!gap) return;
    mutation.mutate(
      { careGapId: gap.id, resolution, note: note.trim() || undefined },
      {
        onSuccess: () => {
          toast({ title: t.activeSearch.resolved, tone: 'success' });
          close();
        },
        onError: (err) => {
          const { message, correlationId } = describeError(err);
          toast({
            title: t.activeSearch.resolveFailed,
            description: `${message}${correlationId ? ` (${correlationId})` : ''}`,
            tone: 'danger',
          });
        },
      },
    );
  };

  return (
    <Dialog open={gap !== null} onOpenChange={(o) => (!o ? close() : undefined)}>
      {gap ? (
        <DialogContent
          title={t.activeSearch.resolveTitle}
          description={`${careGapKindLabels[gap.gap_kind]} — ${gap.citizen_display_name ?? ''}`}
          size="md"
          footer={
            <>
              <Button variant="secondary" onClick={close}>
                {t.app.cancel}
              </Button>
              <Button variant="primary" onClick={submit} loading={mutation.isPending}>
                {t.activeSearch.resolve}
              </Button>
            </>
          }
        >
          <div className="flex flex-col gap-4">
            <Select
              label={t.activeSearch.resolution}
              value={resolution}
              onValueChange={(v) => setResolution(v as CareGapResolution)}
              required
              options={RESOLUTIONS.map((r) => ({ value: r, label: careGapResolutionLabels[r] }))}
            />
            <Textarea
              label={t.activeSearch.note}
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
