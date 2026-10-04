import { ProductionPage, isProductionTab } from '@/features/producao/ProductionPage';
import { t } from '@/i18n';

export const metadata = { title: t.production.title };

export default async function Page({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  const { aba } = await searchParams;
  return <ProductionPage initialTab={isProductionTab(aba) ? aba : 'painel'} />;
}
