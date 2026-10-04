import { RegulationCockpit } from '@/features/regulacao/RegulationCockpit';
import { t } from '@/i18n';

export const metadata = { title: t.regulation.title };

export default function Page() {
  return <RegulationCockpit />;
}
