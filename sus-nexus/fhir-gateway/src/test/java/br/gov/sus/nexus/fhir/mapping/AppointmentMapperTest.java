package br.gov.sus.nexus.fhir.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.gov.sus.nexus.fhir.FhirConstants;
import org.hl7.fhir.r4.model.Appointment;
import org.hl7.fhir.r4.model.Appointment.AppointmentStatus;
import org.junit.jupiter.api.Test;

class AppointmentMapperTest {

  private final AppointmentMapper mapper = new AppointmentMapper(MapperSettings.defaults());

  @Test
  void mapsCanonicalAppointment() {
    CanonicalAppointment c =
        MapperTestSupport.canonical("canonical-appointment.json", CanonicalAppointment.class);
    Appointment a = mapper.map(c);

    assertThat(a.getId()).isEqualTo("01J0000000000000000000APT1");
    assertThat(a.getMeta().getProfile().get(0).getValue()).endsWith("SUSNexusAppointment");
    assertThat(a.getStatus()).isEqualTo(AppointmentStatus.BOOKED);
    assertThat(a.getServiceTypeFirstRep().getCodingFirstRep().getSystem())
        .isEqualTo("http://www.saude.gov.br/fhir/r4/CodeSystem/BRTabelaSUS");
    assertThat(a.getServiceTypeFirstRep().getCodingFirstRep().getCode()).isEqualTo("0301010072");
    assertThat(a.getParticipant()).hasSize(3);
    assertThat(a.getParticipant().get(0).getActor().getReference())
        .isEqualTo("Patient/01HZX4Y5K6M7N8P9Q0R1S2T3U4");
    assertThat(a.getParticipant().get(1).getActor().getIdentifier().getSystem())
        .isEqualTo(FhirConstants.SYSTEM_CNES);
    assertThat(a.getParticipant().get(1).getActor().getType()).isEqualTo("Location");
    assertThat(a.getExtensionByUrl(FhirConstants.EXT_APPOINTMENT_KIND).getValue().primitiveValue())
        .isEqualTo("regulated");
    assertThat(a.getExtensionByUrl(FhirConstants.EXT_CARE_LINE).getValue().primitiveValue())
        .isEqualTo("hipertensao");
    assertThat(a.getBasedOnFirstRep().getReference())
        .isEqualTo("ServiceRequest/01J0000000000000000000REG1");
    assertThat(a.getStartElement().getValueAsString()).startsWith("2026-10-10T");
    assertThat(a.hasEnd()).isTrue();
    assertThat(
            a.getIdentifier().stream()
                .anyMatch(i -> FhirConstants.SYSTEM_MUNICIPAL_APPOINTMENT_ID.equals(i.getSystem())))
        .isTrue();
  }

  @Test
  void statusMappingCoversCanonicalEnum() {
    assertThat(AppointmentMapper.status("proposed")).isEqualTo(AppointmentStatus.PROPOSED);
    assertThat(AppointmentMapper.status("confirmed")).isEqualTo(AppointmentStatus.BOOKED);
    assertThat(AppointmentMapper.status("arrived")).isEqualTo(AppointmentStatus.ARRIVED);
    assertThat(AppointmentMapper.status("fulfilled")).isEqualTo(AppointmentStatus.FULFILLED);
    assertThat(AppointmentMapper.status("cancelled")).isEqualTo(AppointmentStatus.CANCELLED);
    assertThat(AppointmentMapper.status("noshow")).isEqualTo(AppointmentStatus.NOSHOW);
    assertThat(AppointmentMapper.status("waitlist")).isEqualTo(AppointmentStatus.WAITLIST);
    assertThatThrownBy(() -> AppointmentMapper.status("x"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void missingEndFallsBackToStartAndCancellationBecomesReason() {
    CanonicalAppointment c =
        MapperTestSupport.canonical("canonical-appointment.json", CanonicalAppointment.class);
    CanonicalAppointment cancelled =
        new CanonicalAppointment(
            c.id(),
            c.citizenId(),
            "cancelled",
            "direct",
            null,
            null,
            null,
            null,
            null,
            c.scheduledStart(),
            null,
            null,
            null,
            null,
            "paciente desistiu",
            "PEC",
            null,
            1,
            null);
    Appointment a = mapper.map(cancelled);
    assertThat(a.getEnd()).isEqualTo(a.getStart());
    assertThat(a.getCancelationReason().getText()).isEqualTo("paciente desistiu");
    assertThat(a.hasServiceType()).isFalse();
    assertThat(a.getParticipant()).hasSize(1);
  }
}
