package br.gov.sus.nexus.fhir.mapping;

import static br.gov.sus.nexus.fhir.mapping.MappingSupport.date;
import static br.gov.sus.nexus.fhir.mapping.MappingSupport.extensionCode;
import static br.gov.sus.nexus.fhir.mapping.MappingSupport.extensionString;
import static br.gov.sus.nexus.fhir.mapping.MappingSupport.isBlank;
import static br.gov.sus.nexus.fhir.mapping.MappingSupport.lower;

import br.gov.sus.nexus.fhir.FhirConstants;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.hl7.fhir.r4.model.Appointment;
import org.hl7.fhir.r4.model.Appointment.AppointmentParticipantComponent;
import org.hl7.fhir.r4.model.Appointment.AppointmentStatus;
import org.hl7.fhir.r4.model.Appointment.ParticipantRequired;
import org.hl7.fhir.r4.model.Appointment.ParticipationStatus;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Reference;

/**
 * Canônico {@code Appointment} → FHIR {@code Appointment}.
 *
 * <p>Status: proposed→proposed, booked/confirmed→booked, arrived→arrived, fulfilled→fulfilled,
 * cancelled→cancelled, noshow→noshow, waitlist→waitlist (o status canônico íntegro fica na extensão
 * {@code appointment-status}). {@code serviceType} com SIGTAP/local conforme {@code code_system};
 * participantes: Patient (obrigatório) e Location pelo CNES (Appointment.participant.actor não
 * admite Organization; resolvida para referência direta quando a Location já foi projetada);
 * profissional como referência lógica. {@code kind} em extensão. Invariante app-2 (start e end
 * juntos): sem {@code scheduled_end}, {@code end = start}.
 */
@ApplicationScoped
public class AppointmentMapper {

  @Inject MapperSettings settings;

  public AppointmentMapper() {}

  public AppointmentMapper(MapperSettings settings) {
    this.settings = settings;
  }

  public Appointment map(CanonicalAppointment a) {
    Appointment ap = new Appointment();
    ap.setId(CanonicalIds.toFhirId(a.id()));
    ap.getMeta().addProfile(settings.appointmentProfile());
    ap.addIdentifier(
        MappingSupport.identifier(FhirConstants.SYSTEM_MUNICIPAL_APPOINTMENT_ID, a.id()));
    MappingSupport.sourceIdentifier(a.sourceSystem(), a.sourceRecordId())
        .ifPresent(ap::addIdentifier);

    ap.setStatus(status(a.status()));
    extensionCode(ap, FhirConstants.EXT_APPOINTMENT_STATUS, a.status());
    extensionCode(ap, FhirConstants.EXT_APPOINTMENT_KIND, a.kind());
    extensionString(ap, FhirConstants.EXT_CARE_LINE, a.careLine());

    if (!isBlank(a.serviceCode())) {
      CodeableConcept st = new CodeableConcept();
      st.addCoding(
          MappingSupport.procedureCoding(
              settings, a.codeSystem(), a.serviceCode(), a.serviceDescription()));
      if (!isBlank(a.serviceDescription())) {
        st.setText(a.serviceDescription());
      }
      ap.addServiceType(st);
    }
    if (!isBlank(a.serviceDescription())) {
      ap.setDescription(a.serviceDescription());
    }
    if (a.scheduledStart() != null) {
      ap.setStart(date(a.scheduledStart()));
      ap.setEnd(date(a.scheduledEnd() == null ? a.scheduledStart() : a.scheduledEnd()));
    }
    if (!isBlank(a.cancellationReason())) {
      ap.setCancelationReason(new CodeableConcept().setText(a.cancellationReason()));
    }
    MappingSupport.referenceFromPrefixedId(a.regulationRequestId()).ifPresent(ap::addBasedOn);
    MappingSupport.referenceFromPrefixedId(a.examOrderId()).ifPresent(ap::addBasedOn);

    participant(ap, MappingSupport.patientRef(a.citizenId()), "SBJ", ParticipantRequired.REQUIRED);
    if (!isBlank(a.healthUnitCnes())) {
      participant(
          ap,
          MappingSupport.locationByCnes(a.healthUnitCnes()),
          "LOC",
          ParticipantRequired.REQUIRED);
    }
    if (!isBlank(a.professionalId())) {
      participant(
          ap,
          MappingSupport.logical(
              "PractitionerRole",
              FhirConstants.SYSTEM_MUNICIPAL_PROFESSIONAL_ID,
              a.professionalId()),
          "PPRF",
          ParticipantRequired.OPTIONAL);
    }
    return ap;
  }

  private static void participant(
      Appointment ap, Reference actor, String typeCode, ParticipantRequired required) {
    AppointmentParticipantComponent p = ap.addParticipant();
    p.addType(
        new CodeableConcept()
            .addCoding(
                new Coding().setSystem(FhirConstants.CS_PARTICIPATION_TYPE).setCode(typeCode)));
    p.setActor(actor);
    p.setRequired(required);
    p.setStatus(ParticipationStatus.ACCEPTED);
  }

  public static AppointmentStatus status(String canonical) {
    return switch (lower(canonical)) {
      case "proposed" -> AppointmentStatus.PROPOSED;
      case "booked", "confirmed" -> AppointmentStatus.BOOKED;
      case "arrived" -> AppointmentStatus.ARRIVED;
      case "fulfilled" -> AppointmentStatus.FULFILLED;
      case "cancelled" -> AppointmentStatus.CANCELLED;
      case "noshow" -> AppointmentStatus.NOSHOW;
      case "waitlist" -> AppointmentStatus.WAITLIST;
      default -> throw new IllegalArgumentException("AppointmentStatus canônico desconhecido");
    };
  }
}
