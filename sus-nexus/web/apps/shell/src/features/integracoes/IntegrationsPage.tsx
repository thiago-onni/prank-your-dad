'use client';

import { usePathname, useRouter, useSearchParams } from 'next/navigation';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@sus-nexus/design-system';
import { PageHeader } from '@/components/PageHeader';
import { t } from '@/i18n';
import { ConnectorsPanel } from './ConnectorsPanel';
import { DlqPanel } from './DlqPanel';
import { MessagesPanel } from './MessagesPanel';
import { ReconciliationPanel } from './ReconciliationPanel';

const TABS = ['conectores', 'mensagens', 'dlq', 'reconciliacao'] as const;
type Tab = (typeof TABS)[number];

export function IntegrationsPage() {
  const params = useSearchParams();
  const router = useRouter();
  const pathname = usePathname();
  const tabParam = params.get('tab');
  const tab: Tab = TABS.includes(tabParam as Tab) ? (tabParam as Tab) : 'conectores';
  const connector = params.get('connector') ?? undefined;

  return (
    <>
      <PageHeader title={t.integrations.title} description={t.integrations.description} />
      <Tabs value={tab} onValueChange={(v) => router.replace(`${pathname}?tab=${v}`)}>
        <TabsList aria-label={t.integrations.title}>
          <TabsTrigger value="conectores">{t.integrations.connectors}</TabsTrigger>
          <TabsTrigger value="mensagens">{t.integrations.messages}</TabsTrigger>
          <TabsTrigger value="dlq">{t.integrations.dlq}</TabsTrigger>
          <TabsTrigger value="reconciliacao">{t.integrations.reconciliation}</TabsTrigger>
        </TabsList>
        <TabsContent value="conectores">
          <ConnectorsPanel />
        </TabsContent>
        <TabsContent value="mensagens">
          <MessagesPanel key={connector ?? 'all'} initialConnector={connector} />
        </TabsContent>
        <TabsContent value="dlq">
          <DlqPanel />
        </TabsContent>
        <TabsContent value="reconciliacao">
          <ReconciliationPanel />
        </TabsContent>
      </Tabs>
    </>
  );
}
