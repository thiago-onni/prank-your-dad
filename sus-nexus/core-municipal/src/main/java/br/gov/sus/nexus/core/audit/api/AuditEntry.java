package br.gov.sus.nexus.core.audit.api;

import java.util.Map;
import java.util.Set;

/**
 * Entrada do {@code audit.audit_log} (append-only, encadeado por hash). Ator e papéis são
 * preenchidos pelo serviço quando nulos. {@code details} nunca deve conter PII em claro.
 */
public record AuditEntry(
    String actorId,
    Set<String> actorRoles,
    String action,
    String resourceType,
    String resourceId,
    String citizenId,
    String reason,
    Map<String, Object> details) {

  public static AuditEntry of(
      String action, String resourceType, String resourceId, String citizenId) {
    return new AuditEntry(null, null, action, resourceType, resourceId, citizenId, null, Map.of());
  }

  public AuditEntry withReason(String newReason) {
    return new AuditEntry(
        actorId, actorRoles, action, resourceType, resourceId, citizenId, newReason, details);
  }

  public AuditEntry withDetails(Map<String, Object> newDetails) {
    return new AuditEntry(
        actorId, actorRoles, action, resourceType, resourceId, citizenId, reason, newDetails);
  }
}
