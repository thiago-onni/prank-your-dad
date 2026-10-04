package br.gov.sus.nexus.fhir.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import br.gov.sus.nexus.fhir.FhirConstants;
import org.hl7.fhir.r4.model.Task;
import org.hl7.fhir.r4.model.Task.TaskPriority;
import org.hl7.fhir.r4.model.Task.TaskStatus;
import org.junit.jupiter.api.Test;

class CareGapMapperTest {

  private final CareGapMapper mapper = new CareGapMapper(MapperSettings.defaults());

  @Test
  void mapsGapToCareGapTask() {
    CanonicalCareGap c =
        MapperTestSupport.canonical("canonical-care-gap.json", CanonicalCareGap.class);
    Task t = mapper.map(c);
    assertThat(t.getId()).isEqualTo("01J0000000000000000000GAP1");
    assertThat(t.getCode().getCodingFirstRep().getSystem()).isEqualTo(FhirConstants.CS_TASK_TYPE);
    assertThat(t.getCode().getCodingFirstRep().getCode()).isEqualTo("care_gap");
    assertThat(t.getReasonCode().getCodingFirstRep().getSystem())
        .isEqualTo(FhirConstants.CS_CARE_GAP_KIND);
    assertThat(t.getReasonCode().getCodingFirstRep().getCode()).isEqualTo("exam_overdue");
    assertThat(t.getFocus().getReference()).isEqualTo("CarePlan/01J0000000000000000000CPL1");
    assertThat(t.getFor().getReference()).isEqualTo("Patient/01HZX4Y5K6M7N8P9Q0R1S2T3U4");
    assertThat(t.getStatus()).isEqualTo(TaskStatus.REQUESTED);
    assertThat(t.getBusinessStatus().getCodingFirstRep().getCode()).isEqualTo("open");
    assertThat(t.getPriority()).isEqualTo(TaskPriority.URGENT);
    assertThat(t.getOwner().getIdentifier().getValue()).isEqualTo("2112345");
    assertThat(t.getBasedOnFirstRep().getReference()).isEqualTo("Task/01J0000000000000000000TSK1");
    assertThat(t.getExtensionByUrl(FhirConstants.EXT_DAYS_OVERDUE).getValue().primitiveValue())
        .isEqualTo("12");
    assertThat(t.getRestriction().getPeriod().hasEnd()).isTrue();
  }

  @Test
  void statusAndPriority() {
    assertThat(CareGapMapper.status("resolved")).isEqualTo(TaskStatus.COMPLETED);
    assertThat(CareGapMapper.priority(null)).isEqualTo(TaskPriority.ROUTINE);
    assertThat(CareGapMapper.priority(0)).isEqualTo(TaskPriority.ROUTINE);
    assertThat(CareGapMapper.priority(45)).isEqualTo(TaskPriority.ASAP);
  }
}
