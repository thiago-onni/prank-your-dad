import { HospitalEpisodeDetail } from '@/features/hospital/HospitalEpisodeDetail';
import { t } from '@/i18n';

export const metadata = { title: t.hospital.detailTitle };

export default async function Page({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return <HospitalEpisodeDetail episodeId={id} />;
}
