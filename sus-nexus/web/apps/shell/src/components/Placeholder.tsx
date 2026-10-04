import { EmptyState } from '@sus-nexus/design-system';
import { Construction } from 'lucide-react';
import { PageHeader } from '@/components/PageHeader';
import { t } from '@/i18n';

export function PlaceholderPage({ title, phase }: { title: string; phase: string }) {
  return (
    <>
      <PageHeader title={title} description={`${t.app.comingSoon} · ${phase}`} />
      <EmptyState
        icon={<Construction className="h-10 w-10" />}
        title={t.app.comingSoon}
        description={t.app.comingSoonDescription}
      />
    </>
  );
}
