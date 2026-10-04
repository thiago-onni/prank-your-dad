import { ProductionRecordDetail } from '@/features/producao/ProductionRecordDetail';
import { t } from '@/i18n';

export const metadata = { title: t.production.recordDetailTitle };

export default async function Page({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return <ProductionRecordDetail recordId={id} />;
}
