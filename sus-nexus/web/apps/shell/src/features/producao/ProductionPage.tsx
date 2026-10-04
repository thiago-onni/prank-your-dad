'use client';

import { Info } from 'lucide-react';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@sus-nexus/design-system';
import { PageHeader } from '@/components/PageHeader';
import { PurposeRequired } from '@/components/PurposeRequired';
import { t } from '@/i18n';
import { BatchesTab } from './BatchesTab';
import { IssuesTab } from './IssuesTab';
import { PanelTab } from './PanelTab';
import { RecordsTab } from './RecordsTab';
import { hasAnyProductionAction, useProductionPermissions } from './permissions';

export const PRODUCTION_TABS = ['painel', 'registros', 'pendencias', 'lotes'] as const;
export type ProductionTab = (typeof PRODUCTION_TABS)[number];

export function isProductionTab(value: unknown): value is ProductionTab {
  return typeof value === 'string' && (PRODUCTION_TABS as readonly string[]).includes(value);
}

/** Mantém a aba no endereço (`?aba=`) para atalhos e recarga, sem navegação. */
function rememberTab(tab: string) {
  if (typeof window === 'undefined') return;
  const url = new URL(window.location.href);
  url.searchParams.set('aba', tab);
  window.history.replaceState(window.history.state, '', url);
}

/** Produção e pré-auditoria (Fase 4): painel, registros, pendências e lotes. */
export function ProductionPage({ initialTab = 'painel' }: { initialTab?: ProductionTab }) {
  const perms = useProductionPermissions();
  return (
    <PurposeRequired>
      <PageHeader title={t.production.title} description={t.production.description} />
      {!hasAnyProductionAction(perms) ? (
        <p
          role="note"
          className="mb-4 flex items-start gap-2 rounded-md border border-border bg-bg-muted p-3 text-sm"
        >
          <Info aria-hidden="true" className="mt-0.5 h-4 w-4 shrink-0" />
          {t.production.readOnlyNotice}
        </p>
      ) : null}
      <Tabs defaultValue={initialTab} onValueChange={rememberTab}>
        <TabsList aria-label={t.production.tabs}>
          <TabsTrigger value="painel">{t.production.tabPanel}</TabsTrigger>
          <TabsTrigger value="registros">{t.production.tabRecords}</TabsTrigger>
          <TabsTrigger value="pendencias">{t.production.tabIssues}</TabsTrigger>
          <TabsTrigger value="lotes">{t.production.tabBatches}</TabsTrigger>
        </TabsList>
        <TabsContent value="painel">
          <PanelTab />
        </TabsContent>
        <TabsContent value="registros">
          <RecordsTab />
        </TabsContent>
        <TabsContent value="pendencias">
          <IssuesTab />
        </TabsContent>
        <TabsContent value="lotes">
          <BatchesTab />
        </TabsContent>
      </Tabs>
    </PurposeRequired>
  );
}
