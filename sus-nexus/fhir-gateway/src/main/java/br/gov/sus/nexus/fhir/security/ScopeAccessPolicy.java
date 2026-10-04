package br.gov.sus.nexus.fhir.security;

import br.gov.sus.nexus.fhir.capability.Interaction;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Política padrão: exige identidade autenticada com tenant, mapeia interação → permissão SMART e
 * restringe o contexto {@code patient/} ao próprio Patient.
 */
@ApplicationScoped
public class ScopeAccessPolicy implements AccessPolicy {

  @Override
  public Decision evaluate(AccessRequest request) {
    Identity identity = request.identity();
    if (!identity.isAuthenticated()) {
      return Decision.deny("unauthenticated");
    }
    if (!identity.hasTenant()) {
      return Decision.deny("tenant-missing");
    }
    Permission required = permissionFor(request.interaction());
    String type = request.resourceType();
    if (!identity.grants(type, required)) {
      return Decision.deny("scope-missing:" + type + "." + required.letter());
    }
    if (identity.onlyPatientContext(type, required)) {
      if (identity.patientId().isEmpty()) {
        return Decision.deny("patient-context-without-patient");
      }
      if (required == Permission.CREATE || required == Permission.UPDATE) {
        return Decision.deny("patient-context-write");
      }
      if ("Patient".equals(type)
          && request.resourceId() != null
          && !request.resourceId().equals(identity.patientId().get())) {
        return Decision.deny("patient-context-other-patient");
      }
    }
    return Decision.allow();
  }

  public static Permission permissionFor(Interaction interaction) {
    return switch (interaction) {
      case READ, VREAD, HISTORY_INSTANCE -> Permission.READ;
      case SEARCH_TYPE -> Permission.SEARCH;
      case CREATE -> Permission.CREATE;
      case UPDATE -> Permission.UPDATE;
    };
  }
}
