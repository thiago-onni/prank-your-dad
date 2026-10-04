import { featuredShortcuts, homeShortcuts, visibleNavItems } from '@/components/layout/nav';

describe('navegação e atalhos por papel (Fase 3)', () => {
  const hrefs = (items: { href: string }[]) => items.map((i) => i.href);

  it('profissional da APS e ACS: atalho para a busca ativa', () => {
    expect(hrefs(featuredShortcuts(['acs']))).toEqual(['/cuidado?aba=busca-ativa']);
    expect(hrefs(featuredShortcuts(['profissional_aps']))).toEqual(['/cuidado?aba=busca-ativa']);
    expect(hrefs(featuredShortcuts(['enfermagem']))).toContain('/cuidado?aba=busca-ativa');
  });

  it('gestor: protocolos; profissional hospitalar: /hospital', () => {
    expect(hrefs(featuredShortcuts(['gestor']))).toEqual(['/admin/protocolos']);
    expect(hrefs(featuredShortcuts(['profissional_hospitalar']))).toEqual(['/hospital']);
    expect(hrefs(visibleNavItems(['profissional_hospitalar']))).toEqual(['/', '/hospital']);
    expect(hrefs(visibleNavItems(['acs']))).not.toContain('/admin/protocolos');
    expect(hrefs(homeShortcuts(['gestor']))).toContain('/admin/protocolos');
  });
});
