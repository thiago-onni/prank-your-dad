'use client';

import { useMemo, useState } from 'react';
import { useProviderCapacity } from '@sus-nexus/api-client';
import {
  EmptyState,
  Input,
  Select,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeaderCell,
  TableRow,
} from '@sus-nexus/design-system';
import { formatDateTime } from '@sus-nexus/domain-components';
import { PageHeader } from '@/components/PageHeader';
import { QueryState } from '@/components/QueryState';
import { t } from '@/i18n';
import { RegulationSubnav } from './RegulationSubnav';

export function CapacityPage() {
  const [provider, setProvider] = useState('');
  const [service, setService] = useState('');
  const [competence, setCompetence] = useState('');
  const all = useProviderCapacity({ limit: 200 });
  const query = useProviderCapacity({
    provider_cnes: provider,
    service_code: service,
    competence,
    limit: 200,
  });

  const options = useMemo(() => {
    const prov = new Map<string, string>();
    const svc = new Set<string>();
    for (const c of all.data?.items ?? []) {
      prov.set(c.provider_cnes, c.provider_name ?? c.provider_cnes);
      svc.add(c.service_code);
    }
    return {
      providers: [...prov.entries()].map(([value, label]) => ({ value, label: `${label} (${value})` })),
      services: [...svc].sort().map((s) => ({ value: s, label: s })),
    };
  }, [all.data]);

  return (
    <>
      <PageHeader title={t.regulation.capacityTitle} description={t.regulation.capacityDescription} />
      <RegulationSubnav />
      <fieldset className="mb-4 grid gap-3 sm:grid-cols-3">
        <legend className="sr-only">{t.regulation.filters}</legend>
        <Select
          label={t.regulation.provider}
          value={provider || 'all'}
          onValueChange={(v) => setProvider(v === 'all' ? '' : v)}
          options={[{ value: 'all', label: t.app.all }, ...options.providers]}
        />
        <Select
          label={t.regulation.serviceCode}
          value={service || 'all'}
          onValueChange={(v) => setService(v === 'all' ? '' : v)}
          options={[{ value: 'all', label: t.app.all }, ...options.services]}
        />
        <Input
          label={t.regulation.competence}
          type="month"
          value={competence}
          onChange={(e) => setCompetence(e.target.value)}
        />
      </fieldset>
      <QueryState
        isLoading={query.isLoading}
        error={query.error}
        data={query.data}
        onRetry={() => void query.refetch()}
      >
        {(page) =>
          page.items.length === 0 ? (
            <EmptyState title={t.regulation.noCapacity} />
          ) : (
            <Table aria-label={t.regulation.capacityTitle}>
              <TableHead>
                <TableRow>
                  <TableHeaderCell>{t.regulation.provider}</TableHeaderCell>
                  <TableHeaderCell>{t.regulation.serviceCode}</TableHeaderCell>
                  <TableHeaderCell>{t.regulation.competence}</TableHeaderCell>
                  <TableHeaderCell>{t.regulation.offered}</TableHeaderCell>
                  <TableHeaderCell>{t.regulation.used}</TableHeaderCell>
                  <TableHeaderCell>{t.regulation.available}</TableHeaderCell>
                  <TableHeaderCell>{t.regulation.updatedAt}</TableHeaderCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {page.items.map((c) => {
                  const pct = c.offered > 0 ? Math.round(((c.used ?? 0) / c.offered) * 100) : 0;
                  const available = c.available ?? Math.max(0, c.offered - (c.used ?? 0));
                  return (
                    <TableRow key={`${c.provider_cnes}-${c.service_code}-${c.competence}`}>
                      <TableHeaderCell scope="row" className="font-medium">
                        {c.provider_name ?? c.provider_cnes}
                        <span className="block text-xs font-normal text-fg-muted">CNES {c.provider_cnes}</span>
                      </TableHeaderCell>
                      <TableCell className="font-mono text-xs">{c.service_code}</TableCell>
                      <TableCell>{c.competence}</TableCell>
                      <TableCell>{c.offered}</TableCell>
                      <TableCell>
                        {c.used ?? '—'}
                        <span className="block text-xs text-fg-muted">{pct}%</span>
                      </TableCell>
                      <TableCell className={available === 0 ? 'font-semibold text-danger-fg-subtle' : ''}>
                        {available}
                      </TableCell>
                      <TableCell>{formatDateTime(c.updated_at)}</TableCell>
                    </TableRow>
                  );
                })}
              </TableBody>
            </Table>
          )
        }
      </QueryState>
    </>
  );
}
