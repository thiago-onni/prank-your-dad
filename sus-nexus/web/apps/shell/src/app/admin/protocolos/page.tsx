import { ProtocolsPage } from '@/features/admin/ProtocolsPage';
import { t } from '@/i18n';

export const metadata = { title: t.protocols.title };

export default function Page() {
  return <ProtocolsPage />;
}
