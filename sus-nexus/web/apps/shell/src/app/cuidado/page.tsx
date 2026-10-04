import { CareWorkbench } from '@/features/cuidado/CareWorkbench';
import { t } from '@/i18n';

export const metadata = { title: t.care.title };

export default function Page() {
  return <CareWorkbench />;
}
