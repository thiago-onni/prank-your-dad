'use client';

import { useState } from 'react';
import { usePatientEverything, type FhirResource } from '@sus-nexus/api-client';
import { Badge, Button, Card, CardHeader, EmptyState, Select } from '@sus-nexus/design-system';
import { FHIR_TYPE_LABELS, FHIRResourceViewer, type JsonValue } from '@sus-nexus/domain-components';
import { QueryState } from '@/components/QueryState';
import { format, t } from '@/i18n';

const ALL = 'todos';
export const FHIR_TAB_TYPES = [
  'Patient',
  'Encounter',
  'Condition',
  'Observation',
  'DiagnosticReport',
  'ServiceRequest',
  'Appointment',
  'CarePlan',
  'DocumentReference',
  'Task',
] as const;

/** Aba "FHIR" do cidadão: compartimento do paciente no FHIR Gateway, via BFF. */
export function FhirTab({ citizenId }: { citizenId: string }) {
  const [type, setType] = useState<string>(ALL);
  const query = usePatientEverything(citizenId, { types: type === ALL ? undefined : [type] });
  const pages = query.data?.pages;
  const resources: FhirResource[] =
    pages?.flatMap((b) =>
      (b.entry ?? []).map((e) => e.resource).filter((r): r is FhirResource => !!r),
    ) ?? [];
  const total = pages?.[0]?.total;
  const counts = resources.reduce<Record<string, number>>((acc, r) => {
    acc[r.resourceType] = (acc[r.resourceType] ?? 0) + 1;
    return acc;
  }, {});

  return (
    <Card as="section" aria-labelledby="citizen-fhir">
      <CardHeader
        headingLevel={2}
        title={<span id="citizen-fhir">{t.fhir.title}</span>}
        description={t.fhir.description}
      />
      <Select
        label={t.fhir.filterType}
        value={type}
        onValueChange={setType}
        options={[
          { value: ALL, label: t.fhir.allTypes },
          ...FHIR_TAB_TYPES.map((ty) => ({
            value: ty,
            label: `${FHIR_TYPE_LABELS[ty] ?? ty} (${ty})`,
          })),
        ]}
        className="mb-4 max-w-sm"
      />
      <QueryState
        isLoading={query.isLoading}
        error={query.error}
        data={query.data}
        onRetry={() => void query.refetch()}
      >
        {() =>
          resources.length === 0 ? (
            <EmptyState title={t.fhir.empty} />
          ) : (
            <div className="flex flex-col gap-4">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <ul aria-label={t.fhir.byType} className="flex flex-wrap gap-2">
                  {Object.entries(counts).map(([ty, n]) => (
                    <li key={ty}>
                      <Badge tone="primary">
                        {FHIR_TYPE_LABELS[ty] ?? ty}: {n}
                      </Badge>
                    </li>
                  ))}
                </ul>
                <p className="text-sm text-fg-muted" aria-live="polite">
                  {format(t.fhir.count, { n: resources.length, total: total ?? resources.length })}
                </p>
              </div>
              <ul className="flex flex-col gap-3">
                {resources.map((r) => (
                  <li key={`${r.resourceType}/${r.id ?? ''}`}>
                    <FHIRResourceViewer
                      resource={r as Record<string, JsonValue>}
                      profile={
                        Array.isArray((r.meta as { profile?: unknown } | undefined)?.profile)
                          ? String((r.meta as { profile: unknown[] }).profile[0])
                          : undefined
                      }
                      masking="identifiers"
                      collapsibleRaw
                      initialView="json"
                      headingLevel={3}
                    />
                  </li>
                ))}
              </ul>
              {query.hasNextPage ? (
                <Button
                  variant="secondary"
                  onClick={() => void query.fetchNextPage()}
                  disabled={query.isFetchingNextPage}
                  className="self-start"
                >
                  {t.fhir.loadMore}
                </Button>
              ) : null}
            </div>
          )
        }
      </QueryState>
    </Card>
  );
}
