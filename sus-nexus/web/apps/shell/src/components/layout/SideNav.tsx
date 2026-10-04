'use client';

import Link from 'next/link';
import { usePathname } from 'next/navigation';
import {
  Activity,
  Bot,
  Briefcase,
  Cable,
  ClipboardList,
  FlaskConical,
  Gauge,
  HeartPulse,
  Home,
  Users,
  type LucideIcon,
} from 'lucide-react';
import { cn } from '@sus-nexus/design-system';
import { useSession } from '@sus-nexus/auth/client';
import { t } from '@/i18n';
import { visibleNavItems } from './nav';

const icons: Record<string, LucideIcon> = {
  '/': Home,
  '/integracoes': Cable,
  '/cadastro': Users,
  '/tarefas': ClipboardList,
  '/regulacao': Gauge,
  '/exames': FlaskConical,
  '/cuidado': HeartPulse,
  '/producao': Briefcase,
  '/agentes': Bot,
  '/situacao': Activity,
};

export function SideNav({ onNavigate }: { onNavigate?: () => void }) {
  const pathname = usePathname();
  const { session } = useSession();
  const items = visibleNavItems(session?.roles ?? []);

  return (
    <nav aria-label={t.app.mainNavigation} className="flex flex-col gap-1 p-2">
      <ul className="flex flex-col gap-1">
        {items.map((item) => {
          const active = item.href === '/' ? pathname === '/' : pathname.startsWith(item.href);
          const Icon = icons[item.href] ?? Home;
          return (
            <li key={item.href}>
              <Link
                href={item.href}
                onClick={onNavigate}
                aria-current={active ? 'page' : undefined}
                title={item.description}
                className={cn(
                  'flex items-center gap-3 rounded-md px-3 py-2 text-base font-medium text-fg-muted',
                  'hover:bg-primary-subtle hover:text-primary-fg-subtle',
                  'focus-visible:outline-4 focus-visible:outline-focus focus-visible:-outline-offset-2',
                  active && 'bg-primary-subtle font-semibold text-primary-fg-subtle',
                )}
              >
                <Icon aria-hidden="true" className="h-5 w-5 shrink-0" />
                <span>{item.label}</span>
              </Link>
            </li>
          );
        })}
      </ul>
    </nav>
  );
}
