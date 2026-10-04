package br.gov.sus.nexus.fhir.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import br.gov.sus.nexus.fhir.FhirConstants;
import org.hl7.fhir.r4.model.ServiceRequest;
import org.hl7.fhir.r4.model.ServiceRequest.ServiceRequestPriority;
import org.hl7.fhir.r4.model.ServiceRequest.ServiceRequestStatus;
import org.junit.jupiter.api.Test;

class RegulationRequestMapperTest {

  private final RegulationRequestMapper mapper =
      new RegulationRequestMapper(MapperSettings.defaults());

  @Test
  void mapsCanonicalRegulationRequest() {
    CanonicalRegulationRequest c =
        MapperTestSupport.canonical(
            "canonical-regulation-request.json", CanonicalRegulationRequest.class);
    ServiceRequest sr = mapper.map(c);
    assertThat(sr.getId()).isEqualTo("01J0000000000000000000REG1");
    assertThat(sr.getStatus()).isEqualTo(ServiceRequestStatus.ACTIVE);
    assertThat(sr.getIntent().toCode()).isEqualTo("order");
    assertThat(sr.getPriority()).isEqualTo(ServiceRequestPriority.ASAP);
    assertThat(sr.getCategoryFirstRep().getCodingFirstRep().getSystem())
        .isEqualTo(FhirConstants.CS_REGULATION_KIND);
    assertThat(sr.getCategoryFirstRep().getCodingFirstRep().getCode()).isEqualTo("consultation");
    assertThat(sr.getCode().getCodingFirstRep().getSystem())
        .isEqualTo("http://www.saude.gov.br/fhir/r4/CodeSystem/BRTabelaSUS");
    assertThat(sr.getSubject().getReference()).isEqualTo("Patient/01HZX4Y5K6M7N8P9Q0R1S2T3U4");
    assertThat(sr.getRequester().getIdentifier().getValue()).isEqualTo("2112345");
    assertThat(sr.getRequester().getDisplay()).isEqualTo("UBS Major Prates");
    assertThat(sr.getPerformerFirstRep().getIdentifier().getValue()).isEqualTo("2222222");
    assertThat(sr.getPerformerType().getText()).isEqualTo("cardiologia");
    assertThat(sr.getOccurrenceDateTimeType().getValueAsString()).startsWith("2026-10-10T");
    assertThat(
            sr.getExtensionByUrl(FhirConstants.EXT_REGULATION_STATUS).getValue().primitiveValue())
        .isEqualTo("authorized");
    assertThat(
            sr.getExtensionByUrl(FhirConstants.EXT_REGULATION_AUTHORIZED)
                .getValue()
                .primitiveValue())
        .isEqualTo("true");
    assertThat(sr.getExtensionByUrl(FhirConstants.EXT_WAITING_DAYS).getValue().primitiveValue())
        .isEqualTo("12");
    assertThat(sr.getNoteFirstRep().getText()).isEqualTo("Autorizado conforme protocolo");
    assertThat(
            ((org.hl7.fhir.r4.model.Reference)
                    sr.getExtensionByUrl(FhirConstants.EXT_SCHEDULED_APPOINTMENT).getValue())
                .getReference())
        .isEqualTo("Appointment/01J0000000000000000000APT1");
  }

  @Test
  void statusMapping() {
    assertThat(RegulationRequestMapper.status("requested")).isEqualTo(ServiceRequestStatus.ACTIVE);
    assertThat(RegulationRequestMapper.status("pending_documents"))
        .isEqualTo(ServiceRequestStatus.ACTIVE);
    assertThat(RegulationRequestMapper.status("under_review"))
        .isEqualTo(ServiceRequestStatus.ACTIVE);
    assertThat(RegulationRequestMapper.status("returned")).isEqualTo(ServiceRequestStatus.ONHOLD);
    assertThat(RegulationRequestMapper.status("authorized")).isEqualTo(ServiceRequestStatus.ACTIVE);
    assertThat(RegulationRequestMapper.status("scheduled")).isEqualTo(ServiceRequestStatus.ACTIVE);
    assertThat(RegulationRequestMapper.status("performed"))
        .isEqualTo(ServiceRequestStatus.COMPLETED);
    assertThat(RegulationRequestMapper.status("denied")).isEqualTo(ServiceRequestStatus.REVOKED);
    assertThat(RegulationRequestMapper.status("cancelled")).isEqualTo(ServiceRequestStatus.REVOKED);
    assertThat(RegulationRequestMapper.status("expired")).isEqualTo(ServiceRequestStatus.REVOKED);
  }

  @Test
  void priorityMapping() {
    assertThat(RegulationRequestMapper.priority("elective"))
        .isEqualTo(ServiceRequestPriority.ROUTINE);
    assertThat(RegulationRequestMapper.priority("priority"))
        .isEqualTo(ServiceRequestPriority.URGENT);
    assertThat(RegulationRequestMapper.priority("urgent")).isEqualTo(ServiceRequestPriority.ASAP);
    assertThat(RegulationRequestMapper.priority("emergency"))
        .isEqualTo(ServiceRequestPriority.STAT);
    assertThat(RegulationRequestMapper.priority(null)).isEqualTo(ServiceRequestPriority.ROUTINE);
  }
}
