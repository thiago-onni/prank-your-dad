package br.gov.sus.nexus.fhir.mapping;

import static br.gov.sus.nexus.fhir.mapping.MappingSupport.date;
import static br.gov.sus.nexus.fhir.mapping.MappingSupport.extensionCode;
import static br.gov.sus.nexus.fhir.mapping.MappingSupport.extensionString;
import static br.gov.sus.nexus.fhir.mapping.MappingSupport.isBlank;
import static br.gov.sus.nexus.fhir.mapping.MappingSupport.lower;

import br.gov.sus.nexus.fhir.FhirConstants;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.hl7.fhir.r4.model.BooleanType;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Encounter.EncounterStatus;
import org.hl7.fhir.r4.model.IntegerType;
import org.hl7.fhir.r4.model.Period;
import org.hl7.fhir.r4.model.Reference;

/**
 * Canônico {@code HospitalEpisode} → FHIR {@code Encounter}.
 *
 * <ul>
 *   <li>{@code class} (v3-ActCode): inpatient→IMP, emergency→EMER, observation→OBSENC,
 *       day_hospital→SS; classe canônica em {@code type} ({@code encounter-class}).
 *   <li>Status: admitted/in_progress/transferred→in-progress, discharged/deceased→finished,
 *       cancelled→cancelled (canônico íntegro em {@code hospital-episode-status}).
 *   <li>{@code period} = admitted_at/discharged_at; {@code hospitalization.dischargeDisposition}
 *       (home→home, home_with_care→alt-home, transfer→other-hcf, against_advice→aadvice,
 *       deceased→exp, other→oth) e {@code admitSource} (emergency→emd, transfer→hosp-trans,
 *       elective→outp, regulation→mp, other→other); {@code reasonCode} CID-10 <b>somente</b> se
 *       presente no canônico; {@code location} Location por CNES (lógica) com ward/bed em extensão;
 *       {@code serviceProvider} Organization por CNES; {@code basedOn} regulação; AIH como
 *       identifier; episódio anterior em extensão {@code previous-episode}; extensões risk-level,
 *       readmission-within-30d, length-of-stay-days.
 * </ul>
 */
@ApplicationScoped
public class HospitalEpisodeMapper {

  @Inject MapperSettings settings;

  public HospitalEpisodeMapper() {}

  public HospitalEpisodeMapper(MapperSettings settings) {
    this.settings = settings;
  }

  public Encounter map(CanonicalHospitalEpisode e) {
    Encounter enc = new Encounter();
    enc.setId(CanonicalIds.toFhirId(e.id()));
    enc.getMeta().addProfile(settings.encounterProfile());
    MappingSupport.applySensitivity(enc, e.sensitivity());
    enc.addIdentifier(
        MappingSupport.identifier(FhirConstants.SYSTEM_MUNICIPAL_HOSPITAL_EPISODE_ID, e.id()));
    MappingSupport.sourceIdentifier(e.sourceSystem(), e.sourceRecordId())
        .ifPresent(enc::addIdentifier);
    if (!isBlank(e.aihNumber())) {
      enc.addIdentifier(MappingSupport.identifier(FhirConstants.SYSTEM_AIH, e.aihNumber()));
    }
    enc.setStatus(status(e.status()));
    extensionCode(enc, FhirConstants.EXT_HOSPITAL_EPISODE_STATUS, e.status());
    enc.setClass_(encounterClass(e.episodeClass()));
    enc.addType(
        new CodeableConcept()
            .addCoding(
                new Coding().setSystem(FhirConstants.CS_ENCOUNTER_CLASS).setCode(e.episodeClass()))
            .setText(e.episodeClass()));
    enc.setSubject(MappingSupport.patientRef(e.citizenId()));
    if (e.admittedAt() != null || e.dischargedAt() != null) {
      Period p = new Period();
      if (e.admittedAt() != null) {
        p.setStart(date(e.admittedAt()));
      }
      if (e.dischargedAt() != null) {
        p.setEnd(date(e.dischargedAt()));
      }
      enc.setPeriod(p);
    }
    if (!isBlank(e.hospitalCnes())) {
      Reference provider = MappingSupport.organizationByCnes(e.hospitalCnes());
      if (!isBlank(e.hospitalName())) {
        provider.setDisplay(e.hospitalName());
      }
      enc.setServiceProvider(provider);
      Encounter.EncounterLocationComponent loc = enc.addLocation();
      loc.setLocation(MappingSupport.locationByCnes(e.hospitalCnes()));
      loc.setStatus(
          enc.getStatus() == EncounterStatus.INPROGRESS
              ? Encounter.EncounterLocationStatus.ACTIVE
              : Encounter.EncounterLocationStatus.COMPLETED);
      if (!isBlank(e.ward())) {
        loc.addExtension(
            FhirConstants.EXT_HOSPITAL_WARD, new org.hl7.fhir.r4.model.StringType(e.ward()));
      }
      if (!isBlank(e.bed())) {
        loc.addExtension(
            FhirConstants.EXT_HOSPITAL_BED, new org.hl7.fhir.r4.model.StringType(e.bed()));
      }
    }
    if (!isBlank(e.principalDiagnosisCid())) {
      enc.addReasonCode(
          new CodeableConcept()
              .addCoding(
                  new Coding()
                      .setSystem(FhirConstants.CS_ICD10)
                      .setCode(e.principalDiagnosisCid())));
    }
    if (!isBlank(e.disposition()) || !isBlank(e.admissionSource())) {
      Encounter.EncounterHospitalizationComponent h = enc.getHospitalization();
      if (!isBlank(e.disposition())) {
        h.setDischargeDisposition(
            new CodeableConcept()
                .addCoding(
                    new Coding()
                        .setSystem(FhirConstants.CS_DISCHARGE_DISPOSITION)
                        .setCode(dischargeDisposition(e.disposition())))
                .setText(e.disposition()));
      }
      if (!isBlank(e.admissionSource())) {
        h.setAdmitSource(
            new CodeableConcept()
                .addCoding(
                    new Coding()
                        .setSystem(FhirConstants.CS_ADMIT_SOURCE)
                        .setCode(admitSource(e.admissionSource())))
                .setText(e.admissionSource()));
        extensionCode(enc, FhirConstants.EXT_ADMISSION_SOURCE, e.admissionSource());
      }
      if (Boolean.TRUE.equals(e.readmissionWithin30d())) {
        h.setReAdmission(
            new CodeableConcept()
                .addCoding(
                    new Coding()
                        .setSystem("http://terminology.hl7.org/CodeSystem/v2-0092")
                        .setCode("R")));
      }
    }
    MappingSupport.referenceFromPrefixedId(e.regulationRequestId()).ifPresent(enc::addBasedOn);
    // episódio anterior (readmissão): extensão, pois Encounter.partOf indica hierarquia, não
    // sequência
    MappingSupport.referenceFromPrefixedId(e.previousEpisodeId())
        .ifPresent(ref -> enc.addExtension(FhirConstants.EXT_PREVIOUS_EPISODE, ref));
    if (e.lengthOfStayDays() != null) {
      enc.addExtension(FhirConstants.EXT_LENGTH_OF_STAY, new IntegerType(e.lengthOfStayDays()));
    }
    if (e.readmissionWithin30d() != null) {
      enc.addExtension(
          FhirConstants.EXT_READMISSION_30D, new BooleanType(e.readmissionWithin30d()));
    }
    extensionCode(enc, FhirConstants.EXT_RISK_LEVEL, e.riskLevel());
    extensionString(enc, FhirConstants.EXT_TEAM_INE, e.referenceTeamIne());
    return enc;
  }

  public static Coding encounterClass(String canonical) {
    String code =
        switch (lower(canonical)) {
          case "inpatient" -> "IMP";
          case "emergency" -> "EMER";
          case "observation" -> "OBSENC";
          case "day_hospital" -> "SS";
          default -> throw new IllegalArgumentException("episode_class canônica desconhecida");
        };
    String display =
        switch (code) {
          case "IMP" -> "inpatient encounter";
          case "EMER" -> "emergency";
          case "OBSENC" -> "observation encounter";
          default -> "short stay";
        };
    return new Coding().setSystem(FhirConstants.CS_V3_ACT_CODE).setCode(code).setDisplay(display);
  }

  public static EncounterStatus status(String canonical) {
    return switch (lower(canonical)) {
      case "admitted", "in_progress", "transferred" -> EncounterStatus.INPROGRESS;
      case "discharged", "deceased" -> EncounterStatus.FINISHED;
      case "cancelled" -> EncounterStatus.CANCELLED;
      default -> throw new IllegalArgumentException("HospitalEpisodeStatus canônico desconhecido");
    };
  }

  public static String dischargeDisposition(String canonical) {
    return switch (lower(canonical)) {
      case "home" -> "home";
      case "home_with_care" -> "alt-home";
      case "transfer" -> "other-hcf";
      case "against_advice" -> "aadvice";
      case "deceased" -> "exp";
      default -> "oth";
    };
  }

  public static String admitSource(String canonical) {
    return switch (lower(canonical)) {
      case "emergency" -> "emd";
      case "transfer" -> "hosp-trans";
      case "elective" -> "outp";
      case "regulation" -> "mp";
      default -> "other";
    };
  }
}
