// @vitest-environment node
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { server } from '@/mocks/server';
import { citizens } from '@/mocks/data';

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

describe('handlers MSW', () => {
  it('busca exige finalidade e devolve identificadores mascarados', async () => {
    const noPurpose = await fetch('http://core.test/api/v1/citizens?q=maria');
    expect(noPurpose.status).toBe(400);
    const res = await fetch('http://core.test/api/v1/citizens?q=a&limit=5', {
      headers: { 'X-Purpose-Of-Use': 'care_coordination' },
    });
    const body = (await res.json()) as {
      items: { identifiers: { value_masked: string }[] }[];
      next_cursor: string | null;
    };
    expect(body.items.length).toBeGreaterThan(0);
    for (const c of body.items)
      for (const i of c.identifiers) expect(i.value_masked).not.toMatch(/^\d{11}$/);
    expect(body.next_cursor).not.toBeNull();
  });

  it('reveal exige justificativa mínima', async () => {
    const c = citizens[0]!;
    const ident = c.identifiers[0]!;
    const url = `http://core.test/api/v1/citizens/${c.id}/identifiers/${ident.id}/reveal`;
    const bad = await fetch(url, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ purpose: 'care_coordination', justification: 'curta' }),
    });
    expect(bad.status).toBe(422);
    const ok = await fetch(url, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({
        purpose: 'care_coordination',
        justification: 'Conferência de documento na recepção',
      }),
    });
    expect(ok.status).toBe(200);
    const data = (await ok.json()) as { system: string; value: string };
    expect(data.system).toBe(ident.system);
    expect(data.value.replace(/\D/g, '')).toHaveLength(15);
  });
});
