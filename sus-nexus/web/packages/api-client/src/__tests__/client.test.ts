import {
  createCoreClient,
  CoreApiError,
  HEADER_CORRELATION,
  HEADER_PURPOSE,
  unwrap,
} from '../client';

describe('createCoreClient', () => {
  it('injeta X-Purpose-Of-Use e X-Correlation-Id', async () => {
    const fetchMock = vi.fn((input: Request) => {
      expect(input.headers.get(HEADER_PURPOSE)).toBe('care_coordination');
      expect(input.headers.get(HEADER_CORRELATION)).toBe('corr-123');
      return new Response(JSON.stringify([]), {
        status: 200,
        headers: { 'content-type': 'application/json' },
      });
    });
    const client = createCoreClient({
      baseUrl: 'http://core.test',
      fetch: fetchMock as unknown as typeof fetch,
      getPurpose: () => 'care_coordination',
      getCorrelationId: () => 'corr-123',
    });
    const result = await client.GET('/api/v1/integration/connectors');
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(result.data).toEqual([]);
  });

  it('não sobrescreve finalidade explícita da requisição', async () => {
    const fetchMock = vi.fn((input: Request) => {
      expect(input.headers.get(HEADER_PURPOSE)).toBe('regulation');
      return new Response(JSON.stringify({ items: [] }), {
        status: 200,
        headers: { 'content-type': 'application/json' },
      });
    });
    const client = createCoreClient({
      baseUrl: 'http://core.test',
      fetch: fetchMock as unknown as typeof fetch,
      getPurpose: () => 'care_coordination',
    });
    await client.GET('/api/v1/citizens', {
      params: { query: { q: 'maria' }, header: { 'X-Purpose-Of-Use': 'regulation' } },
    });
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('unwrap lança CoreApiError com problem+json', async () => {
    const fetchMock = vi.fn(
      () =>
        new Response(
          JSON.stringify({ title: 'Acesso negado', status: 403, correlation_id: 'c-1' }),
          { status: 403, headers: { 'content-type': 'application/problem+json' } },
        ),
    );
    const client = createCoreClient({
      baseUrl: 'http://core.test',
      fetch: fetchMock as unknown as typeof fetch,
    });
    const result = await client.GET('/api/v1/integration/connectors');
    expect(() => unwrap(result)).toThrowError(CoreApiError);
    try {
      unwrap(result);
    } catch (e: unknown) {
      const err = e as CoreApiError;
      expect(err.status).toBe(403);
      expect(err.correlationId).toBe('c-1');
      expect(err.message).toBe('Acesso negado');
    }
  });
});
