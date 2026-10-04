'use client';

import type { ReactNode } from 'react';
import { useCurrentPurpose } from '@sus-nexus/api-client';
import { EmptyState } from '@sus-nexus/design-system';
import { ShieldAlert } from 'lucide-react';
import { t } from '@/i18n';

/** Bloqueia conteúdo que consulta dados de cidadãos enquanto não há finalidade selecionada. */
export function PurposeRequired({ children }: { children: ReactNode }) {
  const purpose = useCurrentPurpose();
  if (!purpose) {
    return (
      <EmptyState
        icon={<ShieldAlert className="h-10 w-10" />}
        title={t.purpose.label}
        description={t.purpose.required}
      />
    );
  }
  return <>{children}</>;
}
