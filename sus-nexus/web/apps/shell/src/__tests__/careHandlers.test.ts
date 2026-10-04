// @vitest-environment node
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import type { CareGap, CarePlan, HospitalEpisode, Protocol } from '@sus-nexus/api-client';
import { server } from '@/mocks/server';
import { carePlans, careGaps, hospitalEpisodes, protocols } from '@/mocks/care-data';
import { citizens, summaries, tasks } from '@/mocks/data';

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

const BASE = 'http://core.test/api/v1';
const PURPOSE = { 'X-Purpose-Of-Use': 'care_coordination' };
const post = (path: string, body: unknown) =>
  fetch(`${BASE}${path}`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(body),
  });

describe('handlers MSW — Fase 3 (hospital, plano de cuidado, protocolos)', () => {
  it('dados sintéticos coerentes: risco variado, 1 pós-alta fora do SLA, 3 protocolos vigentes', () => {
    expect(new Set(hospitalEpisodes.map((e) => e.risk_level).filter(Boolean))).toEqual(
      new Set(['high', 'medium', 'low']),
    );
    const late = careGaps.filter((g) => g.gap_kind === 'post_discharge_no_contact');
    expect(late).toHaveLength(1);
    expect(tasks.find((t) => t.id === late[0]!.task_id)?.overdue).toBe(true);
    expect(
      protocols
        .filter((p) => p.status === 'active')
        .map((p) => p.care_line)
        .sort(),
    ).toEqual(['diabetes', 'hipertensao', 'pre_natal']);
    expect(new Set(carePlans.map((p) => p.care_line))).toEqual(
      new Set(['pre_natal', 'hipertensao', 'diabetes']),
    );
    // Pré-natal só para mulheres; lacunas herdam a microárea do cidadão.
    for (const p of carePlans.filter((x) => x.care_line === 'pre_natal'))
      expect(citizens.find((c) => c.id === p.citizen_id)?.sex).toBe('female');
    for (const g of careGaps)
      expect(g.microarea).toBe(citizens.find((c) => c.id === g.citizen_id)?.microarea);
    // Resumo do cidadão reflete os planos/lacunas.
    const p0 = carePlans[0]!;
    expect(summaries.get(p0.citizen_id)?.care_lines).toContain(p0.care_line);
  });

  it('episódios exigem finalidade e filtram por UBS de referência e pós-alta', async () => {
    expect((await fetch(`${BASE}/hospital/episodes`)).status).toBe(400);
    const cnes = hospitalEpisodes[0]!.reference_health_unit_cnes!;
    const res = await fetch(
      `${BASE}/hospital/episodes?reference_cnes=${cnes}&followup_status=pending`,
      { headers: PURPOSE },
    );
    const body = (await res.json()) as { items: HospitalEpisode[] };
    expect(body.items.length).toBeGreaterThan(0);
    for (const e of body.items) {
      expect(e.reference_health_unit_cnes).toBe(cnes);
      expect(e.followup?.status).toBe('pending');
    }
  });

  it('desfecho pós-alta valida enum e conclui a tarefa vinculada', async () => {
    const ep = hospitalEpisodes.find((e) => e.followup?.status === 'pending')!;
    expect((await post(`/hospital/episodes/${ep.id}/followup`, { outcome: 'x' })).status).toBe(422);
    const res = await post(`/hospital/episodes/${ep.id}/followup`, {
      outcome: 'contact_made',
      note: 'Orientado sobre medicação.',
    });
    expect(res.status).toBe(200);
    const updated = (await res.json()) as HospitalEpisode;
    expect(updated.followup?.status).toBe('contacted');
    expect(tasks.find((t) => t.id === ep.followup?.task_id)?.status).toBe('completed');
  });

  it('busca ativa filtra por microárea e resolve com desfecho', async () => {
    const res = await fetch(`${BASE}/caregaps?microarea=01`, { headers: PURPOSE });
    const body = (await res.json()) as { items: CareGap[] };
    expect(body.items.length).toBeGreaterThan(0);
    for (const g of body.items) expect(g.microarea).toBe('01');
    const gap = body.items[0]!;
    expect((await post(`/caregaps/${gap.id}/resolve`, { resolution: '??' })).status).toBe(422);
    const ok = await post(`/caregaps/${gap.id}/resolve`, { resolution: 'contact_made' });
    expect(((await ok.json()) as CareGap).status).toBe('resolved');
    expect((await post(`/caregaps/${gap.id}/resolve`, { resolution: 'contact_made' })).status).toBe(
      409,
    );
  });

  it('plano: só protocolo vigente e elegível; encerrar exige motivo', async () => {
    const man = citizens.find((c) => c.sex === 'male')!;
    const denied = await post('/careplans', { citizen_id: man.id, protocol_id: 'prot_prenatal' });
    expect(denied.status).toBe(422);
    const plan = carePlans.find((p) => p.status === 'active')!;
    expect(
      (await post(`/careplans/${plan.id}/close`, { status: 'completed', reason: 'x' })).status,
    ).toBe(422);
    const closed = await post(`/careplans/${plan.id}/close`, {
      status: 'cancelled',
      reason: 'Mudou de município.',
    });
    expect(((await closed.json()) as CarePlan).status).toBe('cancelled');
  });

  it('protocolo: aprovação sem casos de teste é bloqueada (422)', async () => {
    const res = await post('/protocols/prot_has/versions/1.5.0/transition', {
      action: 'approve',
      justification: 'Revisão concluída pela coordenação.',
    });
    expect(res.status).toBe(422);
    expect(((await res.json()) as { title: string }).title).toBe('Casos de teste obrigatórios');
    const created = await post('/protocols', {
      care_line: 'hipertensao',
      name: 'Hipertensão arterial sistêmica',
      base_version: '1.4.0',
      items: [{ kind: 'exam', title: 'Potássio', due_in_days: 30 }],
      test_cases: [{ input: {}, expected: {} }],
    });
    const draft = (await created.json()) as Protocol;
    expect(draft.status).toBe('draft');
    expect(draft.test_cases_count).toBe(1);
    expect(
      (
        await post(`/protocols/${draft.id}/versions/${draft.version}/transition`, {
          action: 'approve',
        })
      ).status,
    ).toBe(409);
  });
});
