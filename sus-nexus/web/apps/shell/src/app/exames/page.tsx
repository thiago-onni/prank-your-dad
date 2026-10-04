import { ExamsPage } from '@/features/exames/ExamsPage';
import { t } from '@/i18n';

export const metadata = { title: t.exams.title };

export default function Page() {
  return <ExamsPage />;
}
