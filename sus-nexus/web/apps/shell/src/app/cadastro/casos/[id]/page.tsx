import { MergeCaseDetail } from '@/features/cadastro/MergeCaseDetail';
import { t } from '@/i18n';

export const metadata = { title: t.registry.caseDetail };

export default async function Page({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return <MergeCaseDetail caseId={id} />;
}
