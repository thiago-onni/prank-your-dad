'use client';

import Link from 'next/link';
import { useState, type ReactNode } from 'react';
import { Menu, X } from 'lucide-react';
import type { Purpose } from '@sus-nexus/api-client';
import { Button, cn } from '@sus-nexus/design-system';
import { t } from '@/i18n';
import { PurposeSelector } from './PurposeSelector';
import { SideNav } from './SideNav';
import { SkipLink } from './SkipLink';
import { UserMenu } from './UserMenu';

export interface AppShellProps {
  purpose: Purpose | undefined;
  allowedPurposes: Purpose[];
  mock: boolean;
  children: ReactNode;
}

export function AppShell({ purpose, allowedPurposes, mock, children }: AppShellProps) {
  const [open, setOpen] = useState(false);
  return (
    <div className="flex min-h-dvh flex-col">
      <SkipLink />
      <header className="sticky top-0 z-30 border-b-4 border-yellow-vivid-20 bg-surface shadow-level-1">
        <div className="flex items-center gap-3 px-4 py-2">
          <Button
            variant="ghost"
            size="icon"
            className="lg:hidden"
            aria-label={open ? 'Fechar menu' : 'Abrir menu'}
            aria-expanded={open}
            aria-controls="side-nav"
            onClick={() => setOpen((v) => !v)}
          >
            {open ? (
              <X aria-hidden="true" className="h-5 w-5" />
            ) : (
              <Menu aria-hidden="true" className="h-5 w-5" />
            )}
          </Button>
          <Link
            href="/"
            className="flex items-center gap-2 rounded-md font-bold text-primary-fg-subtle"
          >
            <span aria-hidden="true" className="inline-block h-6 w-6 rounded-sm bg-primary" />
            <span>{t.app.name}</span>
          </Link>
          <div className="ml-auto flex items-center gap-3">
            <PurposeSelector purpose={purpose} allowed={allowedPurposes} />
            <UserMenu />
          </div>
        </div>
        {mock ? (
          <p
            role="status"
            className="bg-warning-subtle px-4 py-1 text-center text-xs font-medium text-warning-fg-subtle"
          >
            {t.app.mockBanner}
          </p>
        ) : null}
      </header>
      <div className="flex flex-1">
        <aside
          id="side-nav"
          className={cn(
            'w-64 shrink-0 border-r border-border bg-surface',
            'fixed inset-y-0 left-0 z-20 pt-16 transition-transform lg:static lg:translate-x-0 lg:pt-0',
            open ? 'translate-x-0' : '-translate-x-full',
          )}
        >
          <SideNav onNavigate={() => setOpen(false)} />
        </aside>
        {open ? (
          <button
            type="button"
            aria-label="Fechar menu"
            className="fixed inset-0 z-10 bg-black/40 lg:hidden"
            onClick={() => setOpen(false)}
          />
        ) : null}
        <main id="conteudo" tabIndex={-1} className="min-w-0 flex-1 p-4 focus:outline-none md:p-6">
          {children}
        </main>
      </div>
    </div>
  );
}
