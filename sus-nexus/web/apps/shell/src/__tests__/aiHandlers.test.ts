// @vitest-environment node
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import type { AgentRunRecord, KillSwitchResponse } from '@sus-nexus/api-client';
import { server } from '@/mocks/server';
import { aiRuns, killSwitchAdmin } from '@/mocks/ai-data';
import { examOrders } from '@/mocks/exams-data';
import { regulationRequests } from '@/mocks/regulation-data';

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => {
  server.close();
  killSwitchAdmin.global = false;
  killSwitchAdmin.tenants = [];
});

const json = (body: unknown) => ({
  method: 'POST',
  headers: { 'content-type': 'application/json' },
  body: JSON.stringify(body),
});

describe('handlers MSW — ai-service', () => {
  it('rejeita ação com justificativa curta e aceita com justificativa válida (stateful)', async () => {
    const run = aiRuns.find((r) => r.actions.some((a) => a.status === 'pending_approval'))!;
    const action = run.actions.find((a) => a.status === 'pending_approval')!;
    const url = `http://ai.test/runs/${run.id}/actions/${action.id}/reject`;
    expect((await fetch(url, json({ justification: 'curta' }))).status).toBe(422);
    const ok = await fetch(url, json({ justification: 'Documento já anexado pela unidade.' }));
    expect(ok.status).toBe(200);
    const body = (await ok.json()) as AgentRunRecord;
    expect(body.actions.find((a) => a.id === action.id)?.status).toBe('rejected');
    // Segunda decisão sobre a mesma ação: conflito.
    expect(
      (await fetch(url, json({ justification: 'Tentativa repetida de decisão.' }))).status,
    ).toBe(409);
    const approvals = (await (await fetch('http://ai.test/approvals?status=rejected')).json()) as {
      action_id: string;
    }[];
    expect(approvals.some((a) => a.action_id === action.id)).toBe(true);
  });

  it('kill switch: valida o corpo e funde estado administrativo com o de ambiente', async () => {
    const bad = await fetch('http://ai.test/admin/kill-switch', json({ global: 'sim' }));
    expect(bad.status).toBe(422);
    const ok = await fetch(
      'http://ai.test/admin/kill-switch',
      json({ global: true, tenants: ['ibge_3143302'] }),
    );
    expect(ok.status).toBe(200);
    const body = (await ok.json()) as KillSwitchResponse;
    expect(body.admin).toEqual({ global: true, agents: [], tools: [], tenants: ['ibge_3143302'] });
    expect(body.effective.tools).toContain('core.send_message');
    expect(body.effective.global).toBe(true);
  });
});

describe('handlers MSW — regulação e exames', () => {
  it('fila exige finalidade e aceita filtro issue=sla_breached', async () => {
    expect((await fetch('http://core.test/api/v1/regulation/requests')).status).toBe(400);
    const res = await fetch('http://core.test/api/v1/regulation/requests?issue=sla_breached', {
      headers: { 'X-Purpose-Of-Use': 'care_coordination' },
    });
    const body = (await res.json()) as { items: { sla_breached?: boolean }[] };
    expect(body.items.length).toBe(regulationRequests.filter((r) => r.sla_breached).length);
    for (const r of body.items) expect(r.sla_breached).toBe(true);
  });

  it('pendência exige kind válido e descrição mínima; resumo aceita group_by', async () => {
    const r = regulationRequests[3]!;
    const url = `http://core.test/api/v1/regulation/requests/${r.id}/issues`;
    expect((await fetch(url, json({ kind: 'decide', description: 'x'.repeat(20) }))).status).toBe(
      422,
    );
    expect((await fetch(url, json({ kind: 'other', description: 'x' }))).status).toBe(422);
    const ok = await fetch(
      url,
      json({ kind: 'other', description: 'Pendência registrada em teste.' }),
    );
    expect(ok.status).toBe(201);
    const summary = await fetch(
      'http://core.test/api/v1/regulation/queues/summary?group_by=priority',
    );
    const items = ((await summary.json()) as { items: { group_key: string }[] }).items;
    expect(items.map((i) => i.group_key)).toEqual(expect.arrayContaining(['elective']));
    expect(
      (await fetch('http://core.test/api/v1/regulation/queues/summary?group_by=x')).status,
    ).toBe(400);
  });

  it('documento do laudo devolve URL assinada curta e exige finalidade', async () => {
    const order = examOrders[0]!;
    const result = order.results![0]!;
    const url = `http://core.test/api/v1/exams/orders/${order.id}/results/${result.id}/document`;
    expect((await fetch(url)).status).toBe(400);
    const res = await fetch(url, { headers: { 'X-Purpose-Of-Use': 'care_coordination' } });
    expect(res.status).toBe(200);
    const body = (await res.json()) as { url: string; expires_at: string; content_type: string };
    expect(body.url).toMatch(/^https:\/\/documents\.sus-nexus\.invalid\/signed\//);
    expect(new Date(body.expires_at).getTime()).toBeGreaterThan(Date.now());
    expect(body.content_type).toBe('application/pdf');
  });
});
