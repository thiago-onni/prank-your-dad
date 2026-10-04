'use client';

import { LogOut, Moon, Sun } from 'lucide-react';
import { Button } from '@sus-nexus/design-system';
import { useSession } from '@sus-nexus/auth/client';
import { useTheme } from '@/components/providers/ThemeProvider';
import { t } from '@/i18n';

export function UserMenu() {
  const { session } = useSession();
  const { theme, setTheme } = useTheme();
  const dark = theme === 'dark';
  if (!session) return null;
  return (
    <div className="flex items-center gap-2">
      <div className="hidden text-right text-sm sm:block">
        <p className="font-semibold leading-tight">{session.user.name}</p>
        <p
          className="text-xs text-fg-muted"
          aria-label={`${t.auth.roles}: ${session.roles.join(', ')}`}
        >
          {session.roles.join(' · ')}
        </p>
      </div>
      <Button
        variant="ghost"
        size="icon"
        aria-pressed={dark}
        aria-label={dark ? 'Usar tema claro' : 'Usar tema escuro'}
        onClick={() => setTheme(dark ? 'light' : 'dark')}
      >
        {dark ? (
          <Sun aria-hidden="true" className="h-5 w-5" />
        ) : (
          <Moon aria-hidden="true" className="h-5 w-5" />
        )}
      </Button>
      <form action="/api/auth/signout" method="post">
        <Button type="submit" variant="ghost" size="sm" aria-label={t.auth.signOut}>
          <LogOut aria-hidden="true" className="h-4 w-4" />
          <span className="hidden sm:inline">{t.auth.signOut}</span>
        </Button>
      </form>
    </div>
  );
}
