package br.gov.sus.nexus.fhir.validation;

import br.gov.sus.nexus.fhir.FhirConstants;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.stream.Collectors;
import org.hl7.fhir.r4.model.OperationOutcome.IssueSeverity;

/**
 * Catálogo de invariantes municipais (regras além do perfil). Adicione novas regras aqui; cada uma
 * é avaliada com FHIRPath sobre o recurso e reportada com {@code issue.expression}.
 */
@ApplicationScoped
public class MunicipalInvariants {

  private static final String IDENTIFIER_SYSTEMS =
      "('" + FhirConstants.SYSTEM_CNS + "' | '" + FhirConstants.SYSTEM_CPF + "')";

  private final List<MunicipalInvariant> invariants =
      List.of(
          new MunicipalInvariant(
              "sus-pat-1",
              "Patient",
              "identifier.where(system in " + IDENTIFIER_SYSTEMS + " and value.exists()).exists()",
              "Patient deve possuir ao menos um identifier com system CNS ou CPF",
              "Patient.identifier",
              IssueSeverity.ERROR),
          new MunicipalInvariant(
              "sus-pat-2",
              "Patient",
              "name.exists() and name.all(family.exists() or given.exists() or text.exists())",
              "Patient deve possuir ao menos um nome com family, given ou text",
              "Patient.name",
              IssueSeverity.ERROR),
          new MunicipalInvariant(
              "sus-pat-3",
              "Patient",
              "identifier.where(system = '"
                  + FhirConstants.SYSTEM_CPF
                  + "').all("
                  + "value.matches('^[0-9]{11}$') or extension.where(url = '"
                  + FhirConstants.EXT_MASKED_IDENTIFIER
                  + "').exists())",
              "CPF deve ter 11 dígitos (ou estar marcado como mascarado)",
              "Patient.identifier.value",
              IssueSeverity.ERROR),
          new MunicipalInvariant(
              "sus-pat-4",
              "Patient",
              "identifier.where(system = '"
                  + FhirConstants.SYSTEM_CNS
                  + "').all("
                  + "value.matches('^[0-9]{15}$') or extension.where(url = '"
                  + FhirConstants.EXT_MASKED_IDENTIFIER
                  + "').exists())",
              "CNS deve ter 15 dígitos (ou estar marcado como mascarado)",
              "Patient.identifier.value",
              IssueSeverity.ERROR),
          new MunicipalInvariant(
              "sus-pat-5",
              "Patient",
              "birthDate.exists()",
              "Patient sem data de nascimento",
              "Patient.birthDate",
              IssueSeverity.WARNING),
          new MunicipalInvariant(
              "sus-org-1",
              "Organization",
              "identifier.where(system = '"
                  + FhirConstants.SYSTEM_CNES
                  + "' and value.exists()).exists()"
                  + " or identifier.where(system = '"
                  + FhirConstants.SYSTEM_MUNICIPAL_HEALTH_UNIT_ID
                  + "').exists()",
              "Organization deve possuir identifier CNES ou id municipal de unidade",
              "Organization.identifier",
              IssueSeverity.ERROR),
          new MunicipalInvariant(
              "sus-org-2",
              "Organization",
              "name.exists()",
              "Organization deve possuir nome",
              "Organization.name",
              IssueSeverity.ERROR),
          new MunicipalInvariant(
              "sus-loc-1",
              "Location",
              "name.exists()",
              "Location deve possuir nome",
              "Location.name",
              IssueSeverity.ERROR),
          new MunicipalInvariant(
              "sus-prac-1",
              "Practitioner",
              "identifier.where(value.exists()).exists()",
              "Practitioner deve possuir ao menos um identifier",
              "Practitioner.identifier",
              IssueSeverity.ERROR),
          new MunicipalInvariant(
              "sus-prac-2",
              "Practitioner",
              "name.exists()",
              "Practitioner deve possuir nome",
              "Practitioner.name",
              IssueSeverity.ERROR),
          new MunicipalInvariant(
              "sus-prole-1",
              "PractitionerRole",
              "practitioner.exists() and organization.exists()",
              "PractitionerRole deve referenciar practitioner e organization",
              "PractitionerRole",
              IssueSeverity.ERROR));

  public List<MunicipalInvariant> forType(String resourceType) {
    return invariants.stream()
        .filter(i -> i.resourceType().equals(resourceType))
        .collect(Collectors.toList());
  }

  public List<MunicipalInvariant> all() {
    return invariants;
  }
}
