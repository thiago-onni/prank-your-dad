package br.gov.sus.nexus.connectors.rnds;

import br.gov.sus.nexus.connectors.sdk.api.ConnectorException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.hl7.fhir.r4.model.DiagnosticReport;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Practitioner;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.ServiceRequest;
import org.hl7.fhir.r4.model.Specimen;

/**
 * Busca no fhir-gateway os recursos de um modelo: {@code resultado-exame} → DiagnosticReport (id =
 * {@code exam_result_id} sem prefixo), Observations de {@code result}, ServiceRequest de {@code
 * basedOn}, Specimens de {@code Observation.specimen}/{@code DiagnosticReport.specimen}, Patient de
 * {@code subject}, Organization/Practitioner referenciados literalmente; {@code sumario-alta} →
 * Encounter ({@code hospital_episode_id}), Patient e Organization.
 */
@ApplicationScoped
public class ResourceFetcher {

  private final FhirGatewayClient fhir;

  @Inject
  public ResourceFetcher(FhirGatewayClient fhir) {
    this.fhir = fhir;
  }

  /** Id FHIR a partir do id canônico ({@code exr_01H...} → {@code 01H...}), como no gateway. */
  public static String toFhirId(String canonicalId) {
    int underscore = canonicalId.indexOf('_');
    return underscore > 0 && underscore < 6 ? canonicalId.substring(underscore + 1) : canonicalId;
  }

  public SourceResources examResult(
      String stableId, Map<String, String> eventData, String cnesSystem) {
    DiagnosticReport report =
        as(fhir.read("DiagnosticReport", toFhirId(stableId)), DiagnosticReport.class);
    Patient patient = patientOf(report.getSubject());
    List<Observation> observations = new ArrayList<>();
    for (Reference ref : report.getResult()) {
      String[] parts = literal(ref, "Observation");
      if (parts != null)
        observations.add(as(fhir.read("Observation", parts[1]), Observation.class));
    }
    ServiceRequest serviceRequest = null;
    for (Reference ref : report.getBasedOn()) {
      String[] parts = literal(ref, "ServiceRequest");
      if (parts != null) {
        serviceRequest = as(fhir.read("ServiceRequest", parts[1]), ServiceRequest.class);
        break;
      }
    }
    List<Practitioner> practitioners = new ArrayList<>();
    for (Reference ref : report.getResultsInterpreter()) {
      String[] parts = literal(ref, "Practitioner");
      if (parts != null) {
        practitioners.add(as(fhir.read("Practitioner", parts[1]), Practitioner.class));
      }
    }
    Map<String, Specimen> specimens = new LinkedHashMap<>();
    for (Reference ref : report.getSpecimen()) readSpecimen(ref, specimens);
    for (Observation o : observations) {
      if (o.hasSpecimen()) readSpecimen(o.getSpecimen(), specimens);
    }
    Map<String, String> orgCnes = new LinkedHashMap<>();
    for (Reference ref : report.getPerformer()) resolveOrganization(ref, cnesSystem, orgCnes);
    for (Observation o : observations) {
      for (Reference ref : o.getPerformer()) resolveOrganization(ref, cnesSystem, orgCnes);
    }
    return new SourceResources(
        BundleAssembler.RESULTADO_EXAME,
        stableId,
        patient,
        report,
        observations,
        serviceRequest,
        practitioners,
        null,
        orgCnes,
        eventData,
        specimens,
        null);
  }

  private void readSpecimen(Reference ref, Map<String, Specimen> out) {
    String[] parts = literal(ref, "Specimen");
    if (parts == null || out.containsKey("Specimen/" + parts[1])) return;
    out.put("Specimen/" + parts[1], as(fhir.read("Specimen", parts[1]), Specimen.class));
  }

  public SourceResources discharge(
      String stableId, Map<String, String> eventData, String cnesSystem) {
    Encounter encounter = as(fhir.read("Encounter", toFhirId(stableId)), Encounter.class);
    Patient patient = patientOf(encounter.getSubject());
    Map<String, String> orgCnes = new LinkedHashMap<>();
    if (encounter.hasServiceProvider()) {
      resolveOrganization(encounter.getServiceProvider(), cnesSystem, orgCnes);
    }
    return new SourceResources(
        BundleAssembler.SUMARIO_ALTA,
        stableId,
        patient,
        null,
        List.of(),
        null,
        List.of(),
        encounter,
        orgCnes,
        eventData);
  }

  private Patient patientOf(Reference subject) {
    String[] parts = literal(subject, "Patient");
    if (parts == null) {
      throw ConnectorException.permanent("transform", "recurso sem subject Patient literal", null);
    }
    return as(fhir.read("Patient", parts[1]), Patient.class);
  }

  private void resolveOrganization(Reference ref, String cnesSystem, Map<String, String> out) {
    String[] parts = literal(ref, "Organization");
    if (parts == null) return;
    Organization org = as(fhir.read("Organization", parts[1]), Organization.class);
    for (Identifier id : org.getIdentifier()) {
      if (cnesSystem.equals(id.getSystem()) && id.hasValue()) {
        out.put("Organization/" + parts[1], id.getValue());
        return;
      }
    }
  }

  /** {@code [Tipo, id]} de uma referência literal relativa do tipo esperado, ou null. */
  static String[] literal(Reference ref, String type) {
    if (ref == null || !ref.hasReference()) return null;
    String[] parts = ref.getReference().split("/");
    for (int i = parts.length - 2; i >= 0; i--) {
      if (type.equals(parts[i])) return new String[] {type, parts[i + 1]};
    }
    return null;
  }

  private static <T extends Resource> T as(Resource resource, Class<T> type) {
    if (!type.isInstance(resource)) {
      throw ConnectorException.permanent(
          "transform",
          "fhir-gateway retornou "
              + resource.fhirType()
              + " (esperado "
              + type.getSimpleName()
              + ")",
          null);
    }
    return type.cast(resource);
  }
}
