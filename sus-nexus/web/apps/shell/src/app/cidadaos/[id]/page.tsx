import { CitizenPage, isCitizenTab } from '@/features/cidadaos/CitizenPage';
import { t } from '@/i18n';

export const metadata = { title: t.citizen.title };

export default async function Page({
  params,
  searchParams,
}: {
  params: Promise<{ id: string }>;
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  const [{ id }, { aba }] = await Promise.all([params, searchParams]);
  return <CitizenPage citizenId={id} initialTab={isCitizenTab(aba) ? aba : 'resumo'} />;
}
