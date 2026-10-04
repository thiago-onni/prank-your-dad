package br.gov.sus.nexus.fhir.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import br.gov.sus.nexus.fhir.FhirConstants;
import org.hl7.fhir.r4.model.ServiceRequest;
import org.hl7.fhir.r4.model.ServiceRequest.ServiceRequestStatus;
import org.junit.jupiter.api.Test;

class ExamOrderMapperTest {

  private final ExamOrderMapper mapper = new ExamOrderMapper(MapperSettings.defaults());

  @Test
  void mapsCanonicalExamOrder() {
    CanonicalExamOrder c =
        MapperTestSupport.canonical("canonical-exam-order.json", CanonicalExamOrder.class);
    ServiceRequest sr = mapper.map(c);
    assertThat(sr.getId()).isEqualTo("01J0000000000000000000EXO1");
    assertThat(sr.getStatus()).isEqualTo(ServiceRequestStatus.COMPLETED);
    assertThat(sr.getCategoryFirstRep().getCoding())
        .anyMatch(
            cd ->
                FhirConstants.CS_SNOMED.equals(cd.getSystem()) && "108252007".equals(cd.getCode()))
        .anyMatch(
            cd ->
                FhirConstants.CS_EXAM_CATEGORY.equals(cd.getSystem())
                    && "laboratory".equals(cd.getCode()));
    assertThat(sr.getCode().getCodingFirstRep().getCode()).isEqualTo("0202010503");
    assertThat(sr.getCode().getText()).isEqualTo("Hemograma completo");
    assertThat(sr.getBasedOnFirstRep().getReference())
        .isEqualTo("ServiceRequest/01J0000000000000000000REG1");
    assertThat(sr.getPerformerFirstRep().getIdentifier().getValue()).isEqualTo("2222222");
    assertThat(sr.getExtensionsByUrl(FhirConstants.EXT_EXAM_ISSUE)).hasSize(1);
    assertThat(
            sr.getExtensionByUrl(FhirConstants.EXT_EXAM_ORDER_STATUS).getValue().primitiveValue())
        .isEqualTo("reported");
    // resultados não são projetados (FHIR-3)
    assertThat(sr.hasSupportingInfo()).isFalse();
  }

  @Test
  void statusAndCategoryMapping() {
    assertThat(ExamOrderMapper.status("requested")).isEqualTo(ServiceRequestStatus.ACTIVE);
    assertThat(ExamOrderMapper.status("collected")).isEqualTo(ServiceRequestStatus.ACTIVE);
    assertThat(ExamOrderMapper.status("performed")).isEqualTo(ServiceRequestStatus.COMPLETED);
    assertThat(ExamOrderMapper.status("cancelled")).isEqualTo(ServiceRequestStatus.REVOKED);
    assertThat(ExamOrderMapper.status("not_performed")).isEqualTo(ServiceRequestStatus.REVOKED);
    assertThat(ExamOrderMapper.category("imaging").getCodingFirstRep().getCode())
        .isEqualTo("363679005");
    assertThat(ExamOrderMapper.category("other").getCoding()).hasSize(1);
    assertThat(ExamOrderMapper.category(null).getText()).isEqualTo("other");
  }

  @Test
  void loincCodeSystem() {
    CanonicalExamOrder c =
        MapperTestSupport.canonical("canonical-exam-order.json", CanonicalExamOrder.class);
    CanonicalExamOrder loinc =
        new CanonicalExamOrder(
            c.id(),
            c.citizenId(),
            "requested",
            c.requestedAt(),
            "718-7",
            "LOINC",
            "Hemoglobina",
            "laboratory",
            "urgent",
            null,
            null,
            "prof_1",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            "LAB",
            null,
            1);
    ServiceRequest sr = mapper.map(loinc);
    assertThat(sr.getCode().getCodingFirstRep().getSystem()).isEqualTo("http://loinc.org");
    assertThat(sr.getPriority().toCode()).isEqualTo("asap");
    assertThat(sr.getRequester().getType()).isEqualTo("PractitionerRole");
  }
}
