import type { Metadata, Viewport } from 'next';
import type { ReactNode } from 'react';
import { cookies, headers } from 'next/headers';
import { getAuth } from '@sus-nexus/auth/server';
import { AppProviders } from '@/components/providers/AppProviders';
import { AppShell } from '@/components/layout/AppShell';
import { parsePurposeCookie, PURPOSE_COOKIE, purposesForRoles } from '@/lib/purpose';
import { PUBLIC_ENV } from '@/lib/env';
import { t } from '@/i18n';
import './globals.css';

export const metadata: Metadata = {
  title: { default: t.app.name, template: `%s · ${t.app.name}` },
  description: t.app.tagline,
  robots: { index: false, follow: false },
};

export const viewport: Viewport = {
  themeColor: [
    { media: '(prefers-color-scheme: light)', color: '#1351B4' },
    { media: '(prefers-color-scheme: dark)', color: '#071D41' },
  ],
  width: 'device-width',
  initialScale: 1,
};

export const dynamic = 'force-dynamic';

export default async function RootLayout({ children }: { children: ReactNode }) {
  const [session, cookieStore, headerStore] = await Promise.all([
    getAuth().publicSession(),
    cookies(),
    headers(),
  ]);
  const nonce = headerStore.get('x-nonce') ?? undefined;
  const purpose = parsePurposeCookie(cookieStore.get(PURPOSE_COOKIE)?.value);
  const allowedPurposes = session ? purposesForRoles(session.roles) : [];

  return (
    <html lang="pt-BR" suppressHydrationWarning>
      <body>
        <AppProviders session={session} purpose={purpose} nonce={nonce}>
          {session ? (
            <AppShell purpose={purpose} allowedPurposes={allowedPurposes} mock={PUBLIC_ENV.apiMock}>
              {children}
            </AppShell>
          ) : (
            children
          )}
        </AppProviders>
      </body>
    </html>
  );
}
