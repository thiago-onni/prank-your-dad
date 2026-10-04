import { AgentsCockpit } from '@/features/agentes/AgentsCockpit';
import { t } from '@/i18n';

export const metadata = { title: t.agents.title };

const TABS = ['agentes', 'execucoes', 'aprovacoes'] as const;
type Tab = (typeof TABS)[number];

export default async function Page({
  searchParams,
}: {
  searchParams: Promise<{ aba?: string | string[] }>;
}) {
  const { aba } = await searchParams;
  const requested = Array.isArray(aba) ? aba[0] : aba;
  const initialTab: Tab = TABS.includes(requested as Tab) ? (requested as Tab) : 'agentes';
  return <AgentsCockpit initialTab={initialTab} />;
}
