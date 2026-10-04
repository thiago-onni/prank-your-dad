import {
  HEADER_CORRELATION,
  HEADER_PURPOSE,
  isPurpose,
  newCorrelationId,
} from '@sus-nexus/api-client';
import { withAuth } from '@sus-nexus/auth/server';
import { maskFhirIdentifiers, type JsonValue } from '@sus-nexus/domain-components/fhir';
import { cookies } from 'next/headers';
import { env } from '@/lib/env';
import { resolveFhirRoute } from '@/lib/fhir/allowlist';
import { parsePurposeCookie, PURPOSE_COOKIE } from '@/lib/purpose';
import { FHIR_VIEWER_ROLES, safeCorrelationId } from '@/lib/situacao/roles';
import { ensureMockServer } from '@/mocks/enable';

/**
 * Proxy BFF → FHIR Gateway (somente leitura do compartimento do paciente).
 * Bearer da sessão + `X-Tenant-Id` + `X-Purpose-Of-Use` (finalidade obrigatória, gera AuditEvent no
 * gateway). Defesa em profundidade: CPF/CNS são mascarados aqui antes de chegar ao navegador.
 */
type Ctx = { params: Promise<{ path: string[] }> };

export const dynamic = 'force-dynamic';

function outcome(status: number, diagnostics: string, correlationId: string) {
  return Response.json(
    {
      resourceType: 'OperationOutcome',
      issue: [
        { severity: 'error', code: status === 404 ? 'not-supported' : 'invalid', diagnostics },
      ],
    },
    {
      status,
      headers: {
        'content-type': 'application/fhir+json',
        [HEADER_CORRELATION]: correlationId,
        'cache-control': 'no-store',
      },
    },
  );
}

export const GET = withAuth<Ctx>(
  async (req, ctx, { accessToken, session }) => {
    const { path } = await ctx.params;
    const correlationId = safeCorrelationId(req.headers.get(HEADER_CORRELATION), newCorrelationId);
    const incoming = new URL(req.url);
    const route = resolveFhirRoute(path, incoming.searchParams);
    if (!route.ok) return outcome(route.status, route.detail, correlationId);

    let purpose = req.headers.get(HEADER_PURPOSE);
    if (!purpose) {
      const cookieStore = await cookies();
      purpose = parsePurposeCookie(cookieStore.get(PURPOSE_COOKIE)?.value) ?? null;
    }
    if (!isPurpose(purpose)) {
      return outcome(400, 'Finalidade de acesso (X-Purpose-Of-Use) obrigatória.', correlationId);
    }

    const query = route.search.toString();
    const target = `${env.fhirGatewayUrl.replace(/\/$/, '')}/fhir/r4/${route.path}${query ? `?${query}` : ''}`;
    const headers = new Headers({
      accept: 'application/fhir+json',
      authorization: `Bearer ${accessToken}`,
      [HEADER_PURPOSE]: purpose,
      [HEADER_CORRELATION]: correlationId,
    });
    if (session.user.municipalityId) headers.set('x-tenant-id', session.user.municipalityId);

    let upstream: Response;
    try {
      await ensureMockServer();
      upstream = await fetch(target, { headers, cache: 'no-store', redirect: 'manual' });
    } catch (error) {
      console.error('[bff] fhir-gateway indisponível', {
        correlationId,
        message: error instanceof Error ? error.message : 'erro',
      });
      return outcome(502, 'FHIR Gateway indisponível.', correlationId);
    }

    const text = await upstream.text();
    let body: string = text;
    try {
      body = JSON.stringify(maskFhirIdentifiers(JSON.parse(text) as JsonValue));
    } catch {
      return outcome(502, 'Resposta inválida do FHIR Gateway.', correlationId);
    }
    return new Response(body, {
      status: upstream.status,
      headers: {
        'content-type': 'application/fhir+json',
        [HEADER_CORRELATION]: correlationId,
        'cache-control': 'no-store',
      },
    });
  },
  { roles: FHIR_VIEWER_ROLES },
);
