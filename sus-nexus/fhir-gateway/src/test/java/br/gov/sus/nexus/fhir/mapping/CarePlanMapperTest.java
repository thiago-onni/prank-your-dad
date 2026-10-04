package br.gov.sus.nexus.fhir.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import br.gov.sus.nexus.fhir.FhirConstants;
import org.hl7.fhir.r4.model.CarePlan;
import org.hl7.fhir.r4.model.CarePlan.CarePlanActivityStatus;
import org.hl7.fhir.r4.model.CarePlan.CarePlanStatus;
import org.junit.jupiter.api.Test;

class CarePlanMapperTest {

  private final CarePlanMapper mapper = new CarePlanMapper(MapperSettings.defaults());

  @Test
  void mapsPlanWithActivityPerItem() {
    CanonicalCarePlan c =
        MapperTestSupport.canonical("canonical-care-plan.json", CanonicalCarePlan.class);
    CarePlan p = mapper.map(c);
    assertThat(p.getId()).isEqualTo("01J0000000000000000000CPL1");
    assertThat(p.getStatus()).isEqualTo(CarePlanStatus.ACTIVE);
    assertThat(p.getIntent()).isEqualTo(CarePlan.CarePlanIntent.PLAN);
    assertThat(p.getCategoryFirstRep().getCodingFirstRep().getCode()).isEqualTo("hipertensao");
    assertThat(p.getSubject().getReference()).isEqualTo("Patient/01HZX4Y5K6M7N8P9Q0R1S2T3U4");
    assertThat(p.getAuthor().getType()).isEqualTo("PractitionerRole");
    assertThat(p.getContributorFirstRep().getIdentifier().getValue()).isEqualTo("2112345");
    assertThat(p.getSupportingInfoFirstRep().getReference())
        .isEqualTo("Encounter/01J0000000000000000000HEP1");
    assertThat(p.getPeriod().hasStart()).isTrue();
    assertThat(p.getPeriod().hasEnd()).isFalse();
    assertThat(p.getActivity()).hasSize(3);
    var exam = p.getActivity().get(1).getDetail();
    assertThat(exam.getStatus()).isEqualTo(CarePlanActivityStatus.STOPPED);
    assertThat(exam.getCode().getCoding().get(0).getCode()).isEqualTo("0202010503");
    assertThat(exam.getCode().getCoding().get(1).getCode()).isEqualTo("exam");
    assertThat(exam.getScheduledPeriod().hasEnd()).isTrue();
    assertThat(exam.getExtensionByUrl(FhirConstants.EXT_ITEM_OVERDUE).getValue().primitiveValue())
        .isEqualTo("true");
    assertThat(
            exam.getExtensionByUrl(FhirConstants.EXT_CARE_PLAN_ITEM_ID).getValue().primitiveValue())
        .isEqualTo("it_2");
    assertThat(p.getActivity().get(0).getDetail().getStatus())
        .isEqualTo(CarePlanActivityStatus.SCHEDULED);
    assertThat(p.getActivity().get(2).getDetail().getStatus())
        .isEqualTo(CarePlanActivityStatus.COMPLETED);
    assertThat(p.getExtensionByUrl(FhirConstants.EXT_OPEN_GAPS).getValue().primitiveValue())
        .isEqualTo("1");
    assertThat(p.getExtensionByUrl(FhirConstants.EXT_PROTOCOL_VERSION).getValue().primitiveValue())
        .isEqualTo("2.1");
  }

  @Test
  void statusMapping() {
    assertThat(CarePlanMapper.status("on_hold")).isEqualTo(CarePlanStatus.ONHOLD);
    assertThat(CarePlanMapper.status("completed")).isEqualTo(CarePlanStatus.COMPLETED);
    assertThat(CarePlanMapper.status("cancelled")).isEqualTo(CarePlanStatus.REVOKED);
    assertThat(CarePlanMapper.itemStatus("planned")).isEqualTo(CarePlanActivityStatus.NOTSTARTED);
    assertThat(CarePlanMapper.itemStatus("cancelled")).isEqualTo(CarePlanActivityStatus.CANCELLED);
  }
}
