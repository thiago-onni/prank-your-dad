package br.gov.sus.nexus.fhir.mapping;

import static br.gov.sus.nexus.fhir.mapping.MappingSupport.date;
import static br.gov.sus.nexus.fhir.mapping.MappingSupport.extensionCode;
import static br.gov.sus.nexus.fhir.mapping.MappingSupport.extensionString;
import static br.gov.sus.nexus.fhir.mapping.MappingSupport.isBlank;
import static br.gov.sus.nexus.fhir.mapping.MappingSupport.lower;

import br.gov.sus.nexus.fhir.FhirConstants;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import org.hl7.fhir.r4.model.BooleanType;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.Period;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Task;
import org.hl7.fhir.r4.model.Task.TaskIntent;
import org.hl7.fhir.r4.model.Task.TaskPriority;
import org.hl7.fhir.r4.model.Task.TaskStatus;

/**
 * Canônico {@code Task} → FHIR {@code Task}.
 *
 * <p>Status: open→requested, assigned→accepted, in_progress→in-progress, completed→completed,
 * cancelled→cancelled, escalated→in-progress; {@code businessStatus} carrega sempre o status
 * canônico ({@code task-business-status}, ex.: {@code escalated}). {@code code} = {@code task_type}
 * no CodeSystem municipal {@code task-type}; {@code for} = Patient; {@code owner} conforme {@code
 * assignee.kind}: user→PractitionerRole, team→CareTeam, health_unit→Organization (CNES, resolvida),
 * queue→Organization lógica (fila); {@code restriction.period.end} = {@code due_at}; {@code
 * basedOn} quando há {@code origin} (referência direta quando o id tem prefixo conhecido, lógica
 * caso contrário). Prioridade: low/medium→routine, high→urgent, urgent→asap (canônica em extensão).
 */
@ApplicationScoped
public class TaskMapper {

  @Inject MapperSettings settings;

  public TaskMapper() {}

  public TaskMapper(MapperSettings settings) {
    this.settings = settings;
  }

  public Task map(CanonicalTask t) {
    Task task = new Task();
    task.setId(CanonicalIds.toFhirId(t.id()));
    task.getMeta().addProfile(settings.taskProfile());
    task.addIdentifier(MappingSupport.identifier(FhirConstants.SYSTEM_MUNICIPAL_TASK_ID, t.id()));

    task.setStatus(status(t.status()));
    task.setBusinessStatus(
        new CodeableConcept()
            .addCoding(
                new Coding().setSystem(FhirConstants.CS_TASK_BUSINESS_STATUS).setCode(t.status()))
            .setText(t.status()));
    extensionCode(task, FhirConstants.EXT_TASK_STATUS, t.status());
    task.setIntent(TaskIntent.ORDER);
    task.setPriority(priority(t.priority()));
    extensionCode(task, FhirConstants.EXT_TASK_PRIORITY, t.priority());

    CodeableConcept code = new CodeableConcept();
    code.addCoding(new Coding().setSystem(FhirConstants.CS_TASK_TYPE).setCode(t.taskType()));
    if (!isBlank(t.title())) {
      code.setText(t.title());
    }
    task.setCode(code);
    if (!isBlank(t.description())) {
      task.setDescription(t.description());
    }
    if (!isBlank(t.citizenId())) {
      task.setFor(MappingSupport.patientRef(t.citizenId()));
    }
    owner(t.assignee()).ifPresent(task::setOwner);
    if (t.createdAt() != null) {
      task.setAuthoredOnElement(new DateTimeType(date(t.createdAt())));
    }
    if (t.updatedAt() != null) {
      task.setLastModifiedElement(new DateTimeType(date(t.updatedAt())));
    }
    if (t.dueAt() != null) {
      task.getRestriction().setPeriod(new Period().setEnd(date(t.dueAt())));
    }
    if (t.completedAt() != null) {
      task.setExecutionPeriod(new Period().setEnd(date(t.completedAt())));
    }
    if (t.origin() != null && !isBlank(t.origin().id())) {
      Reference basedOn =
          MappingSupport.referenceFromPrefixedId(t.origin().id())
              .orElseGet(
                  () ->
                      MappingSupport.logical(
                          null,
                          FhirConstants.SUS_NEXUS_BASE
                              + "/NamingSystem/origin-"
                              + lower(t.origin().kind()),
                          t.origin().id()));
      if (!isBlank(t.origin().version())) {
        basedOn.setDisplay(lower(t.origin().kind()) + " " + t.origin().version());
      }
      task.addBasedOn(basedOn);
    }
    if (!isBlank(t.reason())) {
      task.setStatusReason(new CodeableConcept().setText(t.reason()));
    }
    extensionString(task, FhirConstants.EXT_TASK_OUTCOME, t.outcome());
    extensionString(task, FhirConstants.EXT_SLA_POLICY_ID, t.slaPolicyId());
    if (t.overdue() != null) {
      task.addExtension(FhirConstants.EXT_TASK_OVERDUE, new BooleanType(t.overdue()));
    }
    if (t.slaBreachedAt() != null) {
      task.addExtension(
          FhirConstants.EXT_SLA_BREACHED_AT, new DateTimeType(date(t.slaBreachedAt())));
    }
    return task;
  }

  static Optional<Reference> owner(CanonicalTask.Assignee a) {
    if (a == null || isBlank(a.id()) || a.kind() == null) {
      return Optional.empty();
    }
    return Optional.of(
        switch (lower(a.kind())) {
          case "user" ->
              MappingSupport.logical(
                  "PractitionerRole", FhirConstants.SYSTEM_MUNICIPAL_USER_ID, a.id());
          case "team" ->
              MappingSupport.logical("CareTeam", FhirConstants.SYSTEM_MUNICIPAL_TEAM_ID, a.id());
          case "health_unit" -> MappingSupport.organizationByCnes(a.id());
          case "queue" ->
              MappingSupport.logical(
                  "Organization", FhirConstants.SYSTEM_MUNICIPAL_QUEUE_ID, a.id());
          default -> throw new IllegalArgumentException("assignee.kind desconhecido");
        });
  }

  public static TaskStatus status(String canonical) {
    return switch (lower(canonical)) {
      case "open" -> TaskStatus.REQUESTED;
      case "assigned" -> TaskStatus.ACCEPTED;
      case "in_progress", "escalated" -> TaskStatus.INPROGRESS;
      case "completed" -> TaskStatus.COMPLETED;
      case "cancelled" -> TaskStatus.CANCELLED;
      default -> throw new IllegalArgumentException("TaskStatus canônico desconhecido");
    };
  }

  public static TaskPriority priority(String canonical) {
    return switch (lower(canonical)) {
      case "", "low", "medium" -> TaskPriority.ROUTINE;
      case "high" -> TaskPriority.URGENT;
      case "urgent" -> TaskPriority.ASAP;
      default -> throw new IllegalArgumentException("Prioridade canônica desconhecida");
    };
  }
}
