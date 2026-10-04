import { HEADER_CORRELATION, newCorrelationId } from '@sus-nexus/api-client';
import { withAuth } from '@sus-nexus/auth/server';
import { trinoEnv } from '@/lib/env';
import { handleSituationRequest } from '@/lib/situacao/handler';
import { safeCorrelationId, SITUATION_ROLES } from '@/lib/situacao/roles';
import { ensureMockServer } from '@/mocks/enable';

/**
 * BFF da Sala de Situação: `GET /api/situacao/<indicadores|serie|ranking|territorios|capacidade|filtros>`.
 * Consulta o Trino com usuário de serviço do grupo BI; o município vem SEMPRE do token
 * (`municipality_id`) e nunca de parâmetro do navegador. Somente consultas fixas e parametrizadas.
 */
type Ctx = { params: Promise<{ view: string }> };

export const dynamic = 'force-dynamic';

export const GET = withAuth<Ctx>(
  async (req, ctx, { session }) => {
    const { view } = await ctx.params;
    const correlationId = safeCorrelationId(req.headers.get(HEADER_CORRELATION), newCorrelationId);
    await ensureMockServer();
    const res = await handleSituationRequest(view, new URL(req.url), {
      tenantId: session.user.municipalityId,
      trino: trinoEnv(),
      correlationId,
    });
    res.headers.set(HEADER_CORRELATION, correlationId);
    return res;
  },
  { roles: SITUATION_ROLES },
);
