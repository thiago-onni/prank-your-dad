package br.gov.sus.nexus.connectors.rnds;

import java.util.List;
import java.util.Map;
import org.hl7.fhir.r4.model.DiagnosticReport;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Practitioner;
import org.hl7.fhir.r4.model.ServiceRequest;
import org.hl7.fhir.r4.model.Specimen;

/**
 * Recursos lidos do fhir-gateway para montar um modelo, mais os dados do evento ({@code eventData}:
 * campos de {@code data} do envelope, sem PII). Campos não usados pelo modelo ficam nulos.
 *
 * @param stableId id canônico do registro de origem (ex.: {@code exr_...}, {@code hep_...})
 * @param organizationCnes CNES resolvido de referências literais a Organization (id → CNES)
 * @param specimens Specimens referenciados por Observation/DiagnosticReport ({@code Specimen/<id>})
 * @param replacesProtocol id atribuído pela RNDS ao documento anterior do mesmo registro (já
 *     aceito); presente = este envio é uma substituição ({@code relatesTo replaces})
 */
public record SourceResources(
    String model,
    String stableId,
    Patient patient,
    DiagnosticReport report,
    List<Observation> observations,
    ServiceRequest serviceRequest,
    List<Practitioner> practitioners,
    Encounter encounter,
    Map<String, String> organizationCnes,
    Map<String, String> eventData,
    Map<String, Specimen> specimens,
    String replacesProtocol) {

  /** Construtor sem Specimens e sem substituição. */
  public SourceResources(
      String model,
      String stableId,
      Patient patient,
      DiagnosticReport report,
      List<Observation> observations,
      ServiceRequest serviceRequest,
      List<Practitioner> practitioners,
      Encounter encounter,
      Map<String, String> organizationCnes,
      Map<String, String> eventData) {
    this(
        model,
        stableId,
        patient,
        report,
        observations,
        serviceRequest,
        practitioners,
        encounter,
        organizationCnes,
        eventData,
        Map.of(),
        null);
  }

  /** Cópia marcando a substituição do documento aceito anteriormente. */
  public SourceResources replacing(String protocol) {
    return new SourceResources(
        model,
        stableId,
        patient,
        report,
        observations,
        serviceRequest,
        practitioners,
        encounter,
        organizationCnes,
        eventData,
        specimens,
        protocol);
  }

  public SourceResources {
    observations = observations == null ? List.of() : List.copyOf(observations);
    practitioners = practitioners == null ? List.of() : List.copyOf(practitioners);
    organizationCnes = organizationCnes == null ? Map.of() : Map.copyOf(organizationCnes);
    eventData = eventData == null ? Map.of() : Map.copyOf(eventData);
    specimens = specimens == null ? Map.of() : Map.copyOf(specimens);
  }
}
