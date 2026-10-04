import { describe, expect, it, vi } from 'vitest';
import { CoreApiError } from '../client';
import { createAiClient } from '../ai/client';
import {
  deriveAutonomy,
  normalizeKillSwitch,
  normalizeRun,
  normalizeTool,
  normalizeToolCall,
  type AgentRunRecordRaw,
} from '../ai/types';

const minimalRun: AgentRunRecordRaw = {
  id: 'run_1',
  agent_id: 'post_discharge_followup',
  agent_version: '1.0.0',
  prompt_version: 'v1',
  model: 'claude-sonnet-4-5',
  tenant: 'ibge_3143302',
  trigger: { kind: 'event' },
  input_ref: { hash: 'abc' },
  started_at: '2026-10-04T12:00:00Z',
  status: 'completed',
  validation_status: 'valid',
  validation_attempts: 1,
  cost_estimate: 0,
  tokens_in: 0,
  tokens_out: 0,
};

describe('normalização do ai-service', () => {
  it('aplica os defaults do contrato a um run mínimo', () => {
    const run = normalizeRun(minimalRun);
    expect(run.actions).toEqual([]);
    expect(run.tools_called).toEqual([]);
    expect(run.minimized_context).toEqual({});
    expect(run.rule_versions).toEqual({});
    expect(run.output).toBeNull();
  });

  it('estreita tools_called (objeto livre no contrato) para ToolCallRecord', () => {
    const call = normalizeToolCall({
      tool: 'core.create_task',
      status: 'executed',
      args_masked: { citizen_id: 'ctz_1' },
      decision: { allow: true, action_class: 'auto', reasons: ['x'] },
      called_at: '2026-10-04T12:00:00Z',
      duration_ms: 12,
    });
    expect(call.status).toBe('executed');
    expect(call.decision).toEqual({
      allow: true,
      action_class: 'auto',
      requires_approval: undefined,
      reasons: ['x'],
    });
    expect(normalizeToolCall('lixo')).toMatchObject({ tool: 'desconhecida', status: 'error' });
    expect(normalizeToolCall({ tool: 'x', status: 'weird' }).status).toBe('error');
  });

  it('estreita risk/action_class de ferramentas com fallback seguro', () => {
    const tool = normalizeTool({
      name: 'x.y',
      description: '',
      risk: 'critical',
      action_class: 'whatever',
      scope: 's',
      kind: 'write',
      owner: 'o',
      stub: false,
      input_schema: {},
      output_schema: {},
    });
    expect(tool.risk).toBe('medium');
    expect(tool.action_class).toBe('requires_approval');
  });

  it('kill switch recebe listas vazias por padrão', () => {
    expect(normalizeKillSwitch({ effective: { global: true }, admin: { global: false } })).toEqual({
      effective: { global: true, agents: [], tools: [], tenants: [] },
      admin: { global: false, agents: [], tools: [], tenants: [] },
    });
  });

  it('deriva autonomia a partir das ferramentas', () => {
    const tools = [
      { name: 'core.get_x', action_class: 'auto' as const, kind: 'read' },
      { name: 'core.create_task', action_class: 'auto' as const, kind: 'write' },
      {
        name: 'core.create_pending_issue',
        action_class: 'requires_approval' as const,
        kind: 'write',
      },
    ];
    expect(deriveAutonomy({ tools: ['core.get_x'] }, tools)).toBe('suggest_only');
    expect(deriveAutonomy({ tools: ['core.get_x', 'core.create_task'] }, tools)).toBe('autonomous');
    expect(deriveAutonomy({ tools: ['core.list_y'] })).toBe('suggest_only');
    expect(deriveAutonomy({ tools: ['core.send_z'] })).toBe('approval_required');
    expect(deriveAutonomy({ tools: ['core.create_pending_issue'] }, tools)).toBe(
      'approval_required',
    );
    expect(deriveAutonomy({ tools: ['mpi.merge'] }, tools)).toBe('suggest_only');
    expect(deriveAutonomy({ tools: [] })).toBe('suggest_only');
  });
});

describe('createAiClient', () => {
  it('monta URL, query e cabeçalhos; normaliza a resposta', async () => {
    const fetchSpy = vi.fn<typeof fetch>().mockResolvedValue(
      new Response(JSON.stringify([minimalRun]), {
        status: 200,
        headers: { 'content-type': 'application/json' },
      }),
    );
    const ai = createAiClient({
      baseUrl: 'http://ai.test',
      fetch: fetchSpy,
      getCorrelationId: () => 'corr-1',
    });
    const runs = await ai.listRuns({ agent_id: '', status: 'completed', limit: 5 });
    expect(runs[0]?.actions).toEqual([]);
    const req = fetchSpy.mock.calls[0]![0] as Request;
    expect(req.url).toBe('http://ai.test/runs?status=completed&limit=5');
    expect(req.headers.get('x-correlation-id')).toBe('corr-1');
    expect(req.headers.get('x-purpose-of-use')).toBeNull();
  });

  it('envia a justificativa e converte erros em CoreApiError (RFC 9457)', async () => {
    const fetchSpy = vi.fn<typeof fetch>().mockResolvedValue(
      new Response(JSON.stringify({ title: 'Conflict', status: 409, correlation_id: 'corr-9' }), {
        status: 409,
        headers: { 'content-type': 'application/problem+json' },
      }),
    );
    const ai = createAiClient({ baseUrl: 'http://ai.test', fetch: fetchSpy });
    await expect(ai.approveAction('run_1', 'act_1', 'Justificativa válida.')).rejects.toThrow(
      CoreApiError,
    );
    const req = fetchSpy.mock.calls[0]![0] as Request;
    expect(req.url).toBe('http://ai.test/runs/run_1/actions/act_1/approve');
    expect(await req.json()).toEqual({ justification: 'Justificativa válida.' });
  });
});
