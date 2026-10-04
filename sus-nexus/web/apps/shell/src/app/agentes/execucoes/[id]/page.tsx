import { AgentRunDetail } from '@/features/agentes/AgentRunDetail';
import { t } from '@/i18n';

export const metadata = { title: t.agents.runDetail };

export default async function Page({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return <AgentRunDetail runId={id} />;
}
