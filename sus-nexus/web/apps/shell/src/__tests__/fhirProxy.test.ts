// @vitest-environment node
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { resolveFhirRoute } from '@/lib/fhir/allowlist';

const { cookieStore } = vi.hoisted(() => ({ cookieStore: new Map<string, string>() }));
vi.mock('next/headers', () => ({
  cookies: () =>
    Promise.resolve({
      get: (name: string) =>
        cookieStore.has(name) ? { name, value: cookieStore.get(name) } : undefined,
    }),
}));

const CPF = '52998224725';
const CNS = '700000000000005';
const bundle = {
  resourceType: 'Bundle',
  type: 'searchset',
  total: 2,
  entry: [
    {
      resource: {
        resourceType: 'Patient',
        id: '01HZX4Y5K6M7N8P9Q0R1S2T3U4',
        identifier: [
          { system: 'http://rnds.saude.gov.br/fhir/r4/NamingSystem/cpf', value: CPF },
          { system: 'http://rnds.saude.gov.br/fhir/r4/NamingSystem/cns', value: CNS },
        ],
      },
    },
    {
      resource: {
        resourceType: 'Observation',
        id: 'obs-1',
        note: [{ text: `CPF informado: 529.982.247-25` }],
        valueQuantity: { value: 126, unit: 'mg/dL' },
      },
    },
  ],
};

const params = (path: string[]) => ({ params: Promise.resolve({ path }) });
const PATH = ['Patient', '01HZX4Y5K6M7N8P9Q0R1S2T3U4', '$everything'];

describe('proxy BFF /api/fhir/r4 → FHIR Gateway', () => {
  const fetchSpy = vi.fn<typeof fetch>();
  beforeEach(() => {
    vi.stubEnv('NEXT_PUBLIC_API_MOCK', 'false');
    vi.stubEnv('FHIR_GATEWAY_URL', 'http://fhir.test:8081');
    cookieStore.clear();
    fetchSpy.mockReset();
    vi.stubGlobal('fetch', fetchSpy);
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.unstubAllEnvs();
  });

  it('encaminha Bearer, tenant e finalidade e mascara CPF/CNS antes do navegador', async () => {
    cookieStore.set('sus-nexus.purpose', 'care_coordination');
    fetchSpy.mockResolvedValue(
      new Response(JSON.stringify(bundle), {
        status: 200,
        headers: { 'content-type': 'application/fhir+json' },
      }),
    );
    const { GET } = await import('@/app/api/fhir/r4/[...path]/route');
    const res = await GET(
      new Request(
        'http://localhost:3000/api/fhir/r4/Patient/01HZX4Y5K6M7N8P9Q0R1S2T3U4/$everything?_count=20&_type=Patient,Observation',
      ),
      params(PATH),
    );
    expect(res.status).toBe(200);
    const [url, init] = fetchSpy.mock.calls[0]!;
    expect(url as string).toBe(
      'http://fhir.test:8081/fhir/r4/Patient/01HZX4Y5K6M7N8P9Q0R1S2T3U4/$everything?_count=20&_type=Patient%2CObservation',
    );
    const h = new Headers(init?.headers);
    expect(h.get('authorization')).toBe('Bearer mock-access-token');
    expect(h.get('x-tenant-id')).toBe('ibge_3143302');
    expect(h.get('x-purpose-of-use')).toBe('care_coordination');
    const text = await res.text();
    expect(text).not.toContain(CPF);
    expect(text).not.toContain(CNS);
    expect(text).not.toContain('529.982.247-25');
    expect(text).not.toContain('mock-access-token');
    expect(text).toContain('***.***.***-25');
    expect(text).toContain('*** **** **** 0005');
    expect(text).toContain('"value":126');
  });

  it('exige finalidade de acesso', async () => {
    const { GET } = await import('@/app/api/fhir/r4/[...path]/route');
    const res = await GET(
      new Request('http://localhost:3000/api/fhir/r4/Patient/abc/$everything'),
      params(['Patient', 'abc', '$everything']),
    );
    expect(res.status).toBe(400);
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it('recusa rotas e parâmetros fora da lista', async () => {
    cookieStore.set('sus-nexus.purpose', 'care_coordination');
    const { GET } = await import('@/app/api/fhir/r4/[...path]/route');
    const bad: [string[], string][] = [
      [['Patient'], ''],
      [['Patient', 'abc'], ''],
      [['AuditEvent'], '?patient=Patient/abc'],
      [['Patient', '..', '$everything'], ''],
      [PATH, '?_type=Binary'],
      [PATH, '?_count=9999'],
      [PATH, '?name=silva'],
      [PATH, '?_cursor=a&_cursor=b'],
    ];
    for (const [path, qs] of bad) {
      const res = await GET(
        new Request(`http://localhost:3000/api/fhir/r4/${path.join('/')}${qs}`),
        params(path),
      );
      expect([400, 404]).toContain(res.status);
      expect(((await res.json()) as { resourceType: string }).resourceType).toBe(
        'OperationOutcome',
      );
    }
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it('502 OperationOutcome com gateway fora', async () => {
    cookieStore.set('sus-nexus.purpose', 'care_coordination');
    fetchSpy.mockRejectedValue(new Error('ECONNREFUSED'));
    vi.spyOn(console, 'error').mockImplementation(() => {});
    const { GET } = await import('@/app/api/fhir/r4/[...path]/route');
    const res = await GET(
      new Request('http://localhost:3000/api/fhir/r4/Patient/abc/$everything'),
      params(['Patient', 'abc', '$everything']),
    );
    expect(res.status).toBe(502);
  });

  it('allowlist reconstrói a query só com valores validados', () => {
    const r = resolveFhirRoute(PATH, new URLSearchParams('_cursor=MTA%3D&_count=50'));
    expect(r).toEqual({
      ok: true,
      path: `Patient/${PATH[1]}/$everything`,
      search: new URLSearchParams('_cursor=MTA=&_count=50'),
    });
  });
});
