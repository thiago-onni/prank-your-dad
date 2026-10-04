package br.gov.sus.nexus.fhir.audit;

import br.gov.sus.nexus.fhir.FhirConstants;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.Date;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Period;
import org.hl7.fhir.r4.model.Provenance;
import org.hl7.fhir.r4.model.Provenance.ProvenanceAgentComponent;
import org.hl7.fhir.r4.model.Provenance.ProvenanceEntityComponent;
import org.hl7.fhir.r4.model.Provenance.ProvenanceEntityRole;
import org.hl7.fhir.r4.model.Reference;

/**
 * Constrói {@link Provenance} para recursos criados por projeção do core municipal (consumidor de
 * {@code sus.identity.citizen.v1} em produção; endpoint interno nesta entrega).
 */
@ApplicationScoped
public class ProvenanceFactory {

  /** Origem de uma projeção. */
  public record ProjectionSource(
      String sourceSystem, String sourceRecordId, Instant occurredAt, String correlationId) {}

  public Provenance forProjection(
      String targetType, String targetId, int version, ProjectionSource source) {
    Provenance prov = new Provenance();
    prov.addTarget(new Reference(targetType + "/" + targetId + "/_history/" + version));
    prov.setRecorded(new Date());
    if (source.occurredAt() != null) {
      prov.setOccurred(new Period().setStart(Date.from(source.occurredAt())));
    }
    prov.addPolicy(FhirConstants.SUS_NEXUS_BASE + "/policy/projection-from-core");
    prov.setActivity(
        new CodeableConcept()
            .addCoding(
                new Coding()
                    .setSystem(FhirConstants.CS_DATA_OPERATION)
                    .setCode("DERIVE")
                    .setDisplay("derive")));

    ProvenanceAgentComponent author = prov.addAgent();
    author.setType(
        new CodeableConcept()
            .addCoding(
                new Coding()
                    .setSystem(FhirConstants.CS_PROVENANCE_PARTICIPANT_TYPE)
                    .setCode("assembler")));
    author.setWho(new Reference().setDisplay("sus-nexus-fhir-gateway"));
    author.setOnBehalfOf(
        new Reference()
            .setDisplay(source.sourceSystem() == null ? "core-municipal" : source.sourceSystem()));

    ProvenanceEntityComponent entity = prov.addEntity();
    entity.setRole(ProvenanceEntityRole.SOURCE);
    Identifier id =
        new Identifier()
            .setSystem(FhirConstants.SYSTEM_SOURCE_LOCAL_ID)
            .setValue(source.sourceRecordId() == null ? targetId : source.sourceRecordId());
    entity.setWhat(
        new Reference()
            .setIdentifier(id)
            .setDisplay(source.sourceSystem() == null ? "core-municipal" : source.sourceSystem()));
    if (source.correlationId() != null) {
      prov.addExtension(
          FhirConstants.SUS_NEXUS_BASE + "/StructureDefinition/correlation-id",
          new org.hl7.fhir.r4.model.StringType(source.correlationId()));
    }
    return prov;
  }
}
