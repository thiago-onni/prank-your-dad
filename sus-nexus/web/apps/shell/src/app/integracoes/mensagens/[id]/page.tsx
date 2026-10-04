import { MessageDetail } from '@/features/integracoes/MessageDetail';
import { t } from '@/i18n';

export const metadata = { title: t.integrations.messageDetail };

export default async function Page({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return <MessageDetail messageId={id} />;
}
