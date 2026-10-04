package br.gov.sus.nexus.fhir.security;

import br.gov.sus.nexus.fhir.capability.CapabilityRegistry;
import br.gov.sus.nexus.fhir.capability.Interaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Política padrão: exige identidade autenticada com tenant, mapeia interação → permissão SMART e
 * restringe o contexto {@code patient/} ao compartimento do próprio paciente: {@code Patient} pelo
 * id; os demais tipos pelo parâmetro de busca {@code patient} registrado ({@code Encounter}, {@code
 * Appointment}, {@code ServiceRequest}, {@code Task}, {@code Condition}, {@code CarePlan}, {@code
 * AuditEvent}). Tipos sem compartimento de paciente são negados nesse contexto. A verificação de
 * pertencimento em leituras por id é feita após a carga pelo roteador ({@link PatientCompartment}).
 */
@ApplicationScoped
public class ScopeAccessPolicy implements AccessPolicy {

  @Inject CapabilityRegistry registry;

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
      if ("Patient".equals(type)) {
        if (request.resourceId() != null
            && !request.resourceId().equals(identity.patientId().get())) {
          return Decision.deny("patient-context-other-patient");
        }
      } else if (!hasPatientCompartment(type)) {
        return Decision.deny("patient-context-no-compartment:" + type);
      }
    }
    return Decision.allow();
  }

  private boolean hasPatientCompartment(String type) {
    return registry.searchParam(type, CapabilityRegistry.PATIENT_PARAM).isPresent();
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
