package br.gov.sus.nexus.fhir.mapping;

import static br.gov.sus.nexus.fhir.mapping.MappingSupport.date;
import static br.gov.sus.nexus.fhir.mapping.MappingSupport.extensionCode;
import static br.gov.sus.nexus.fhir.mapping.MappingSupport.extensionString;
import static br.gov.sus.nexus.fhir.mapping.MappingSupport.isBlank;
import static br.gov.sus.nexus.fhir.mapping.MappingSupport.lower;

import br.gov.sus.nexus.fhir.FhirConstants;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.hl7.fhir.r4.model.CodeType;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.ServiceRequest;
import org.hl7.fhir.r4.model.ServiceRequest.ServiceRequestIntent;
import org.hl7.fhir.r4.model.ServiceRequest.ServiceRequestPriority;
import org.hl7.fhir.r4.model.ServiceRequest.ServiceRequestStatus;

/**
 * Canônico {@code ExamOrder} → FHIR {@code ServiceRequest} com categoria laboratory/imaging (SNOMED
 * 108252007/363679005 + CodeSystem municipal {@code exam-category}). Status:
 * requested/authorized/scheduled/collected→active, performed/reported→completed,
 * cancelled/not_performed→revoked (canônico íntegro em {@code exam-order-status}). Resultados não
 * são projetados aqui: {@code DiagnosticReport}/{@code Observation} são FHIR-3.
 */
@ApplicationScoped
public class ExamOrderMapper {

  @Inject MapperSettings settings;

  public ExamOrderMapper() {}

  public ExamOrderMapper(MapperSettings settings) {
    this.settings = settings;
  }

  public ServiceRequest map(CanonicalExamOrder e) {
    ServiceRequest sr = new ServiceRequest();
    sr.setId(CanonicalIds.toFhirId(e.id()));
    sr.getMeta().addProfile(settings.serviceRequestProfile());
    sr.addIdentifier(
        MappingSupport.identifier(FhirConstants.SYSTEM_MUNICIPAL_EXAM_ORDER_ID, e.id()));
    MappingSupport.sourceIdentifier(e.sourceSystem(), e.sourceRecordId())
        .ifPresent(sr::addIdentifier);

    sr.setStatus(status(e.status()));
    extensionCode(sr, FhirConstants.EXT_EXAM_ORDER_STATUS, e.status());
    sr.setIntent(ServiceRequestIntent.ORDER);
    sr.setPriority(priority(e.priority()));
    sr.addCategory(category(e.category()));

    CodeableConcept code = new CodeableConcept();
    code.addCoding(
        MappingSupport.procedureCoding(
            settings, e.codeSystem(), e.examCode(), e.examDescription()));
    if (!isBlank(e.examDescription())) {
      code.setText(e.examDescription());
    }
    sr.setCode(code);
    sr.setSubject(MappingSupport.patientRef(e.citizenId()));
    if (e.requestedAt() != null) {
      sr.setAuthoredOnElement(new DateTimeType(date(e.requestedAt())));
    }
    if (!isBlank(e.requestingCnes())) {
      var req = MappingSupport.organizationByCnes(e.requestingCnes());
      if (!isBlank(e.requestingUnitName())) {
        req.setDisplay(e.requestingUnitName());
      }
      sr.setRequester(req);
    } else if (!isBlank(e.requestingProfessionalId())) {
      sr.setRequester(
          MappingSupport.logical(
              "PractitionerRole",
              FhirConstants.SYSTEM_MUNICIPAL_PROFESSIONAL_ID,
              e.requestingProfessionalId()));
    }
    if (!isBlank(e.performerCnes())) {
      sr.addPerformer(MappingSupport.organizationByCnes(e.performerCnes()));
    }
    if (e.scheduledAt() != null) {
      sr.setOccurrence(new DateTimeType(date(e.scheduledAt())));
    }
    MappingSupport.referenceFromPrefixedId(e.regulationRequestId()).ifPresent(sr::addBasedOn);
    MappingSupport.referenceFromPrefixedId(e.appointmentId())
        .ifPresent(ref -> sr.addExtension(FhirConstants.EXT_SCHEDULED_APPOINTMENT, ref));
    extensionString(sr, FhirConstants.EXT_CARE_LINE, e.careLine());
    if (e.issues() != null) {
      for (String issue : e.issues()) {
        sr.addExtension(FhirConstants.EXT_EXAM_ISSUE, new CodeType(issue));
      }
    }
    return sr;
  }

  static CodeableConcept category(String canonical) {
    CodeableConcept cc = new CodeableConcept();
    String c = isBlank(canonical) ? "other" : lower(canonical);
    switch (c) {
      case "laboratory" ->
          cc.addCoding(
              new Coding()
                  .setSystem(FhirConstants.CS_SNOMED)
                  .setCode("108252007")
                  .setDisplay("Laboratory procedure"));
      case "imaging" ->
          cc.addCoding(
              new Coding()
                  .setSystem(FhirConstants.CS_SNOMED)
                  .setCode("363679005")
                  .setDisplay("Imaging"));
      default -> {
        // sem equivalente SNOMED: apenas o código municipal
      }
    }
    cc.addCoding(new Coding().setSystem(FhirConstants.CS_EXAM_CATEGORY).setCode(c));
    cc.setText(c);
    return cc;
  }

  public static ServiceRequestStatus status(String canonical) {
    return switch (lower(canonical)) {
      case "requested", "authorized", "scheduled", "collected" -> ServiceRequestStatus.ACTIVE;
      case "performed", "reported" -> ServiceRequestStatus.COMPLETED;
      case "cancelled", "not_performed" -> ServiceRequestStatus.REVOKED;
      default -> throw new IllegalArgumentException("ExamOrderStatus canônico desconhecido");
    };
  }

  public static ServiceRequestPriority priority(String canonical) {
    return switch (lower(canonical)) {
      case "", "routine" -> ServiceRequestPriority.ROUTINE;
      case "priority" -> ServiceRequestPriority.URGENT;
      case "urgent" -> ServiceRequestPriority.ASAP;
      default -> throw new IllegalArgumentException("Prioridade de exame desconhecida");
    };
  }
}
