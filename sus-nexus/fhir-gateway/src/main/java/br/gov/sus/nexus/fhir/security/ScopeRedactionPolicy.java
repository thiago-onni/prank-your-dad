package br.gov.sus.nexus.fhir.security;

import br.gov.sus.nexus.fhir.FhirConstants;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;
import org.hl7.fhir.r4.model.CarePlan;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Condition;
import org.hl7.fhir.r4.model.DiagnosticReport;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.Meta;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.ServiceRequest;
import org.hl7.fhir.r4.model.StringType;
import org.hl7.fhir.r4.model.Task;

/**
 * Redação por escopo. Quando a leitura do tipo é concedida apenas por escopo granular (ex.: {@code
 * user/Patient.rs}) em vez de completo ({@code user/Patient.read}, {@code user/*.read}):
 *
 * <ul>
 *   <li>{@code Patient}: remove {@code telecom} e {@code address};
 *   <li>{@code Condition}: remove {@code code.text};
 *   <li>{@code ServiceRequest} e {@code Encounter}: removem {@code reasonCode};
 *   <li>{@code Task} e {@code CarePlan}: removem {@code description};
 *   <li>{@code Observation}: remove {@code valueString} e {@code note};
 *   <li>{@code DiagnosticReport}: remove {@code conclusion} e {@code presentedForm}.
 * </ul>
 *
 * <p>Recursos com {@code meta.security} {@code highly_restricted} (CodeSystem municipal {@code
 * sensitivity}) ou {@code V} (v3-Confidentiality) são omitidos integralmente sem escopo completo.
 */
@ApplicationScoped
public class ScopeRedactionPolicy implements RedactionPolicy {

  public static final String REDACTED_TAG_SYSTEM =
      "http://terminology.hl7.org/CodeSystem/v3-ObservationValue";
  public static final String REDACTED_TAG_CODE = "REDACTED";
  public static final String EXT_REDACTED_ELEMENTS =
      FhirConstants.SUS_NEXUS_BASE + "/StructureDefinition/redacted-elements";
  public static final String HIGHLY_RESTRICTED = "highly_restricted";

  @Override
  public boolean withhold(Identity identity, Resource resource) {
    if (identity.hasFullRead(resource.fhirType())) {
      return false;
    }
    for (Coding c : resource.getMeta().getSecurity()) {
      boolean municipal =
          FhirConstants.CS_SENSITIVITY.equals(c.getSystem())
              && HIGHLY_RESTRICTED.equals(c.getCode());
      boolean v3 =
          FhirConstants.CS_V3_CONFIDENTIALITY.equals(c.getSystem()) && "V".equals(c.getCode());
      if (municipal || v3) {
        return true;
      }
    }
    return false;
  }

  @Override
  public Resource apply(Identity identity, Resource resource) {
    if (identity.hasFullRead(resource.fhirType())) {
      return resource;
    }
    List<String> removed = new ArrayList<>();
    switch (resource) {
      case Patient patient -> {
        if (patient.hasTelecom()) {
          patient.setTelecom(null);
          removed.add("telecom");
        }
        if (patient.hasAddress()) {
          patient.setAddress(null);
          removed.add("address");
        }
      }
      case Condition condition -> {
        if (condition.hasCode() && condition.getCode().hasText()) {
          condition.getCode().setText(null);
          removed.add("code.text");
        }
      }
      case ServiceRequest sr -> {
        if (sr.hasReasonCode()) {
          sr.setReasonCode(null);
          removed.add("reasonCode");
        }
      }
      case Encounter enc -> {
        if (enc.hasReasonCode()) {
          enc.setReasonCode(null);
          removed.add("reasonCode");
        }
      }
      case Task task -> {
        if (task.hasDescription()) {
          task.setDescription(null);
          removed.add("description");
        }
      }
      case CarePlan plan -> {
        if (plan.hasDescription()) {
          plan.setDescription(null);
          removed.add("description");
        }
      }
      case Observation obs -> {
        if (obs.hasValueStringType()) {
          obs.setValue(null);
          removed.add("valueString");
        }
        if (obs.hasNote()) {
          obs.setNote(null);
          removed.add("note");
        }
      }
      case DiagnosticReport report -> {
        if (report.hasConclusion()) {
          report.setConclusion(null);
          removed.add("conclusion");
        }
        if (report.hasPresentedForm()) {
          report.setPresentedForm(null);
          removed.add("presentedForm");
        }
      }
      default -> {
        return resource;
      }
    }
    if (!removed.isEmpty()) {
      Meta meta = resource.getMeta();
      meta.addTag().setSystem(REDACTED_TAG_SYSTEM).setCode(REDACTED_TAG_CODE);
      meta.addExtension(
          new Extension(EXT_REDACTED_ELEMENTS, new StringType(String.join(",", removed))));
    }
    return resource;
  }
}
