'use client';

import { useCitizen, useHealthUnits } from '@sus-nexus/api-client';
import { Skeleton } from '@sus-nexus/design-system';
import { CitizenHeader } from '@sus-nexus/domain-components';
import { describeError } from '@/lib/problem';
import { t } from '@/i18n';

/** Cabeçalho do cidadão (identificadores sempre mascarados) para telas de detalhe. */
export function CitizenBlock({ citizenId }: { citizenId: string }) {
  const citizen = useCitizen(citizenId);
  const units = useHealthUnits({ limit: 100 });
  if (citizen.isLoading) return <Skeleton className="h-32 w-full" label={t.app.loading} />;
  if (citizen.error || !citizen.data) {
    const { message } = describeError(citizen.error);
    return (
      <p role="alert" className="rounded-md border border-danger bg-danger-subtle p-3 text-sm">
        {t.citizen.notFound} {message}
      </p>
    );
  }
  return (
    <CitizenHeader
      citizen={citizen.data}
      healthUnitName={units.data?.items.find((u) => u.cnes === citizen.data.health_unit_cnes)?.name}
    />
  );
}
