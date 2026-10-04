import { isSituationTab, SituationPage } from '@/features/situacao/SituationPage';
import { metabaseUrl } from '@/lib/env';
import { t } from '@/i18n';

export const metadata = { title: t.situation.title };
export const dynamic = 'force-dynamic';

export default async function Page({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  const { aba } = await searchParams;
  return (
    <SituationPage
      initialTab={isSituationTab(aba) ? aba : 'indicadores'}
      metabaseUrl={metabaseUrl()}
    />
  );
}
