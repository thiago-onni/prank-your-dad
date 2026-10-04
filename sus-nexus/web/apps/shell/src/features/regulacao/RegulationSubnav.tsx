'use client';

import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { cn } from '@sus-nexus/design-system';
import { t } from '@/i18n';

const LINKS = [
  { href: '/regulacao', label: t.regulation.queueView, exact: true },
  { href: '/regulacao/capacidade', label: t.regulation.capacity, exact: false },
];

/** Navegação secundária do cockpit (fila × capacidade). */
export function RegulationSubnav() {
  const pathname = usePathname();
  return (
    <nav aria-label={t.regulation.title} className="mb-4 flex gap-1 border-b border-border">
      {LINKS.map((l) => {
        const active = l.exact ? pathname === l.href : pathname.startsWith(l.href);
        return (
          <Link
            key={l.href}
            href={l.href}
            aria-current={active ? 'page' : undefined}
            className={cn(
              '-mb-px border-b-4 border-transparent px-4 py-2 text-base font-semibold text-fg-muted hover:bg-bg-muted hover:text-fg',
              'focus-visible:outline-4 focus-visible:outline-focus focus-visible:-outline-offset-4',
              active && 'border-primary text-primary-fg-subtle',
            )}
          >
            {l.label}
          </Link>
        );
      })}
    </nav>
  );
}
