import { CareWorkbench, isCareTab } from '@/features/cuidado/CareWorkbench';
import { t } from '@/i18n';

export const metadata = { title: t.care.title };

export default async function Page({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  const { aba } = await searchParams;
  return <CareWorkbench initialTab={isCareTab(aba) ? aba : 'tarefas'} />;
}
