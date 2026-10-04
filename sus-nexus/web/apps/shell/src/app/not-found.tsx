import Link from 'next/link';
import { Button, EmptyState } from '@sus-nexus/design-system';
import { t } from '@/i18n';

export default function NotFound() {
  return (
    <EmptyState
      title={t.app.notFoundTitle}
      description={t.app.notFoundDescription}
      action={
        <Button asChild variant="secondary">
          <Link href="/">{t.nav.home}</Link>
        </Button>
      }
    />
  );
}
