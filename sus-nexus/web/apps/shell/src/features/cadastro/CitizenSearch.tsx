'use client';

import Link from 'next/link';
import { useState, type FormEvent } from 'react';
import { useCitizenSearch, type CitizenSummary } from '@sus-nexus/api-client';
import {
  Badge,
  Button,
  CursorPagination,
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
import {
  formatAge,
  formatDate,
  IdentityConfidenceBadge,
  registrationStateLabels,
} from '@sus-nexus/domain-components';
import { Search } from 'lucide-react';
import { PurposeRequired } from '@/components/PurposeRequired';
import { QueryState } from '@/components/QueryState';
import {
  isValidCnsLength,
  isValidCpfLength,
  maskCnsInput,
  maskCpfInput,
  toIdentifierParam,
} from '@/lib/masks';
import { t } from '@/i18n';

type Mode = 'name' | 'cns' | 'cpf';

interface Criteria {
  q?: string;
  identifier?: string;
  birthdate?: string;
}

export function CitizenSearch() {
  const [mode, setMode] = useState<Mode>('name');
  const [value, setValue] = useState('');
  const [birthdate, setBirthdate] = useState('');
  const [error, setError] = useState<string | undefined>();
  const [criteria, setCriteria] = useState<Criteria>({});
  const [cursors, setCursors] = useState<string[]>([]);

  const query = useCitizenSearch({ ...criteria, cursor: cursors[cursors.length - 1], limit: 20 });

  const onSubmit = (e: FormEvent) => {
    e.preventDefault();
    setCursors([]);
    if (mode === 'name') {
      if (value.trim().length < 3) return setError(t.registry.minName);
      setError(undefined);
      return setCriteria({ q: value.trim(), birthdate: birthdate || undefined });
    }
    if (mode === 'cpf') {
      if (!isValidCpfLength(value)) return setError(t.registry.invalidCpf);
      setError(undefined);
      return setCriteria({
        identifier: toIdentifierParam('CPF', value),
        birthdate: birthdate || undefined,
      });
    }
    if (!isValidCnsLength(value)) return setError(t.registry.invalidCns);
    setError(undefined);
    setCriteria({ identifier: toIdentifierParam('CNS', value), birthdate: birthdate || undefined });
  };

  const onValueChange = (raw: string) => {
    setValue(mode === 'cpf' ? maskCpfInput(raw) : mode === 'cns' ? maskCnsInput(raw) : raw);
  };

  const labels: Record<Mode, string> = {
    name: t.registry.nameLabel,
    cns: t.registry.cnsLabel,
    cpf: t.registry.cpfLabel,
  };

  return (
    <PurposeRequired>
      <form
        onSubmit={onSubmit}
        className="grid gap-3 md:grid-cols-[180px_1fr_200px_auto] md:items-end"
        aria-label={t.registry.search}
      >
        <Select
          label={t.registry.searchBy}
          value={mode}
          onValueChange={(v) => {
            setMode(v as Mode);
            setValue('');
            setError(undefined);
          }}
          options={[
            { value: 'name', label: t.registry.byName },
            { value: 'cns', label: t.registry.byCns },
            { value: 'cpf', label: t.registry.byCpf },
          ]}
        />
        <Input
          label={labels[mode]}
          value={value}
          onChange={(e) => onValueChange(e.target.value)}
          inputMode={mode === 'name' ? 'text' : 'numeric'}
          autoComplete="off"
          error={error}
          required
        />
        <Input
          label={t.registry.birthdate}
          type="date"
          value={birthdate}
          onChange={(e) => setBirthdate(e.target.value)}
        />
        <Button type="submit" loading={query.isFetching}>
          <Search aria-hidden="true" className="h-4 w-4" />
          {t.app.search}
        </Button>
      </form>

      <section aria-labelledby="results" className="mt-6">
        <h2 id="results" className="mb-2 text-xl font-semibold">
          {t.registry.results}
        </h2>
        {!criteria.q && !criteria.identifier ? (
          <EmptyState title={t.registry.typeToSearch} />
        ) : (
          <QueryState
            isLoading={query.isLoading}
            error={query.error}
            data={query.data}
            onRetry={() => void query.refetch()}
          >
            {(page) =>
              page.items.length === 0 ? (
                <EmptyState title={t.registry.noResults} />
              ) : (
                <div className="flex flex-col gap-3">
                  <ResultsTable items={page.items} />
                  <CursorPagination
                    nextCursor={page.next_cursor}
                    hasPrevious={cursors.length > 0}
                    isLoading={query.isFetching}
                    onNext={(c) => setCursors((p) => [...p, c])}
                    onPrevious={() => setCursors((p) => p.slice(0, -1))}
                  />
                </div>
              )
            }
          </QueryState>
        )}
      </section>
    </PurposeRequired>
  );
}

function ResultsTable({ items }: { items: CitizenSummary[] }) {
  return (
    <Table aria-label={t.registry.results}>
      <TableHead>
        <TableRow>
          <TableHeaderCell>Nome</TableHeaderCell>
          <TableHeaderCell>Nascimento</TableHeaderCell>
          <TableHeaderCell>Mãe</TableHeaderCell>
          <TableHeaderCell>CNS</TableHeaderCell>
          <TableHeaderCell>CPF</TableHeaderCell>
          <TableHeaderCell>Situação</TableHeaderCell>
          <TableHeaderCell>Identidade</TableHeaderCell>
          <TableHeaderCell>UBS</TableHeaderCell>
        </TableRow>
      </TableHead>
      <TableBody>
        {items.map((c) => {
          const state = registrationStateLabels[c.registration_state];
          const cns = c.identifiers.find((i) => i.system === 'CNS');
          const cpf = c.identifiers.find((i) => i.system === 'CPF');
          return (
            <TableRow key={c.id}>
              <TableHeaderCell scope="row" className="font-medium">
                <Link
                  href={`/cidadaos/${c.id}`}
                  className="text-primary-fg-subtle hover:underline"
                  aria-label={`${t.registry.openCitizen}: ${c.display_name}`}
                >
                  {c.display_name}
                </Link>
              </TableHeaderCell>
              <TableCell>
                {formatDate(c.birthdate)}{' '}
                <span className="text-fg-muted">({formatAge(c.birthdate)})</span>
              </TableCell>
              <TableCell>{c.mother_name_masked ?? '—'}</TableCell>
              <TableCell className="font-mono">{cns?.value_masked ?? '—'}</TableCell>
              <TableCell className="font-mono">{cpf?.value_masked ?? '—'}</TableCell>
              <TableCell>
                <Badge tone={state.tone}>{state.label}</Badge>
              </TableCell>
              <TableCell>
                {c.identity_confidence ? (
                  <IdentityConfidenceBadge confidence={c.identity_confidence} />
                ) : (
                  '—'
                )}
              </TableCell>
              <TableCell className="font-mono text-xs">{c.health_unit_cnes ?? '—'}</TableCell>
            </TableRow>
          );
        })}
      </TableBody>
    </Table>
  );
}
