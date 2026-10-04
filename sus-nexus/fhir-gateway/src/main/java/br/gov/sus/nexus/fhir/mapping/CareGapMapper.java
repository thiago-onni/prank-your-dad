package br.gov.sus.nexus.fhir.mapping;

import static br.gov.sus.nexus.fhir.mapping.MappingSupport.date;
import static br.gov.sus.nexus.fhir.mapping.MappingSupport.extensionCode;
import static br.gov.sus.nexus.fhir.mapping.MappingSupport.extensionString;
import static br.gov.sus.nexus.fhir.mapping.MappingSupport.isBlank;
import static br.gov.sus.nexus.fhir.mapping.MappingSupport.lower;

import br.gov.sus.nexus.fhir.FhirConstants;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.hl7.fhir.r4.model.BooleanType;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.IntegerType;
import org.hl7.fhir.r4.model.Period;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Task;
import org.hl7.fhir.r4.model.Task.TaskIntent;
import org.hl7.fhir.r4.model.Task.TaskPriority;
import org.hl7.fhir.r4.model.Task.TaskStatus;

/**
 * Canônico {@code CareGap} → FHIR {@code Task} de busca ativa.
 *
 * <ul>
 *   <li>{@code code} = {@code care_gap} no CodeSystem municipal {@code task-type}; {@code
 *       reasonCode} = {@code gap_kind} ({@code care-gap-kind}); {@code focus} = {@code
 *       CarePlan/<id>} quando há plano; {@code for} = Patient; {@code intent=order}.
 *   <li>Status: open→requested, resolved→completed; {@code businessStatus} = status canônico;
 *       {@code authoredOn} = detected_at; {@code restriction.period.end} = expected_by; {@code
 *       executionPeriod.end} = resolved_at; prioridade asap quando {@code days_overdue > 30},
 *       urgent quando {@code > 0}, routine caso contrário.
 *   <li>{@code owner} Organization por CNES (resolvida na projeção); {@code basedOn} = Task
 *       operacional gerada pelo core ({@code task_id}); extensões care-line, days-overdue,
 *       gap-resolution, contact-valid, protocol-id/version, team-ine, microarea.
 * </ul>
 */
@ApplicationScoped
public class CareGapMapper {

  @Inject MapperSettings settings;

  public CareGapMapper() {}

  public CareGapMapper(MapperSettings settings) {
    this.settings = settings;
  }

  public Task map(CanonicalCareGap g) {
    Task task = new Task();
    task.setId(CanonicalIds.toFhirId(g.id()));
    task.getMeta().addProfile(settings.taskProfile());
    task.addIdentifier(
        MappingSupport.identifier(FhirConstants.SYSTEM_MUNICIPAL_CARE_GAP_ID, g.id()));
    task.setStatus(status(g.status()));
    task.setBusinessStatus(
        new CodeableConcept()
            .addCoding(
                new Coding()
                    .setSystem(FhirConstants.CS_TASK_BUSINESS_STATUS)
                    .setCode(isBlank(g.status()) ? "open" : g.status()))
            .setText(g.status()));
    task.setIntent(TaskIntent.ORDER);
    task.setPriority(priority(g.daysOverdue()));
    task.setCode(
        new CodeableConcept()
            .addCoding(
                new Coding()
                    .setSystem(FhirConstants.CS_TASK_TYPE)
                    .setCode(FhirConstants.TASK_TYPE_CARE_GAP)
                    .setDisplay("Lacuna de cuidado"))
            .setText("Busca ativa: " + lower(g.gapKind())));
    if (!isBlank(g.gapKind())) {
      task.setReasonCode(
          new CodeableConcept()
              .addCoding(
                  new Coding().setSystem(FhirConstants.CS_CARE_GAP_KIND).setCode(g.gapKind()))
              .setText(g.gapKind()));
    }
    task.setFor(MappingSupport.patientRef(g.citizenId()));
    if (!isBlank(g.carePlanId())) {
      task.setFocus(new Reference("CarePlan/" + CanonicalIds.toFhirId(g.carePlanId())));
    }
    if (!isBlank(g.healthUnitCnes())) {
      task.setOwner(MappingSupport.organizationByCnes(g.healthUnitCnes()));
    }
    if (!isBlank(g.taskId())) {
      MappingSupport.referenceFromPrefixedId(g.taskId()).ifPresent(task::addBasedOn);
    }
    if (g.detectedAt() != null) {
      task.setAuthoredOnElement(new DateTimeType(date(g.detectedAt())));
    }
    if (g.expectedBy() != null) {
      task.getRestriction().setPeriod(new Period().setEnd(date(g.expectedBy())));
    }
    if (g.resolvedAt() != null) {
      task.setExecutionPeriod(new Period().setEnd(date(g.resolvedAt())));
      task.setLastModifiedElement(new DateTimeType(date(g.resolvedAt())));
    }
    extensionCode(task, FhirConstants.EXT_CARE_LINE, g.careLine());
    extensionCode(task, FhirConstants.EXT_GAP_RESOLUTION, g.resolution());
    extensionString(task, FhirConstants.EXT_PROTOCOL_ID, g.protocolId());
    extensionString(task, FhirConstants.EXT_PROTOCOL_VERSION, g.protocolVersion());
    extensionString(task, FhirConstants.EXT_TEAM_INE, g.teamIne());
    extensionString(
        task, FhirConstants.SUS_NEXUS_BASE + "/StructureDefinition/microarea", g.microarea());
    if (g.daysOverdue() != null) {
      task.addExtension(FhirConstants.EXT_DAYS_OVERDUE, new IntegerType(g.daysOverdue()));
    }
    if (g.contactValid() != null) {
      task.addExtension(FhirConstants.EXT_CONTACT_VALID, new BooleanType(g.contactValid()));
    }
    return task;
  }

  public static TaskStatus status(String canonical) {
    return switch (lower(canonical)) {
      case "", "open" -> TaskStatus.REQUESTED;
      case "resolved" -> TaskStatus.COMPLETED;
      default -> throw new IllegalArgumentException("Status de lacuna de cuidado desconhecido");
    };
  }

  public static TaskPriority priority(Integer daysOverdue) {
    if (daysOverdue == null || daysOverdue <= 0) {
      return TaskPriority.ROUTINE;
    }
    return daysOverdue > 30 ? TaskPriority.ASAP : TaskPriority.URGENT;
  }
}
