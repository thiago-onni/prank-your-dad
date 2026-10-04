import { Suspense } from 'react';
import { SkeletonList } from '@sus-nexus/design-system';
import { RegistryPage } from '@/features/cadastro/RegistryPage';
import { t } from '@/i18n';

export const metadata = { title: t.registry.title };

export default function Page() {
  return (
    <Suspense fallback={<SkeletonList rows={6} />}>
      <RegistryPage />
    </Suspense>
  );
}
