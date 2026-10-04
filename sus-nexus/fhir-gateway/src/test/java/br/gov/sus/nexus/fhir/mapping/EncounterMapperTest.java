package br.gov.sus.nexus.fhir.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import br.gov.sus.nexus.fhir.FhirConstants;
import java.time.Instant;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Encounter.EncounterStatus;
import org.junit.jupiter.api.Test;

class EncounterMapperTest {

  private final EncounterMapper mapper = new EncounterMapper(MapperSettings.defaults());

  @Test
  void mapsEncounterEventData() {
    CanonicalEncounter c =
        MapperTestSupport.canonical("canonical-encounter.json", CanonicalEncounter.class);
    Encounter e = mapper.map(c);
    assertThat(e.getId()).isEqualTo("01J0000000000000000000ENC1");
    assertThat(e.getMeta().getProfile().get(0).getValue()).endsWith("BRCoreEncounter");
    assertThat(e.getStatus()).isEqualTo(EncounterStatus.FINISHED);
    assertThat(e.getClass_().getCode()).isEqualTo("AMB");
    assertThat(e.getClass_().getSystem()).isEqualTo(FhirConstants.CS_V3_ACT_CODE);
    assertThat(e.getTypeFirstRep().getCodingFirstRep().getCode()).isEqualTo("aps_individual");
    assertThat(e.getSubject().getReference()).isEqualTo("Patient/01HZX4Y5K6M7N8P9Q0R1S2T3U4");
    assertThat(e.getPeriod().getStart()).isEqualTo(Instant.parse("2026-10-01T12:00:00Z"));
    assertThat(e.getServiceProvider().getIdentifier().getValue()).isEqualTo("2112345");
    assertThat(e.getReasonCode()).hasSize(2);
    assertThat(e.getReasonCode().get(0).getCodingFirstRep().getSystem())
        .isEqualTo(FhirConstants.CS_ICD10);
    assertThat(e.getReasonCode().get(1).getCodingFirstRep().getSystem())
        .isEqualTo(FhirConstants.CS_ICPC2);
    assertThat(e.getExtensionByUrl(FhirConstants.EXT_TEAM_INE).getValue().primitiveValue())
        .isEqualTo("0000123456");
    assertThat(e.getExtensionByUrl(FhirConstants.EXT_EXAM_ORDERS_COUNT).getValue().primitiveValue())
        .isEqualTo("2");
    assertThat(e.getMeta().getSecurityFirstRep().getCode()).isEqualTo("R");
    assertThat(e.getParticipantFirstRep().getIndividual().getType()).isEqualTo("PractitionerRole");
  }

  @Test
  void classAndStatusMapping() {
    assertThat(EncounterMapper.encounterClass("emergency").getCode()).isEqualTo("EMER");
    assertThat(EncounterMapper.encounterClass("inpatient").getCode()).isEqualTo("IMP");
    assertThat(EncounterMapper.encounterClass("aps_home_visit").getCode()).isEqualTo("HH");
    assertThat(EncounterMapper.encounterClass("aps_odonto").getCode()).isEqualTo("AMB");
    assertThat(EncounterMapper.status("in_progress")).isEqualTo(EncounterStatus.INPROGRESS);
    assertThat(EncounterMapper.status("planned")).isEqualTo(EncounterStatus.PLANNED);
    assertThat(EncounterMapper.status("cancelled")).isEqualTo(EncounterStatus.CANCELLED);
  }

  @Test
  void highlyRestrictedSensitivityAndTimelineFallback() {
    CanonicalTimelineEvent te =
        new CanonicalTimelineEvent(
            "tl_01J0000000000000000000TLE1",
            "cit_01HZX4Y5K6M7N8P9Q0R1S2T3U4",
            "aps",
            "aps.encounter.finished",
            Instant.parse("2026-10-01T12:00:00Z"),
            Instant.parse("2026-10-01T12:05:00Z"),
            "ESUS_APS_PEC",
            "2112345",
            "UBS",
            null,
            "finished",
            "confirmed",
            "highly_restricted",
            null,
            "enc_01J0000000000000000000ENC5");
    Encounter e = mapper.map(CanonicalEncounter.fromTimelineEvent(te));
    assertThat(e.getId()).isEqualTo("01J0000000000000000000ENC5");
    assertThat(e.getClass_().getCode()).isEqualTo("AMB");
    assertThat(e.getMeta().getSecurity())
        .anyMatch(s -> "highly_restricted".equals(s.getCode()))
        .anyMatch(s -> "V".equals(s.getCode()));
    assertThat(e.getPeriod().hasEnd()).isFalse();
  }
}
