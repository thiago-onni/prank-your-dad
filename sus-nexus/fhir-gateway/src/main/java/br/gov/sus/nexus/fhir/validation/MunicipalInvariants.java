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

  /** Expressão: o elemento de referência existe e aponta para {@code Patient/…}. */
  private static String patientRef(String element) {
    return element + ".exists() and " + element + ".reference.startsWith('Patient/')";
  }

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
              IssueSeverity.ERROR),
          // ---- FHIR-2 ----
          new MunicipalInvariant(
              "sus-enc-1",
              "Encounter",
              patientRef("subject"),
              "Encounter.subject deve referenciar um Patient",
              "Encounter.subject",
              IssueSeverity.ERROR),
          new MunicipalInvariant(
              "sus-enc-2",
              "Encounter",
              "class.code.exists() and class.system.exists()",
              "Encounter.class deve ter system e code (v3-ActCode: AMB/EMER/IMP/HH)",
              "Encounter.class",
              IssueSeverity.ERROR),
          new MunicipalInvariant(
              "sus-enc-3",
              "Encounter",
              "status in ('planned' | 'cancelled') or period.start.exists()",
              "Encounter iniciado deve ter period.start",
              "Encounter.period",
              IssueSeverity.WARNING),
          new MunicipalInvariant(
              "sus-app-1",
              "Appointment",
              "participant.actor.where(reference.startsWith('Patient/')).exists()",
              "Appointment deve ter um participant Patient",
              "Appointment.participant",
              IssueSeverity.ERROR),
          new MunicipalInvariant(
              "sus-app-2",
              "Appointment",
              "status in ('proposed' | 'cancelled' | 'waitlist') or start.exists()",
              "Appointment agendado deve ter start",
              "Appointment.start",
              IssueSeverity.ERROR),
          new MunicipalInvariant(
              "sus-sr-1",
              "ServiceRequest",
              patientRef("subject"),
              "ServiceRequest.subject deve referenciar um Patient",
              "ServiceRequest.subject",
              IssueSeverity.ERROR),
          new MunicipalInvariant(
              "sus-sr-2",
              "ServiceRequest",
              "code.coding.where(system.exists() and code.exists()).exists()",
              "ServiceRequest.code deve ter coding com system e code (SIGTAP/LOINC/local)",
              "ServiceRequest.code",
              IssueSeverity.ERROR),
          new MunicipalInvariant(
              "sus-sr-3",
              "ServiceRequest",
              "authoredOn.exists()",
              "ServiceRequest sem authoredOn",
              "ServiceRequest.authoredOn",
              IssueSeverity.WARNING),
          new MunicipalInvariant(
              "sus-task-1",
              "Task",
              patientRef("for"),
              "Task.for deve referenciar um Patient",
              "Task.for",
              IssueSeverity.ERROR),
          new MunicipalInvariant(
              "sus-task-2",
              "Task",
              "code.coding.where(system.exists() and code.exists()).exists()",
              "Task.code deve ter coding com system e code (task-type)",
              "Task.code",
              IssueSeverity.ERROR),
          new MunicipalInvariant(
              "sus-cond-1",
              "Condition",
              patientRef("subject"),
              "Condition.subject deve referenciar um Patient",
              "Condition.subject",
              IssueSeverity.ERROR),
          new MunicipalInvariant(
              "sus-cond-2",
              "Condition",
              "code.coding.where(system.exists() and code.exists()).exists()",
              "Condition.code deve ter coding com system e code (CID-10/CIAP-2)",
              "Condition.code",
              IssueSeverity.ERROR),
          new MunicipalInvariant(
              "sus-cp-1",
              "CarePlan",
              patientRef("subject"),
              "CarePlan.subject deve referenciar um Patient",
              "CarePlan.subject",
              IssueSeverity.ERROR),
          new MunicipalInvariant(
              "sus-cp-2",
              "CarePlan",
              "category.exists() or title.exists() or description.exists()",
              "CarePlan deve ter category, title ou description",
              "CarePlan",
              IssueSeverity.WARNING),
          // ---- FHIR-3 ----
          new MunicipalInvariant(
              "sus-obs-1",
              "Observation",
              patientRef("subject"),
              "Observation.subject deve referenciar um Patient",
              "Observation.subject",
              IssueSeverity.ERROR),
          new MunicipalInvariant(
              "sus-obs-2",
              "Observation",
              "code.coding.where(system.exists() and code.exists()).exists()",
              "Observation.code deve ter coding com system e code (LOINC/SIGTAP/local)",
              "Observation.code",
              IssueSeverity.ERROR),
          new MunicipalInvariant(
              "sus-dr-1",
              "DiagnosticReport",
              patientRef("subject"),
              "DiagnosticReport.subject deve referenciar um Patient",
              "DiagnosticReport.subject",
              IssueSeverity.ERROR),
          new MunicipalInvariant(
              "sus-dr-2",
              "DiagnosticReport",
              "code.coding.where(system.exists() and code.exists()).exists()",
              "DiagnosticReport.code deve ter coding com system e code (SIGTAP/LOINC/local)",
              "DiagnosticReport.code",
              IssueSeverity.ERROR),
          new MunicipalInvariant(
              "sus-dr-3",
              "DiagnosticReport",
              "presentedForm.all(data.empty())",
              "DiagnosticReport.presentedForm não admite conteúdo inline; use Binary (url)",
              "DiagnosticReport.presentedForm",
              IssueSeverity.ERROR),
          new MunicipalInvariant(
              "sus-doc-1",
              "DocumentReference",
              "content.attachment.all(data.empty())",
              "DocumentReference não admite conteúdo inline (attachment.data); use Binary (url)",
              "DocumentReference.content.attachment",
              IssueSeverity.ERROR),
          new MunicipalInvariant(
              "sus-doc-2",
              "DocumentReference",
              patientRef("subject"),
              "DocumentReference.subject deve referenciar um Patient",
              "DocumentReference.subject",
              IssueSeverity.ERROR),
          new MunicipalInvariant(
              "sus-doc-3",
              "DocumentReference",
              "type.coding.where(system.exists() and code.exists()).exists()",
              "DocumentReference.type deve ter coding com system e code (LOINC)",
              "DocumentReference.type",
              IssueSeverity.ERROR),
          new MunicipalInvariant(
              "sus-bin-1",
              "Binary",
              "contentType.exists()",
              "Binary.contentType obrigatório",
              "Binary.contentType",
              IssueSeverity.ERROR),
          new MunicipalInvariant(
              "sus-bin-2",
              "Binary",
              patientRef("securityContext"),
              "Binary.securityContext deve referenciar o Patient (compartimento de acesso)",
              "Binary.securityContext",
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
