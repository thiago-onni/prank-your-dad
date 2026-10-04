package br.gov.sus.nexus.fhir.mapping;

import static br.gov.sus.nexus.fhir.mapping.MappingSupport.date;
import static br.gov.sus.nexus.fhir.mapping.MappingSupport.isBlank;
import static br.gov.sus.nexus.fhir.mapping.MappingSupport.lower;

import br.gov.sus.nexus.fhir.FhirConstants;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.hl7.fhir.r4.model.Attachment;
import org.hl7.fhir.r4.model.BooleanType;
import org.hl7.fhir.r4.model.CodeType;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.DiagnosticReport;
import org.hl7.fhir.r4.model.DiagnosticReport.DiagnosticReportStatus;
import org.hl7.fhir.r4.model.DocumentReference;
import org.hl7.fhir.r4.model.DocumentReference.ReferredDocumentStatus;
import org.hl7.fhir.r4.model.DomainResource;
import org.hl7.fhir.r4.model.Enumerations.DocumentReferenceStatus;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Observation.ObservationStatus;
import org.hl7.fhir.r4.model.Quantity;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.StringType;

/**
 * Canônico {@code ExamOrder} + {@code ExamResult} → {@code DiagnosticReport} (+ uma {@code
 * Observation} por item de {@code observations[]} e um {@code DocumentReference} quando {@code
 * has_document}).
 *
 * <ul>
 *   <li>Status: final→final, preliminary→preliminary, amended→amended, inconclusive→<b>partial</b>
 *       (canônico íntegro na extensão {@code exam-result-status}), cancelled→cancelled.
 *   <li>{@code category} v2-0074: laboratory→LAB, imaging→RAD, demais→OTH (+ {@code
 *       exam-category} municipal); {@code code} = {@code exam_code} do pedido; {@code basedOn} =
 *       {@code ServiceRequest/<id do pedido>}; {@code performer} Organization pelo CNES (lógica,
 *       resolvida na projeção); {@code conclusionCode} ausente (o canônico não traz conclusão).
 *   <li>{@code Observation}: {@code valueQuantity} (UCUM) ou {@code valueString} mascarado;
 *       {@code interpretation} A (abnormal) / AA (abnormal + critical) — H/L/HH/LL exigem faixa de
 *       referência, inexistente no canônico; {@code code} LOINC/SIGTAP/LOCAL por {@code
 *       code_system}.
 *   <li>{@code DocumentReference}: {@code type} LOINC 11502-2 (laboratório) ou 18748-4 (imagem);
 *       {@code content.attachment.url} = {@code {base}/Binary/{id}} quando o conteúdo foi copiado
 *       para o gateway ({@code binary_id}); senão a referência opaca fica na extensão {@code
 *       document-ref} e {@code attachment.url} é omitido (o link assinado do core é efêmero e nunca
 *       é persistido). Conteúdo inline nunca é gerado (invariante {@code sus-doc-1}).
 * </ul>
 */
@ApplicationScoped
public class ExamResultMapper {

  @Inject MapperSettings settings;

  public ExamResultMapper() {}

  public ExamResultMapper(MapperSettings settings) {
    this.settings = settings;
  }

  /** Recursos derivados de um resultado (laudo primeiro; Observations referenciadas por result). */
  public record Mapped(
      DiagnosticReport report, List<Observation> observations, Optional<DocumentReference> document) {

    public List<DomainResource> all() {
      List<DomainResource> list = new ArrayList<>(observations);
      list.add(report);
      document.ifPresent(list::add);
      return list;
    }
  }

  public Mapped map(CanonicalExamOrder order, CanonicalExamResult result) {
    String reportId = CanonicalIds.toFhirId(result.id());
    String patientId = CanonicalIds.toFhirId(order.citizenId());
    Reference subject = MappingSupport.patientRef(order.citizenId());
    Reference basedOn = new Reference("ServiceRequest/" + CanonicalIds.toFhirId(order.id()));
    CodeableConcept code = examCode(settings, order);
    String category = isBlank(order.category()) ? "other" : lower(order.category());

    List<Observation> observations = new ArrayList<>();
    if (result.observations() != null) {
      int n = 0;
      for (CanonicalExamResult.Observation item : result.observations()) {
        n++;
        observations.add(observation(reportId + "-obs-" + n, item, result, order, subject, basedOn));
      }
    }

    DiagnosticReport report = new DiagnosticReport();
    report.setId(reportId);
    report.getMeta().addProfile(settings.diagnosticReportProfile());
    report.addIdentifier(
        MappingSupport.identifier(FhirConstants.SYSTEM_MUNICIPAL_EXAM_RESULT_ID, result.id()));
    MappingSupport.sourceIdentifier(result.sourceSystem(), result.sourceRecordId())
        .ifPresent(report::addIdentifier);
    report.addBasedOn(basedOn);
    report.setStatus(reportStatus(result.status()));
    report.addExtension(FhirConstants.EXT_EXAM_RESULT_STATUS, new CodeType(lower(result.status())));
    if (result.critical() != null) {
      report.addExtension(FhirConstants.EXT_EXAM_RESULT_CRITICAL, new BooleanType(result.critical()));
    }
    report.addCategory(reportCategory(category));
    report.setCode(code);
    report.setSubject(subject);
    if (order.performedAt() != null) {
      report.setEffective(new DateTimeType(date(order.performedAt())));
    }
    if (result.reportedAt() != null) {
      report.setIssued(date(result.reportedAt()));
    }
    if (!isBlank(result.performerCnes())) {
      report.addPerformer(MappingSupport.organizationByCnes(result.performerCnes()));
    }
    for (Observation o : observations) {
      report.addResult(new Reference("Observation/" + o.getIdElement().getIdPart()));
    }
    if (!isBlank(result.binaryId())) {
      report.addPresentedForm(
          new Attachment()
              .setContentType(
                  isBlank(result.documentContentType()) ? "application/pdf" : result.documentContentType())
              .setUrl(settings.fhirBaseUrl() + "/Binary/" + result.binaryId()));
    }
    MappingSupport.extensionString(report, FhirConstants.EXT_CARE_LINE, order.careLine());

    Optional<DocumentReference> document =
        Boolean.TRUE.equals(result.hasDocument()) || !isBlank(result.documentRef()) || !isBlank(result.binaryId())
            ? Optional.of(document(reportId, order, result, category, subject, basedOn, patientId))
            : Optional.empty();
    return new Mapped(report, observations, document);
  }

  private Observation observation(
      String id,
      CanonicalExamResult.Observation item,
      CanonicalExamResult result,
      CanonicalExamOrder order,
      Reference subject,
      Reference basedOn) {
    Observation obs = new Observation();
    obs.setId(id);
    obs.getMeta().addProfile(settings.observationProfile());
    obs.setStatus(observationStatus(result.status()));
    obs.addExtension(FhirConstants.EXT_EXAM_RESULT_STATUS, new CodeType(lower(result.status())));
    obs.addCategory(
        new CodeableConcept()
            .addCoding(
                new Coding()
                    .setSystem(FhirConstants.CS_OBSERVATION_CATEGORY)
                    .setCode(observationCategory(order.category()))));
    obs.setCode(
        new CodeableConcept()
            .addCoding(MappingSupport.procedureCoding(settings, item.codeSystem(), item.code(), null)));
    obs.setSubject(subject);
    obs.addBasedOn(basedOn);
    if (order.performedAt() != null) {
      obs.setEffective(new DateTimeType(date(order.performedAt())));
    }
    if (result.reportedAt() != null) {
      obs.setIssued(date(result.reportedAt()));
    }
    if (!isBlank(result.performerCnes())) {
      obs.addPerformer(MappingSupport.organizationByCnes(result.performerCnes()));
    }
    if (item.value() != null) {
      Quantity q = new Quantity().setValue(item.value());
      if (!isBlank(item.unit())) {
        q.setUnit(item.unit()).setSystem(settings.ucumSystem()).setCode(item.unit());
      }
      obs.setValue(q);
    } else if (!isBlank(item.valueTextMasked())) {
      obs.setValue(new StringType(item.valueTextMasked()));
    }
    if (Boolean.TRUE.equals(item.abnormal())) {
      boolean critical = Boolean.TRUE.equals(result.critical());
      obs.addInterpretation(
          new CodeableConcept()
              .addCoding(
                  new Coding()
                      .setSystem(FhirConstants.CS_V3_OBSERVATION_INTERPRETATION)
                      .setCode(critical ? "AA" : "A")
                      .setDisplay(critical ? "Critical abnormal" : "Abnormal")));
    } else if (Boolean.FALSE.equals(item.abnormal())) {
      obs.addInterpretation(
          new CodeableConcept()
              .addCoding(
                  new Coding()
                      .setSystem(FhirConstants.CS_V3_OBSERVATION_INTERPRETATION)
                      .setCode("N")
                      .setDisplay("Normal")));
    }
    return obs;
  }

  private DocumentReference document(
      String reportId,
      CanonicalExamOrder order,
      CanonicalExamResult result,
      String category,
      Reference subject,
      Reference basedOn,
      String patientId) {
    DocumentReference doc = new DocumentReference();
    doc.setId(reportId + "-doc");
    doc.getMeta().addProfile(settings.documentReferenceProfile());
    doc.addIdentifier(
        MappingSupport.identifier(FhirConstants.SYSTEM_MUNICIPAL_EXAM_RESULT_ID, result.id()));
    doc.setStatus(
        "cancelled".equals(lower(result.status()))
            ? DocumentReferenceStatus.ENTEREDINERROR
            : DocumentReferenceStatus.CURRENT);
    docStatus(result.status()).ifPresent(doc::setDocStatus);
    boolean imaging = "imaging".equals(category);
    doc.setType(
        new CodeableConcept()
            .addCoding(
                new Coding()
                    .setSystem(FhirConstants.CS_LOINC)
                    .setCode(imaging ? FhirConstants.LOINC_IMAGING_REPORT : FhirConstants.LOINC_LAB_REPORT)
                    .setDisplay(imaging ? "Diagnostic imaging study" : "Laboratory report")));
    doc.addCategory(reportCategory(category));
    doc.setSubject(subject);
    if (result.reportedAt() != null) {
      doc.setDate(date(result.reportedAt()));
    }
    if (!isBlank(result.performerCnes())) {
      doc.addAuthor(MappingSupport.organizationByCnes(result.performerCnes()));
    }
    Attachment attachment = new Attachment();
    attachment.setContentType(
        isBlank(result.documentContentType()) ? "application/pdf" : result.documentContentType());
    if (!isBlank(result.binaryId())) {
      attachment.setUrl(settings.fhirBaseUrl() + "/Binary/" + result.binaryId());
    } else if (!isBlank(result.documentRef())) {
      doc.addExtension(FhirConstants.EXT_DOCUMENT_REF, new StringType(result.documentRef()));
    }
    if (!isBlank(result.documentSha256())) {
      doc.addExtension(FhirConstants.EXT_DOCUMENT_SHA256, new StringType(result.documentSha256()));
    }
    attachment.setTitle(isBlank(order.examDescription()) ? "Laudo" : "Laudo: " + order.examDescription());
    doc.addContent().setAttachment(attachment);
    doc.getContext().addRelated(basedOn);
    doc.getContext().addRelated(new Reference("DiagnosticReport/" + reportId));
    return doc;
  }

  static CodeableConcept examCode(MapperSettings settings, CanonicalExamOrder order) {
    CodeableConcept code = new CodeableConcept();
    code.addCoding(
        MappingSupport.procedureCoding(
            settings, order.codeSystem(), order.examCode(), order.examDescription()));
    if (!isBlank(order.examDescription())) {
      code.setText(order.examDescription());
    }
    return code;
  }

  static CodeableConcept reportCategory(String canonical) {
    String v2 =
        switch (canonical) {
          case "laboratory" -> "LAB";
          case "imaging" -> "RAD";
          default -> "OTH";
        };
    return new CodeableConcept()
        .addCoding(new Coding().setSystem(FhirConstants.CS_V2_0074).setCode(v2))
        .addCoding(new Coding().setSystem(FhirConstants.CS_EXAM_CATEGORY).setCode(canonical));
  }

  static String observationCategory(String canonical) {
    return "imaging".equals(lower(canonical)) ? "imaging" : "laboratory";
  }

  public static DiagnosticReportStatus reportStatus(String canonical) {
    return switch (lower(canonical)) {
      case "final" -> DiagnosticReportStatus.FINAL;
      case "preliminary" -> DiagnosticReportStatus.PRELIMINARY;
      case "amended" -> DiagnosticReportStatus.AMENDED;
      case "inconclusive" -> DiagnosticReportStatus.PARTIAL;
      case "cancelled" -> DiagnosticReportStatus.CANCELLED;
      default -> throw new IllegalArgumentException("Status de resultado canônico desconhecido");
    };
  }

  public static ObservationStatus observationStatus(String canonical) {
    return switch (lower(canonical)) {
      case "final" -> ObservationStatus.FINAL;
      case "preliminary" -> ObservationStatus.PRELIMINARY;
      case "amended" -> ObservationStatus.AMENDED;
      case "inconclusive" -> ObservationStatus.UNKNOWN;
      case "cancelled" -> ObservationStatus.CANCELLED;
      default -> throw new IllegalArgumentException("Status de resultado canônico desconhecido");
    };
  }

  static Optional<ReferredDocumentStatus> docStatus(String canonical) {
    return switch (lower(canonical)) {
      case "final", "inconclusive" -> Optional.of(ReferredDocumentStatus.FINAL);
      case "preliminary" -> Optional.of(ReferredDocumentStatus.PRELIMINARY);
      case "amended" -> Optional.of(ReferredDocumentStatus.AMENDED);
      case "cancelled" -> Optional.of(ReferredDocumentStatus.ENTEREDINERROR);
      default -> Optional.empty();
    };
  }
}
