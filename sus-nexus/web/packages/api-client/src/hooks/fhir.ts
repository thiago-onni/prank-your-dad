import { useInfiniteQuery } from '@tanstack/react-query';
import { bffGetJson } from '../bff';
import { coreKeys } from '../keys';
import { useBffBaseUrl, useCurrentPurpose } from '../provider';

/** Recurso FHIR genérico (JSON). */
export interface FhirResource {
  resourceType: string;
  id?: string;
  [key: string]: unknown;
}

export interface FhirBundle {
  resourceType: 'Bundle';
  type?: string;
  total?: number;
  link?: { relation: string; url: string }[];
  entry?: { fullUrl?: string; resource?: FhirResource; search?: { mode?: string } }[];
}

/**
 * Id FHIR a partir do id canônico do core: o gateway remove o prefixo `cit_` (o sublinhado não é
 * permitido em ids FHIR) — `CanonicalIds.toFhirId` no fhir-gateway.
 */
export function toFhirId(canonicalId: string): string {
  const i = canonicalId.indexOf('_');
  return i > 0 && i < 6 ? canonicalId.slice(i + 1) : canonicalId;
}

/** Cursor da próxima página a partir de `link[rel=next]` (o BFF só aceita `_cursor`). */
export function nextCursorOf(bundle: FhirBundle): string | undefined {
  const next = bundle.link?.find((l) => l.relation === 'next')?.url;
  if (!next) return undefined;
  try {
    return new URL(next, 'http://x').searchParams.get('_cursor') ?? undefined;
  } catch {
    return undefined;
  }
}

/**
 * `Patient/{id}/$everything` via BFF (`/api/fhir/r4`), com a finalidade corrente (o gateway
 * registra AuditEvent com `purposeOfEvent`). Paginação por cursor.
 */
export function usePatientEverything(
  citizenId: string,
  options: { types?: string[]; count?: number; enabled?: boolean } = {},
) {
  const base = useBffBaseUrl();
  const purpose = useCurrentPurpose();
  const fhirId = toFhirId(citizenId);
  const types = options.types?.length ? options.types.join(',') : undefined;
  const count = options.count ?? 50;
  return useInfiniteQuery({
    queryKey: coreKeys.fhirEverything(fhirId, { types, count, purpose }),
    enabled: (options.enabled ?? true) && !!purpose,
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last: FhirBundle) => nextCursorOf(last),
    queryFn: ({ pageParam, signal }) => {
      const params = new URLSearchParams({ _count: String(count) });
      if (types) params.set('_type', types);
      if (pageParam) params.set('_cursor', pageParam);
      return bffGetJson<FhirBundle>(
        `${base}/api/fhir/r4/Patient/${encodeURIComponent(fhirId)}/$everything?${params}`,
        { purpose, signal, accept: 'application/fhir+json' },
      );
    },
  });
}
