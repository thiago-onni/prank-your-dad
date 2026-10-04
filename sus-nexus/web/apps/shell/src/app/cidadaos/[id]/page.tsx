import { CitizenPage } from '@/features/cidadaos/CitizenPage';
import { t } from '@/i18n';

export const metadata = { title: t.citizen.title };

export default async function Page({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return <CitizenPage citizenId={id} />;
}
