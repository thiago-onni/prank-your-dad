/**
 * Lista de rotas do proxy BFF → FHIR Gateway. Apenas leitura do compartimento do paciente:
 * `Patient/{id}/$everything` com `_type` (whitelist), `_count` e `_cursor`. Qualquer outro caminho
 * ou parâmetro é recusado; a query encaminhada é reconstruída a partir dos valores validados.
 */
export const FHIR_EVERYTHING_TYPES = [
  'Patient',
  'Encounter',
  'Appointment',
  'ServiceRequest',
  'Task',
  'Condition',
  'CarePlan',
  'Observation',
  'DiagnosticReport',
  'DocumentReference',
] as const;

/** Id FHIR (`[A-Za-z0-9\-.]{1,64}`) sem segmentos `.`/`..` (evita normalização de caminho). */
const FHIR_ID_RE = /^(?!\.{1,2}$)(?!.*\.\.)[A-Za-z0-9\-.]{1,64}$/;
const CURSOR_RE = /^[A-Za-z0-9_\-.~=]{1,512}$/;

export type FhirRouteResult =
  | { ok: true; path: string; search: URLSearchParams }
  | { ok: false; status: 400 | 404; detail: string };

export function resolveFhirRoute(segments: string[], search: URLSearchParams): FhirRouteResult {
  if (
    segments.length !== 3 ||
    segments[0] !== 'Patient' ||
    segments[2] !== '$everything' ||
    !FHIR_ID_RE.test(segments[1] ?? '')
  ) {
    return { ok: false, status: 404, detail: 'Rota FHIR não permitida.' };
  }
  const out = new URLSearchParams();
  for (const key of new Set(search.keys())) {
    const values = search.getAll(key);
    if (values.length !== 1)
      return { ok: false, status: 400, detail: `Parâmetro repetido: ${key}` };
    const value = values[0]!;
    switch (key) {
      case '_type': {
        const types = value.split(',');
        if (!types.every((t) => (FHIR_EVERYTHING_TYPES as readonly string[]).includes(t)))
          return { ok: false, status: 400, detail: 'Tipo de recurso não permitido.' };
        out.set('_type', types.join(','));
        break;
      }
      case '_count': {
        const n = Number(value);
        if (!/^[0-9]{1,3}$/.test(value) || n < 1 || n > 200)
          return { ok: false, status: 400, detail: '_count deve estar entre 1 e 200.' };
        out.set('_count', String(n));
        break;
      }
      case '_cursor':
        if (!CURSOR_RE.test(value)) return { ok: false, status: 400, detail: 'Cursor inválido.' };
        out.set('_cursor', value);
        break;
      default:
        return { ok: false, status: 400, detail: `Parâmetro não permitido: ${key.slice(0, 40)}` };
    }
  }
  return { ok: true, path: `Patient/${segments[1]}/$everything`, search: out };
}
