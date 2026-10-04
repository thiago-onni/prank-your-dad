package br.gov.sus.nexus.fhir.security;

import br.gov.sus.nexus.fhir.FhirConstants;
import jakarta.enterprise.context.ApplicationScoped;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.Meta;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.StringType;

/**
 * Política de exemplo: quando o escopo de leitura de Patient é restrito (granular, ex.: {@code
 * user/Patient.rs}) em vez de completo ({@code user/Patient.read}, {@code user/*.read}), remove
 * {@code Patient.telecom} e {@code Patient.address} e marca o recurso com a tag {@code REDACTED}.
 */
@ApplicationScoped
public class ScopeRedactionPolicy implements RedactionPolicy {

  public static final String REDACTED_TAG_SYSTEM =
      "http://terminology.hl7.org/CodeSystem/v3-ObservationValue";
  public static final String REDACTED_TAG_CODE = "REDACTED";
  public static final String EXT_REDACTED_ELEMENTS =
      FhirConstants.SUS_NEXUS_BASE + "/StructureDefinition/redacted-elements";

  @Override
  public Resource apply(Identity identity, Resource resource) {
    if (!(resource instanceof Patient patient)) {
      return resource;
    }
    if (identity.hasFullRead("Patient")) {
      return resource;
    }
    boolean changed = false;
    if (patient.hasTelecom()) {
      patient.setTelecom(null);
      changed = true;
    }
    if (patient.hasAddress()) {
      patient.setAddress(null);
      changed = true;
    }
    if (changed) {
      Meta meta = patient.getMeta();
      meta.addTag().setSystem(REDACTED_TAG_SYSTEM).setCode(REDACTED_TAG_CODE);
      meta.addExtension(new Extension(EXT_REDACTED_ELEMENTS, new StringType("telecom,address")));
    }
    return patient;
  }
}
