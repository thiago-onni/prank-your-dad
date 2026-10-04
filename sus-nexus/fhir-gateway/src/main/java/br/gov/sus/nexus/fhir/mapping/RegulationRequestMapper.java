package br.gov.sus.nexus.fhir.mapping;

import static br.gov.sus.nexus.fhir.mapping.MappingSupport.date;
import static br.gov.sus.nexus.fhir.mapping.MappingSupport.extensionCode;
import static br.gov.sus.nexus.fhir.mapping.MappingSupport.isBlank;
import static br.gov.sus.nexus.fhir.mapping.MappingSupport.lower;

import br.gov.sus.nexus.fhir.FhirConstants;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.hl7.fhir.r4.model.Annotation;
import org.hl7.fhir.r4.model.BooleanType;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.IntegerType;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.ServiceRequest;
import org.hl7.fhir.r4.model.ServiceRequest.ServiceRequestIntent;
import org.hl7.fhir.r4.model.ServiceRequest.ServiceRequestPriority;
import org.hl7.fhir.r4.model.ServiceRequest.ServiceRequestStatus;

/**
 * Canônico {@code RegulationRequest} → FHIR {@code ServiceRequest} ({@code intent=order}).
 *
 * <p>Status: requested/pending_documents/under_review/scheduled/no_show→active; returned→on-hold;
 * authorized→active + extensão {@code regulation-authorized=true}; performed→completed;
 * denied/cancelled/expired→revoked. O status canônico íntegro fica na extensão {@code
 * regulation-status}. Prioridade: elective→routine, priority→urgent, urgent→asap, emergency→stat.
 * {@code category} = kind (CodeSystem {@code regulation-kind}); {@code code} SIGTAP ou local;
 * {@code requester} Organization pelo CNES solicitante (PractitionerRole lógico quando só há
 * profissional); {@code performer} Organization prestadora; {@code occurrence} = scheduled_at;
 * {@code performerType.text} = especialidade.
 */
@ApplicationScoped
public class RegulationRequestMapper {

  @Inject MapperSettings settings;

  public RegulationRequestMapper() {}

  public RegulationRequestMapper(MapperSettings settings) {
    this.settings = settings;
  }

  public ServiceRequest map(CanonicalRegulationRequest r) {
    ServiceRequest sr = new ServiceRequest();
    sr.setId(CanonicalIds.toFhirId(r.id()));
    sr.getMeta().addProfile(settings.serviceRequestProfile());
    sr.addIdentifier(
        MappingSupport.identifier(FhirConstants.SYSTEM_MUNICIPAL_REGULATION_REQUEST_ID, r.id()));
    MappingSupport.sourceIdentifier(r.sourceSystem(), r.sourceRecordId())
        .ifPresent(sr::addIdentifier);

    sr.setStatus(status(r.status()));
    extensionCode(sr, FhirConstants.EXT_REGULATION_STATUS, r.status());
    if (isAuthorized(r.status())) {
      sr.addExtension(FhirConstants.EXT_REGULATION_AUTHORIZED, new BooleanType(true));
    }
    sr.setIntent(ServiceRequestIntent.ORDER);
    sr.setPriority(priority(r.priority()));
    extensionCode(sr, FhirConstants.EXT_REGULATION_PRIORITY, r.priority());

    sr.addCategory(
        new CodeableConcept()
            .addCoding(new Coding().setSystem(FhirConstants.CS_REGULATION_KIND).setCode(r.kind()))
            .setText(r.kind()));
    CodeableConcept code = new CodeableConcept();
    code.addCoding(
        MappingSupport.procedureCoding(
            settings, r.codeSystem(), r.requestedServiceCode(), r.serviceDescription()));
    if (!isBlank(r.serviceDescription())) {
      code.setText(r.serviceDescription());
    }
    sr.setCode(code);
    sr.setSubject(MappingSupport.patientRef(r.citizenId()));
    if (r.requestedAt() != null) {
      sr.setAuthoredOnElement(new DateTimeType(date(r.requestedAt())));
    }
    if (!isBlank(r.requestingCnes())) {
      Reference req = MappingSupport.organizationByCnes(r.requestingCnes());
      if (!isBlank(r.requestingUnitName())) {
        req.setDisplay(r.requestingUnitName());
      }
      sr.setRequester(req);
    } else if (!isBlank(r.requestingProfessionalId())) {
      sr.setRequester(
          MappingSupport.logical(
              "PractitionerRole",
              FhirConstants.SYSTEM_MUNICIPAL_PROFESSIONAL_ID,
              r.requestingProfessionalId()));
    }
    if (!isBlank(r.providerCnes())) {
      Reference perf = MappingSupport.organizationByCnes(r.providerCnes());
      if (!isBlank(r.providerName())) {
        perf.setDisplay(r.providerName());
      }
      sr.addPerformer(perf);
    }
    if (!isBlank(r.specialty())) {
      sr.setPerformerType(new CodeableConcept().setText(r.specialty()));
    }
    if (r.scheduledAt() != null) {
      sr.setOccurrence(new DateTimeType(date(r.scheduledAt())));
    }
    MappingSupport.referenceFromPrefixedId(r.appointmentId())
        .ifPresent(ref -> sr.addExtension(FhirConstants.EXT_SCHEDULED_APPOINTMENT, ref));
    if (!isBlank(r.decisionReason())) {
      sr.addNote(new Annotation().setText(r.decisionReason()));
    }
    if (r.waitingDays() != null) {
      sr.addExtension(FhirConstants.EXT_WAITING_DAYS, new IntegerType(r.waitingDays()));
    }
    if (r.justificationPresent() != null) {
      sr.addExtension(
          FhirConstants.EXT_JUSTIFICATION_PRESENT, new BooleanType(r.justificationPresent()));
    }
    if (r.slaDueAt() != null) {
      sr.addExtension(FhirConstants.EXT_SLA_DUE_AT, new DateTimeType(date(r.slaDueAt())));
    }
    return sr;
  }

  static boolean isAuthorized(String canonical) {
    return switch (lower(canonical)) {
      case "authorized", "scheduled", "performed", "no_show" -> true;
      default -> false;
    };
  }

  public static ServiceRequestStatus status(String canonical) {
    return switch (lower(canonical)) {
      case "requested", "pending_documents", "under_review", "authorized", "scheduled", "no_show" ->
          ServiceRequestStatus.ACTIVE;
      case "returned" -> ServiceRequestStatus.ONHOLD;
      case "performed" -> ServiceRequestStatus.COMPLETED;
      case "denied", "cancelled", "expired" -> ServiceRequestStatus.REVOKED;
      default -> throw new IllegalArgumentException("RegulationStatus canônico desconhecido");
    };
  }

  public static ServiceRequestPriority priority(String canonical) {
    return switch (lower(canonical)) {
      case "", "elective" -> ServiceRequestPriority.ROUTINE;
      case "priority" -> ServiceRequestPriority.URGENT;
      case "urgent" -> ServiceRequestPriority.ASAP;
      case "emergency" -> ServiceRequestPriority.STAT;
      default -> throw new IllegalArgumentException("RegulationPriority canônica desconhecida");
    };
  }
}
