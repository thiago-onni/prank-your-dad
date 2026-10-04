'use client';

import { Tabs, TabsContent, TabsList, TabsTrigger } from '@sus-nexus/design-system';
import { PageHeader } from '@/components/PageHeader';
import { PurposeRequired } from '@/components/PurposeRequired';
import { t } from '@/i18n';
import { ActiveSearchTab } from './ActiveSearchTab';
import { CareLinesTab } from './CareLinesTab';
import { CareTasksTab } from './CareTasksTab';

export const CARE_TABS = ['tarefas', 'busca-ativa', 'linhas'] as const;
export type CareTab = (typeof CARE_TABS)[number];

export function isCareTab(value: unknown): value is CareTab {
  return typeof value === 'string' && (CARE_TABS as readonly string[]).includes(value);
}

/** Mantém a aba no endereço (`?aba=`) para atalhos e recarga, sem navegação. */
function rememberTab(tab: string) {
  if (typeof window === 'undefined') return;
  const url = new URL(window.location.href);
  url.searchParams.set('aba', tab);
  window.history.replaceState(window.history.state, '', url);
}

/** Workbench de Cuidado: tarefas (v1), busca ativa (lacunas) e linhas de cuidado. */
export function CareWorkbench({ initialTab = 'tarefas' }: { initialTab?: CareTab }) {
  return (
    <PurposeRequired>
      <PageHeader title={t.care.title} description={t.care.description} />
      <Tabs defaultValue={initialTab} onValueChange={rememberTab}>
        <TabsList aria-label={t.care.tabs}>
          <TabsTrigger value="tarefas">{t.care.tabTasks}</TabsTrigger>
          <TabsTrigger value="busca-ativa">{t.activeSearch.tab}</TabsTrigger>
          <TabsTrigger value="linhas">{t.careLines.tab}</TabsTrigger>
        </TabsList>
        <TabsContent value="tarefas">
          <CareTasksTab />
        </TabsContent>
        <TabsContent value="busca-ativa">
          <ActiveSearchTab />
        </TabsContent>
        <TabsContent value="linhas">
          <CareLinesTab />
        </TabsContent>
      </Tabs>
    </PurposeRequired>
  );
}
