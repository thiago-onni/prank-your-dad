import { ProductionBatchDetail } from '@/features/producao/ProductionBatchDetail';
import { t } from '@/i18n';

export const metadata = { title: t.production.batchDetailTitle };

export default async function Page({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return <ProductionBatchDetail batchId={id} />;
}
