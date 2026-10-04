'use client';

import { useRouter } from 'next/navigation';
import { useState, useTransition } from 'react';
import type { Purpose } from '@sus-nexus/api-client';
import { Select, useToast } from '@sus-nexus/design-system';
import { purposeLabels } from '@sus-nexus/domain-components';
import { t } from '@/i18n';

export interface PurposeSelectorProps {
  purpose: Purpose | undefined;
  allowed: Purpose[];
}

/** Seletor de finalidade de acesso (X-Purpose-Of-Use) persistido em cookie de sessão. */
export function PurposeSelector({ purpose, allowed }: PurposeSelectorProps) {
  const router = useRouter();
  const { toast } = useToast();
  const [pending, startTransition] = useTransition();
  const [value, setValue] = useState<string | undefined>(purpose);

  const onChange = (next: string) => {
    setValue(next);
    startTransition(async () => {
      const res = await fetch('/api/session/purpose', {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ purpose: next }),
      });
      if (res.ok) {
        toast({ title: t.purpose.changed, tone: 'success' });
        router.refresh();
      } else {
        toast({ title: t.purpose.label, description: t.app.unexpectedError, tone: 'danger' });
        setValue(purpose);
      }
    });
  };

  return (
    <Select
      label={t.purpose.label}
      hideLabel
      placeholder={t.purpose.select}
      value={value}
      onValueChange={onChange}
      disabled={pending || allowed.length === 0}
      options={allowed.map((p) => ({ value: p, label: purposeLabels[p] }))}
      className="min-w-56"
      description={undefined}
    />
  );
}
