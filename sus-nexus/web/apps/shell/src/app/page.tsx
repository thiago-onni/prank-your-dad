import Link from 'next/link';
import { Card, CardHeader, EmptyState } from '@sus-nexus/design-system';
import { getAuth } from '@sus-nexus/auth/server';
import { PageHeader } from '@/components/PageHeader';
import { visibleNavItems } from '@/components/layout/nav';
import { t } from '@/i18n';

export default async function HomePage() {
  const session = await getAuth().publicSession();
  const items = visibleNavItems(session?.roles ?? []).filter((i) => i.href !== '/');
  return (
    <>
      <PageHeader
        title={t.home.title}
        description={`${t.home.welcome}, ${session?.user.name ?? ''}.`}
      />
      <section aria-labelledby="shortcuts">
        <h2 id="shortcuts" className="mb-3 text-xl font-semibold">
          {t.home.shortcuts}
        </h2>
        {items.length === 0 ? (
          <EmptyState title={t.home.noShortcuts} />
        ) : (
          <ul className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {items.map((item) => (
              <li key={item.href}>
                <Card className="h-full transition-colors hover:border-primary">
                  <CardHeader
                    title={
                      <Link
                        href={item.href}
                        className="rounded-sm text-primary-fg-subtle hover:underline"
                      >
                        {item.label}
                      </Link>
                    }
                    description={item.description}
                  />
                </Card>
              </li>
            ))}
          </ul>
        )}
      </section>
    </>
  );
}
