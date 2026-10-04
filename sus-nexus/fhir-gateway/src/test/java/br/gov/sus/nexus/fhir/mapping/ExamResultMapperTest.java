package br.gov.sus.nexus.fhir.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.gov.sus.nexus.fhir.FhirConstants;
import java.time.Instant;
import org.hl7.fhir.r4.model.DiagnosticReport;
import org.hl7.fhir.r4.model.DiagnosticReport.DiagnosticReportStatus;
import org.hl7.fhir.r4.model.DocumentReference;
import org.hl7.fhir.r4.model.Enumerations.DocumentReferenceStatus;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Observation.ObservationStatus;
import org.hl7.fhir.r4.model.Quantity;
import org.junit.jupiter.api.Test;

class ExamResultMapperTest {

  private final ExamResultMapper mapper = new ExamResultMapper(MapperSettings.defaults());

  private static CanonicalExamOrder order() {
    return MapperTestSupport.canonical(
        "canonical-exam-result-order.json", CanonicalExamOrder.class);
  }

  private static CanonicalExamResult withStatus(CanonicalExamResult r, String status) {
    return new CanonicalExamResult(
        r.id(),
        r.reportedAt(),
        status,
        r.critical(),
        r.performerCnes(),
        r.hasDocument(),
        r.observationsCount(),
        r.followupTaskId(),
        r.sourceSystem(),
        r.sourceRecordId(),
        r.documentRef(),
        r.documentContentType(),
        r.documentSha256(),
        r.binaryId(),
        r.observations());
  }

  @Test
  void mapsReportObservationsAndDocument() {
    CanonicalExamOrder o = order();
    ExamResultMapper.Mapped m = mapper.map(o, o.results().get(0));

    DiagnosticReport r = m.report();
    assertThat(r.getId()).isEqualTo("01J0000000000000000000EXR3");
    assertThat(r.getStatus()).isEqualTo(DiagnosticReportStatus.FINAL);
    assertThat(r.getMeta().getProfile().get(0).getValue()).endsWith("SUSNexusDiagnosticReport");
    assertThat(r.getCategoryFirstRep().getCodingFirstRep().getCode()).isEqualTo("LAB");
    assertThat(r.getCode().getCodingFirstRep().getCode()).isEqualTo("0202010503");
    assertThat(r.getSubject().getReference()).isEqualTo("Patient/01HZX4Y5K6M7N8P9Q0R1S2T3U4");
    assertThat(r.getBasedOnFirstRep().getReference())
        .isEqualTo("ServiceRequest/01J0000000000000000000EXO3");
    assertThat(r.getPerformerFirstRep().getIdentifier().getValue()).isEqualTo("2112345");
    assertThat(r.getIssued()).isEqualTo(Instant.parse("2026-09-29T15:00:00Z"));
    assertThat(r.getResult()).hasSize(3);
    assertThat(r.getResult().get(0).getReference())
        .isEqualTo("Observation/01J0000000000000000000EXR3-obs-1");
    // conteúdo nunca inline; sem binary_id não há presentedForm
    assertThat(r.hasPresentedForm()).isFalse();

    assertThat(m.observations()).hasSize(3);
    Observation hb = m.observations().get(0);
    assertThat(hb.getStatus()).isEqualTo(ObservationStatus.FINAL);
    assertThat(hb.getCode().getCodingFirstRep().getSystem()).isEqualTo("http://loinc.org");
    Quantity q = hb.getValueQuantity();
    assertThat(q.getValue()).isEqualByComparingTo("13.5");
    assertThat(q.getSystem()).isEqualTo("http://unitsofmeasure.org");
    assertThat(hb.getInterpretationFirstRep().getCodingFirstRep().getCode()).isEqualTo("N");
    assertThat(m.observations().get(1).getInterpretationFirstRep().getCodingFirstRep().getCode())
        .isEqualTo("A");
    assertThat(m.observations().get(2).getValueStringType().getValue()).isEqualTo("Reagente ***");
    assertThat(m.observations().get(2).getCode().getCodingFirstRep().getSystem())
        .isEqualTo(MapperSettings.defaults().localSystem());

    DocumentReference doc = m.document().orElseThrow();
    assertThat(doc.getId()).isEqualTo("01J0000000000000000000EXR3-doc");
    assertThat(doc.getStatus()).isEqualTo(DocumentReferenceStatus.CURRENT);
    assertThat(doc.getType().getCodingFirstRep().getCode())
        .isEqualTo(FhirConstants.LOINC_LAB_REPORT);
    assertThat(doc.getContentFirstRep().getAttachment().hasData()).isFalse();
    assertThat(doc.getContentFirstRep().getAttachment().hasUrl()).isFalse();
    assertThat(doc.getExtensionByUrl(FhirConstants.EXT_DOCUMENT_REF).getValue().primitiveValue())
        .isEqualTo("s3://lab-origem/laudos/9911.pdf");
    assertThat(doc.getContext().getRelated()).hasSize(2);
    assertThat(m.all()).hasSize(5);
  }

  @Test
  void statusMappingIncludesInconclusiveAsPartialWithExtension() {
    CanonicalExamOrder o = order();
    DiagnosticReport partial =
        mapper.map(o, withStatus(o.results().get(0), "inconclusive")).report();
    assertThat(partial.getStatus()).isEqualTo(DiagnosticReportStatus.PARTIAL);
    assertThat(
            partial
                .getExtensionByUrl(FhirConstants.EXT_EXAM_RESULT_STATUS)
                .getValue()
                .primitiveValue())
        .isEqualTo("inconclusive");
    ExamResultMapper.Mapped cancelled = mapper.map(o, withStatus(o.results().get(0), "cancelled"));
    assertThat(cancelled.report().getStatus()).isEqualTo(DiagnosticReportStatus.CANCELLED);
    assertThat(cancelled.observations().get(0).getStatus()).isEqualTo(ObservationStatus.CANCELLED);
    assertThat(cancelled.document().orElseThrow().getStatus())
        .isEqualTo(DocumentReferenceStatus.ENTEREDINERROR);
    assertThat(ExamResultMapper.reportStatus("preliminary"))
        .isEqualTo(DiagnosticReportStatus.PRELIMINARY);
    assertThat(ExamResultMapper.reportStatus("amended")).isEqualTo(DiagnosticReportStatus.AMENDED);
    assertThatThrownBy(() -> ExamResultMapper.reportStatus("weird"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void criticalAbnormalAndBinaryLinkedDocument() {
    CanonicalExamOrder o = order();
    CanonicalExamResult r = o.results().get(0);
    CanonicalExamResult critical =
        new CanonicalExamResult(
            r.id(),
            r.reportedAt(),
            "final",
            true,
            null,
            false,
            0,
            null,
            "LAB",
            null,
            null,
            "application/pdf",
            null,
            "01J0000000000000000000BIN1",
            r.observations());
    ExamResultMapper.Mapped m = mapper.map(o, critical);
    assertThat(m.observations().get(1).getInterpretationFirstRep().getCodingFirstRep().getCode())
        .isEqualTo("AA");
    assertThat(m.report().getPresentedFormFirstRep().getUrl())
        .isEqualTo("http://localhost:8081/fhir/r4/Binary/01J0000000000000000000BIN1");
    assertThat(m.report().getPresentedFormFirstRep().hasData()).isFalse();
    assertThat(m.document().orElseThrow().getContentFirstRep().getAttachment().getUrl())
        .endsWith("/Binary/01J0000000000000000000BIN1");
    assertThat(m.report().hasPerformer()).isFalse();

    // sem documento nem binário → sem DocumentReference
    CanonicalExamResult bare =
        new CanonicalExamResult(
            r.id(),
            r.reportedAt(),
            "final",
            null,
            null,
            false,
            0,
            null,
            "LAB",
            null,
            null,
            null,
            null,
            null,
            null);
    ExamResultMapper.Mapped none = mapper.map(o, bare);
    assertThat(none.document()).isEmpty();
    assertThat(none.observations()).isEmpty();
    assertThat(none.report().hasResult()).isFalse();
  }

  @Test
  void imagingCategory() {
    CanonicalExamOrder o = order();
    CanonicalExamOrder imaging =
        new CanonicalExamOrder(
            o.id(),
            o.citizenId(),
            o.status(),
            o.requestedAt(),
            "0204030153",
            "SIGTAP",
            "RX tórax",
            "imaging",
            o.priority(),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            o.performedAt(),
            o.reportedAt(),
            null,
            null,
            o.results(),
            "RIS",
            null,
            1);
    ExamResultMapper.Mapped m = mapper.map(imaging, o.results().get(0));
    assertThat(m.report().getCategoryFirstRep().getCodingFirstRep().getCode()).isEqualTo("RAD");
    assertThat(m.document().orElseThrow().getType().getCodingFirstRep().getCode())
        .isEqualTo(FhirConstants.LOINC_IMAGING_REPORT);
    assertThat(m.observations().get(0).getCategoryFirstRep().getCodingFirstRep().getCode())
        .isEqualTo("imaging");
  }
}
