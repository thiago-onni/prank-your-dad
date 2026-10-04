/** Decodifica o payload de um JWT sem verificar assinatura (token veio do token endpoint via TLS). */
export function decodeJwtPayload(token: string): Record<string, unknown> {
  const parts = token.split('.');
  const payload = parts[1];
  if (!payload) return {};
  try {
    const normalized = payload
      .replace(/-/g, '+')
      .replace(/_/g, '/')
      .padEnd(Math.ceil(payload.length / 4) * 4, '=');
    const bytes = Uint8Array.from(atob(normalized), (c) => c.charCodeAt(0));
    const json = new TextDecoder().decode(bytes);
    const parsed: unknown = JSON.parse(json);
    return parsed && typeof parsed === 'object' ? (parsed as Record<string, unknown>) : {};
  } catch {
    return {};
  }
}

export function extractRoles(payload: Record<string, unknown>, clientId?: string): string[] {
  const roles = new Set<string>();
  const realm = payload.realm_access;
  if (realm && typeof realm === 'object' && Array.isArray((realm as { roles?: unknown }).roles)) {
    for (const r of (realm as { roles: unknown[] }).roles) if (typeof r === 'string') roles.add(r);
  }
  const resource = payload.resource_access;
  if (clientId && resource && typeof resource === 'object') {
    const client = (resource as Record<string, { roles?: unknown }>)[clientId];
    if (client && Array.isArray(client.roles)) {
      for (const r of client.roles) if (typeof r === 'string') roles.add(r);
    }
  }
  return [...roles];
}
