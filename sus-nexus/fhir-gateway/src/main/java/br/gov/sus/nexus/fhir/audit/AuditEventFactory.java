package br.gov.sus.nexus.fhir.audit;

import br.gov.sus.nexus.fhir.FhirConstants;
import br.gov.sus.nexus.fhir.capability.Interaction;
import br.gov.sus.nexus.fhir.config.FhirGatewayConfig;
import br.gov.sus.nexus.fhir.security.Identity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Date;
import org.hl7.fhir.r4.model.AuditEvent;
import org.hl7.fhir.r4.model.AuditEvent.AuditEventAction;
import org.hl7.fhir.r4.model.AuditEvent.AuditEventAgentComponent;
import org.hl7.fhir.r4.model.AuditEvent.AuditEventAgentNetworkComponent;
import org.hl7.fhir.r4.model.AuditEvent.AuditEventEntityComponent;
import org.hl7.fhir.r4.model.AuditEvent.AuditEventOutcome;
import org.hl7.fhir.r4.model.AuditEvent.AuditEventSourceComponent;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Reference;

/** Constrói {@link AuditEvent} para cada interação RESTful (sucesso ou negação). */
@ApplicationScoped
public class AuditEventFactory {

  @Inject FhirGatewayConfig config;

  /** Dados da interação auditada. */
  public record AuditInput(
      Identity identity,
      Interaction interaction,
      String resourceType,
      String resourceId,
      Integer versionId,
      String purposeOfUse,
      String correlationId,
      String query,
      boolean success,
      String denyReason) {}

  public AuditEvent build(AuditInput in) {
    AuditEvent event = new AuditEvent();
    event.setType(
        new Coding()
            .setSystem(FhirConstants.CS_AUDIT_EVENT_TYPE)
            .setCode("rest")
            .setDisplay("RESTful Operation"));
    event.addSubtype(
        new Coding()
            .setSystem(FhirConstants.CS_RESTFUL_INTERACTION)
            .setCode(in.interaction().code()));
    event.setAction(AuditEventAction.fromCode(in.interaction().auditAction()));
    event.setRecorded(new Date());
    event.setOutcome(in.success() ? AuditEventOutcome._0 : AuditEventOutcome._4);
    if (!in.success() && in.denyReason() != null) {
      event.setOutcomeDesc(in.denyReason());
    }
    if (in.purposeOfUse() != null && !in.purposeOfUse().isBlank()) {
      event.addPurposeOfEvent(
          new CodeableConcept()
              .addCoding(
                  new Coding()
                      .setSystem(FhirConstants.SYSTEM_PURPOSE_OF_USE)
                      .setCode(in.purposeOfUse())));
    }

    AuditEventAgentComponent agent = event.addAgent();
    agent.setType(
        new CodeableConcept()
            .addCoding(
                new Coding().setSystem(FhirConstants.CS_PARTICIPATION_TYPE).setCode("IRCP")));
    agent.setRequestor(true);
    agent.setWho(
        new Reference()
            .setIdentifier(
                new Identifier()
                    .setSystem(FhirConstants.SUS_NEXUS_BASE + "/NamingSystem/subject")
                    .setValue(in.identity().subject())));
    for (var scope : in.identity().scopes()) {
      agent.addPolicy(
          scope.context()
              + "/"
              + scope.resourceType()
              + "."
              + scope.permissions().stream()
                  .map(p -> String.valueOf(p.letter()))
                  .sorted()
                  .reduce("", String::concat));
    }
    if (in.correlationId() != null) {
      agent.setNetwork(new AuditEventAgentNetworkComponent().setAddress(in.correlationId()));
    }

    AuditEventSourceComponent source = event.getSource();
    source.setSite(in.identity().tenantId());
    source.setObserver(new Reference().setDisplay("sus-nexus-fhir-gateway"));
    source.addType(new Coding().setSystem(FhirConstants.CS_AUDIT_SOURCE_TYPE).setCode("4"));

    if (in.resourceType() != null) {
      AuditEventEntityComponent entity = event.addEntity();
      if (in.resourceId() != null) {
        String ref = in.resourceType() + "/" + in.resourceId();
        if (in.versionId() != null) {
          ref = ref + "/_history/" + in.versionId();
        }
        entity.setWhat(new Reference(ref));
      } else {
        entity.setWhat(new Reference().setDisplay(in.resourceType()));
      }
      entity.setType(
          new Coding().setSystem("http://hl7.org/fhir/resource-types").setCode(in.resourceType()));
      entity.setRole(
          new Coding()
              .setSystem(FhirConstants.CS_OBJECT_ROLE)
              .setCode("4")
              .setDisplay("Domain Resource"));
      if (in.query() != null) {
        entity.setQuery(in.query().getBytes(java.nio.charset.StandardCharsets.UTF_8));
      }
    }
    return event;
  }
}
