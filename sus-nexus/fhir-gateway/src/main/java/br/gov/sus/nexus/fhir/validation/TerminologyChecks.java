package br.gov.sus.nexus.fhir.validation;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.hl7.fhir.r4.model.ContactPoint;
import org.hl7.fhir.r4.model.HumanName;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.OperationOutcome.IssueType;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Practitioner;
import org.hl7.fhir.r4.model.Resource;

/**
 * Terminologia básica verificada localmente (bindings {@code required} da base que o modelo não
 * garante, e regras municipais de uso de códigos). Terminologia completa fica a cargo do validador
 * oficial quando habilitado.
 */
public final class TerminologyChecks {

  private TerminologyChecks() {}

  private static final Set<String> ADMINISTRATIVE_GENDER =
      Set.of("male", "female", "other", "unknown");
  private static final Set<String> IDENTIFIER_USE =
      Set.of("usual", "official", "temp", "secondary", "old");
  private static final Set<String> NAME_USE =
      Set.of("usual", "official", "temp", "nickname", "anonymous", "old", "maiden");
  private static final Set<String> CONTACT_SYSTEM =
      Set.of("phone", "fax", "email", "pager", "url", "sms", "other");

  public static List<ValidationIssue> check(Resource resource) {
    List<ValidationIssue> issues = new ArrayList<>();
    if (resource instanceof Patient p) {
      if (p.hasGenderElement() && p.getGenderElement().hasValue()) {
        checkCode(
            p.getGenderElement().getValueAsString(),
            ADMINISTRATIVE_GENDER,
            "Patient.gender",
            issues);
      }
      p.getIdentifier().forEach(i -> checkIdentifier(i, "Patient.identifier", issues));
      p.getName().forEach(n -> checkName(n, "Patient.name", issues));
      p.getTelecom().forEach(t -> checkTelecom(t, "Patient.telecom", issues));
    } else if (resource instanceof Practitioner pr) {
      if (pr.hasGenderElement() && pr.getGenderElement().hasValue()) {
        checkCode(
            pr.getGenderElement().getValueAsString(),
            ADMINISTRATIVE_GENDER,
            "Practitioner.gender",
            issues);
      }
      pr.getIdentifier().forEach(i -> checkIdentifier(i, "Practitioner.identifier", issues));
      pr.getName().forEach(n -> checkName(n, "Practitioner.name", issues));
    }
    return issues;
  }

  private static void checkIdentifier(Identifier id, String path, List<ValidationIssue> issues) {
    if (id.hasUseElement() && id.getUseElement().hasValue()) {
      checkCode(id.getUseElement().getValueAsString(), IDENTIFIER_USE, path + ".use", issues);
    }
  }

  private static void checkName(HumanName name, String path, List<ValidationIssue> issues) {
    if (name.hasUseElement() && name.getUseElement().hasValue()) {
      checkCode(name.getUseElement().getValueAsString(), NAME_USE, path + ".use", issues);
    }
  }

  private static void checkTelecom(ContactPoint cp, String path, List<ValidationIssue> issues) {
    if (cp.hasSystemElement() && cp.getSystemElement().hasValue()) {
      checkCode(cp.getSystemElement().getValueAsString(), CONTACT_SYSTEM, path + ".system", issues);
    }
  }

  private static void checkCode(
      String code, Set<String> allowed, String path, List<ValidationIssue> issues) {
    if (!allowed.contains(code)) {
      issues.add(
          ValidationIssue.error(
              IssueType.CODEINVALID,
              "Código '" + code + "' fora do conjunto permitido em " + path,
              path));
    }
  }
}
