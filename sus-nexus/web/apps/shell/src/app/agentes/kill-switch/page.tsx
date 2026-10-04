import { KillSwitchPage } from '@/features/agentes/KillSwitchPage';
import { t } from '@/i18n';

export const metadata = { title: t.agents.ks.title };

/**
 * Restrito a DPO/admin. A verificação de papel na UI (`KillSwitchPage`) é apenas usabilidade:
 * o ai-service aplica `admin_roles` sobre o Bearer encaminhado pelo proxy `/api/ai`.
 */
export default function Page() {
  return <KillSwitchPage />;
}
