package br.gov.sus.nexus.core.audit.api;

import br.gov.sus.nexus.core.platform.security.Purpose;
import java.util.Set;

/** Registro de acesso a dado de cidadão ({@code audit.access_log}). */
public record AccessRecord(
    String actorId,
    Set<String> actorRoles,
    String action,
    String resourceType,
    String resourceId,
    String citizenId,
    Purpose purpose,
    boolean allowed,
    boolean breakGlass,
    String justification,
    String correlationId) {}
