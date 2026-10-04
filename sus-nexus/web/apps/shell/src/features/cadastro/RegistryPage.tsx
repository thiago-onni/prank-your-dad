'use client';

import { usePathname, useRouter, useSearchParams } from 'next/navigation';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@sus-nexus/design-system';
import { PageHeader } from '@/components/PageHeader';
import { t } from '@/i18n';
import { CitizenSearch } from './CitizenSearch';
import { MergeQueue } from './MergeQueue';

export function RegistryPage() {
  const params = useSearchParams();
  const router = useRouter();
  const pathname = usePathname();
  const tab = params.get('tab') === 'fila' ? 'fila' : 'busca';
  return (
    <>
      <PageHeader title={t.registry.title} description={t.registry.description} />
      <Tabs value={tab} onValueChange={(v) => router.replace(`${pathname}?tab=${v}`)}>
        <TabsList aria-label={t.registry.title}>
          <TabsTrigger value="busca">{t.registry.search}</TabsTrigger>
          <TabsTrigger value="fila">{t.registry.reviewQueue}</TabsTrigger>
        </TabsList>
        <TabsContent value="busca">
          <CitizenSearch />
        </TabsContent>
        <TabsContent value="fila">
          <MergeQueue />
        </TabsContent>
      </Tabs>
    </>
  );
}
