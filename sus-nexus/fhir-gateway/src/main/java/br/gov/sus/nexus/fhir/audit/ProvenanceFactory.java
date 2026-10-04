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

  /**
   * Origem de uma projeção.
   *
   * @param sourceSystem sistema de origem (ex.: {@code core-municipal}, {@code SISREG})
   * @param sourceRecordId id do registro de origem (vai para {@code entity.what.identifier})
   * @param occurredAt quando o fato ocorreu na origem ({@code occurred})
   * @param correlationId correlação (extensão)
   * @param eventId id do evento Kafka que motivou a projeção, se houver (extensão)
   */
  public record ProjectionSource(
      String sourceSystem,
      String sourceRecordId,
      Instant occurredAt,
      String correlationId,
      String eventId) {
    public ProjectionSource(
        String sourceSystem, String sourceRecordId, Instant occurredAt, String correlationId) {
      this(sourceSystem, sourceRecordId, occurredAt, correlationId, null);
    }
  }

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
    author.setWho(
        new Reference()
            .setIdentifier(
                new Identifier()
                    .setSystem(FhirConstants.SYSTEM_SUBJECT)
                    .setValue("sus-nexus-fhir-gateway"))
            .setDisplay("sus-nexus-fhir-gateway"));
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
          FhirConstants.EXT_CORRELATION_ID,
          new org.hl7.fhir.r4.model.StringType(source.correlationId()));
    }
    if (source.eventId() != null) {
      prov.addExtension(
          FhirConstants.EXT_EVENT_ID, new org.hl7.fhir.r4.model.StringType(source.eventId()));
    }
    return prov;
  }
}
