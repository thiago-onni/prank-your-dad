import { Suspense } from 'react';
import { SkeletonList } from '@sus-nexus/design-system';
import { IntegrationsPage } from '@/features/integracoes/IntegrationsPage';
import { t } from '@/i18n';

export const metadata = { title: t.integrations.title };

export default function Page() {
  return (
    <Suspense fallback={<SkeletonList rows={6} />}>
      <IntegrationsPage />
    </Suspense>
  );
}
