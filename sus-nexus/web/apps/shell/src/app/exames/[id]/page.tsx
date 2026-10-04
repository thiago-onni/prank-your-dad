import { ExamOrderDetail } from '@/features/exames/ExamOrderDetail';
import { t } from '@/i18n';

export const metadata = { title: t.exams.detailTitle };

export default async function Page({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return <ExamOrderDetail orderId={id} />;
}
