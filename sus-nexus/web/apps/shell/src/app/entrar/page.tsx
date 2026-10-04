import { redirect } from 'next/navigation';
import { Button, Card, CardHeader } from '@sus-nexus/design-system';
import { getAuth } from '@sus-nexus/auth/server';
import { t } from '@/i18n';

export const metadata = { title: t.auth.signIn };

export default async function LoginPage({
  searchParams,
}: {
  searchParams: Promise<{ callbackUrl?: string }>;
}) {
  const auth = getAuth();
  const session = await auth.publicSession();
  const { callbackUrl } = await searchParams;
  const target = callbackUrl?.startsWith('/') ? callbackUrl : '/';
  if (session) redirect(target);

  return (
    <main id="conteudo" className="flex min-h-dvh items-center justify-center p-4">
      <Card className="w-full max-w-md">
        <CardHeader
          headingLevel={2}
          title={t.auth.loginTitle}
          description={t.auth.loginDescription}
        />
        <form
          action={async () => {
            'use server';
            await getAuth().signIn(target);
          }}
        >
          <Button type="submit" className="w-full">
            {t.auth.loginWithKeycloak}
          </Button>
        </form>
      </Card>
    </main>
  );
}
