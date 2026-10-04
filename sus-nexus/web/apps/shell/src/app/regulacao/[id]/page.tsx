import { RegulationRequestDetail } from '@/features/regulacao/RegulationRequestDetail';
import { t } from '@/i18n';

export const metadata = { title: t.regulation.detailTitle };

export default async function Page({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return <RegulationRequestDetail requestId={id} />;
}
