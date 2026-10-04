package br.gov.sus.nexus.fhir.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import br.gov.sus.nexus.fhir.FhirConstants;
import org.hl7.fhir.r4.model.Task;
import org.hl7.fhir.r4.model.Task.TaskPriority;
import org.hl7.fhir.r4.model.Task.TaskStatus;
import org.junit.jupiter.api.Test;

class TaskMapperTest {

  private final TaskMapper mapper = new TaskMapper(MapperSettings.defaults());

  @Test
  void mapsCanonicalTask() {
    CanonicalTask c = MapperTestSupport.canonical("canonical-task.json", CanonicalTask.class);
    Task t = mapper.map(c);
    assertThat(t.getId()).isEqualTo("01J0000000000000000000TSK1");
    assertThat(t.getStatus()).isEqualTo(TaskStatus.INPROGRESS);
    assertThat(t.getBusinessStatus().getCodingFirstRep().getCode()).isEqualTo("escalated");
    assertThat(t.getIntent().toCode()).isEqualTo("order");
    assertThat(t.getPriority()).isEqualTo(TaskPriority.URGENT);
    assertThat(t.getCode().getCodingFirstRep().getSystem()).isEqualTo(FhirConstants.CS_TASK_TYPE);
    assertThat(t.getCode().getCodingFirstRep().getCode()).isEqualTo("regulation_pending_document");
    assertThat(t.getCode().getText()).isEqualTo("Documento pendente na regulação");
    assertThat(t.getFor().getReference()).isEqualTo("Patient/01HZX4Y5K6M7N8P9Q0R1S2T3U4");
    assertThat(t.getOwner().getType()).isEqualTo("Organization");
    assertThat(t.getOwner().getIdentifier().getValue()).isEqualTo("2112345");
    assertThat(t.getRestriction().getPeriod().getEndElement().getValueAsString())
        .startsWith("2026-10-12T");
    assertThat(t.getBasedOnFirstRep().getReference())
        .isEqualTo("ServiceRequest/01J0000000000000000000REG1");
    assertThat(t.getStatusReason().getText()).isEqualTo("Regulador solicitou documento");
    assertThat(t.getExtensionByUrl(FhirConstants.EXT_SLA_POLICY_ID).getValue().primitiveValue())
        .isEqualTo("sla_reg_docs_48h");
  }

  @Test
  void ownerByAssigneeKind() {
    assertThat(TaskMapper.owner(new CanonicalTask.Assignee("user", "u1")).orElseThrow().getType())
        .isEqualTo("PractitionerRole");
    assertThat(TaskMapper.owner(new CanonicalTask.Assignee("team", "t1")).orElseThrow().getType())
        .isEqualTo("CareTeam");
    assertThat(
            TaskMapper.owner(new CanonicalTask.Assignee("queue", "q1"))
                .orElseThrow()
                .getIdentifier()
                .getSystem())
        .isEqualTo(FhirConstants.SYSTEM_MUNICIPAL_QUEUE_ID);
    assertThat(TaskMapper.owner(null)).isEmpty();
  }

  @Test
  void statusAndPriorityMapping() {
    assertThat(TaskMapper.status("open")).isEqualTo(TaskStatus.REQUESTED);
    assertThat(TaskMapper.status("assigned")).isEqualTo(TaskStatus.ACCEPTED);
    assertThat(TaskMapper.status("in_progress")).isEqualTo(TaskStatus.INPROGRESS);
    assertThat(TaskMapper.status("completed")).isEqualTo(TaskStatus.COMPLETED);
    assertThat(TaskMapper.status("cancelled")).isEqualTo(TaskStatus.CANCELLED);
    assertThat(TaskMapper.status("escalated")).isEqualTo(TaskStatus.INPROGRESS);
    assertThat(TaskMapper.priority("low")).isEqualTo(TaskPriority.ROUTINE);
    assertThat(TaskMapper.priority("urgent")).isEqualTo(TaskPriority.ASAP);
  }

  @Test
  void originWithoutKnownPrefixBecomesLogicalReference() {
    CanonicalTask c = MapperTestSupport.canonical("canonical-task.json", CanonicalTask.class);
    CanonicalTask t =
        new CanonicalTask(
            c.id(),
            c.taskType(),
            "open",
            "low",
            c.title(),
            null,
            null,
            null,
            null,
            null,
            null,
            new CanonicalTask.Origin("rule", "rule-42", "3"),
            null,
            null,
            null,
            null,
            c.createdAt(),
            null,
            1);
    Task task = mapper.map(t);
    assertThat(task.hasFor()).isFalse();
    assertThat(task.getBasedOnFirstRep().hasReference()).isFalse();
    assertThat(task.getBasedOnFirstRep().getIdentifier().getSystem()).endsWith("/origin-rule");
    assertThat(task.getBasedOnFirstRep().getIdentifier().getValue()).isEqualTo("rule-42");
  }
}
