import { HospitalPage } from '@/features/hospital/HospitalPage';
import { t } from '@/i18n';

export const metadata = { title: t.hospital.title };

export default async function Page({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  const { cidadao } = await searchParams;
  return <HospitalPage citizenId={typeof cidadao === 'string' && cidadao ? cidadao : undefined} />;
}
