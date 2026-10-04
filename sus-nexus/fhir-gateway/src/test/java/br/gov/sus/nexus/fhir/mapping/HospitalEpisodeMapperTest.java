package br.gov.sus.nexus.fhir.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import br.gov.sus.nexus.fhir.FhirConstants;
import java.time.Instant;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Encounter.EncounterStatus;
import org.junit.jupiter.api.Test;

class HospitalEpisodeMapperTest {

  private final HospitalEpisodeMapper mapper = new HospitalEpisodeMapper(MapperSettings.defaults());

  @Test
  void mapsDischargedInpatientEpisode() {
    CanonicalHospitalEpisode c =
        MapperTestSupport.canonical(
            "canonical-hospital-episode.json", CanonicalHospitalEpisode.class);
    Encounter e = mapper.map(c);
    assertThat(e.getId()).isEqualTo("01J0000000000000000000HEP1");
    assertThat(e.getMeta().getProfile().get(0).getValue()).endsWith("BRCoreEncounter");
    assertThat(e.getStatus()).isEqualTo(EncounterStatus.FINISHED);
    assertThat(e.getClass_().getCode()).isEqualTo("IMP");
    assertThat(e.getClass_().getSystem()).isEqualTo(FhirConstants.CS_V3_ACT_CODE);
    assertThat(e.getTypeFirstRep().getCodingFirstRep().getCode()).isEqualTo("inpatient");
    assertThat(e.getSubject().getReference()).isEqualTo("Patient/01HZX4Y5K6M7N8P9Q0R1S2T3U4");
    assertThat(e.getPeriod().getStart()).isEqualTo(Instant.parse("2026-09-20T13:00:00Z"));
    assertThat(e.getPeriod().getEnd()).isEqualTo(Instant.parse("2026-09-25T18:00:00Z"));
    assertThat(e.getHospitalization().getDischargeDisposition().getCodingFirstRep().getCode())
        .isEqualTo("alt-home");
    assertThat(e.getHospitalization().getAdmitSource().getCodingFirstRep().getCode())
        .isEqualTo("emd");
    assertThat(e.getReasonCodeFirstRep().getCodingFirstRep().getCode()).isEqualTo("I50.0");
    assertThat(e.getReasonCodeFirstRep().getCodingFirstRep().getSystem())
        .isEqualTo(FhirConstants.CS_ICD10);
    assertThat(e.getServiceProvider().getIdentifier().getValue()).isEqualTo("2112345");
    assertThat(e.getServiceProvider().getType()).isEqualTo("Organization");
    assertThat(e.getLocationFirstRep().getLocation().getType()).isEqualTo("Location");
    assertThat(e.getLocationFirstRep().getStatus())
        .isEqualTo(Encounter.EncounterLocationStatus.COMPLETED);
    assertThat(e.getBasedOnFirstRep().getReference())
        .isEqualTo("ServiceRequest/01J0000000000000000000REG1");
    assertThat(e.getIdentifier())
        .anyMatch(i -> FhirConstants.SYSTEM_AIH.equals(i.getSystem()))
        .anyMatch(i -> FhirConstants.SYSTEM_MUNICIPAL_HOSPITAL_EPISODE_ID.equals(i.getSystem()));
    assertThat(e.getExtensionByUrl(FhirConstants.EXT_LENGTH_OF_STAY).getValue().primitiveValue())
        .isEqualTo("5");
    assertThat(e.getExtensionByUrl(FhirConstants.EXT_RISK_LEVEL).getValue().primitiveValue())
        .isEqualTo("high");
    assertThat(e.hasPartOf()).isFalse();
    assertThat(e.getExtensionByUrl(FhirConstants.EXT_PREVIOUS_EPISODE)).isNotNull();
  }

  @Test
  void reasonCodeOnlyWhenPresentAndClassMapping() {
    CanonicalHospitalEpisode c =
        MapperTestSupport.canonical(
            "canonical-hospital-episode.json", CanonicalHospitalEpisode.class);
    CanonicalHospitalEpisode noDx =
        new CanonicalHospitalEpisode(
            c.id(),
            c.citizenId(),
            c.hospitalCnes(),
            null,
            "emergency",
            "admitted",
            c.admittedAt(),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);
    Encounter e = mapper.map(noDx);
    assertThat(e.hasReasonCode()).isFalse();
    assertThat(e.getClass_().getCode()).isEqualTo("EMER");
    assertThat(e.getStatus()).isEqualTo(EncounterStatus.INPROGRESS);
    assertThat(e.hasHospitalization()).isFalse();
    assertThat(e.getPeriod().hasEnd()).isFalse();
    assertThat(HospitalEpisodeMapper.encounterClass("observation").getCode()).isEqualTo("OBSENC");
    assertThat(HospitalEpisodeMapper.status("cancelled")).isEqualTo(EncounterStatus.CANCELLED);
    assertThat(HospitalEpisodeMapper.status("deceased")).isEqualTo(EncounterStatus.FINISHED);
    assertThat(HospitalEpisodeMapper.dischargeDisposition("deceased")).isEqualTo("exp");
    assertThat(HospitalEpisodeMapper.dischargeDisposition("transfer")).isEqualTo("other-hcf");
  }
}
