package br.gov.sus.nexus.fhir.mapping;

import static br.gov.sus.nexus.fhir.mapping.MappingSupport.date;
import static br.gov.sus.nexus.fhir.mapping.MappingSupport.extensionString;
import static br.gov.sus.nexus.fhir.mapping.MappingSupport.isBlank;
import static br.gov.sus.nexus.fhir.mapping.MappingSupport.lower;

import br.gov.sus.nexus.fhir.FhirConstants;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Encounter.EncounterStatus;
import org.hl7.fhir.r4.model.IntegerType;
import org.hl7.fhir.r4.model.Period;

/**
 * Atendimento APS ({@link CanonicalEncounter}, formato do evento {@code sus.aps.encounter.v1}) →
 * FHIR {@code Encounter}. Classe (v3-ActCode): aps_home_visit→HH, demais aps_x e ambulatory→AMB,
 * emergency→EMER, inpatient→IMP (classe canônica em {@code Encounter.type}, CodeSystem {@code
 * encounter-class}). Status: planned→planned, in_progress→in-progress, finished→finished,
 * cancelled→cancelled. {@code subject} Patient, {@code period}, {@code serviceProvider} pelo CNES,
 * profissional como participante lógico, condições (CID-10/CIAP-2) em {@code reasonCode} (redigido
 * em escopo restrito), classificação {@code highly_restricted} em {@code meta.security}.
 */
@ApplicationScoped
public class EncounterMapper {

  @Inject MapperSettings settings;

  public EncounterMapper() {}

  public EncounterMapper(MapperSettings settings) {
    this.settings = settings;
  }

  public Encounter map(CanonicalEncounter e) {
    Encounter enc = new Encounter();
    enc.setId(CanonicalIds.toFhirId(e.encounterId()));
    enc.getMeta().addProfile(settings.encounterProfile());
    MappingSupport.applySensitivity(enc, e.sensitivity());
    enc.addIdentifier(
        MappingSupport.identifier(FhirConstants.SYSTEM_MUNICIPAL_ENCOUNTER_ID, e.encounterId()));
    MappingSupport.sourceIdentifier(e.sourceSystem(), e.sourceRecordId())
        .ifPresent(enc::addIdentifier);

    enc.setStatus(status(e.status()));
    enc.setClass_(encounterClass(e.encounterClass()));
    enc.addType(
        new CodeableConcept()
            .addCoding(
                new Coding()
                    .setSystem(FhirConstants.CS_ENCOUNTER_CLASS)
                    .setCode(e.encounterClass()))
            .setText(e.encounterClass()));
    enc.setSubject(MappingSupport.patientRef(e.citizenId()));
    if (e.start() != null || e.end() != null) {
      Period p = new Period();
      if (e.start() != null) {
        p.setStart(date(e.start()));
      }
      if (e.end() != null) {
        p.setEnd(date(e.end()));
      }
      enc.setPeriod(p);
    }
    if (!isBlank(e.healthUnitCnes())) {
      enc.setServiceProvider(MappingSupport.organizationByCnes(e.healthUnitCnes()));
    }
    if (!isBlank(e.professionalId())) {
      var participant = enc.addParticipant();
      participant.addType(
          new CodeableConcept()
              .addCoding(
                  new Coding().setSystem(FhirConstants.CS_PARTICIPATION_TYPE).setCode("PPRF")));
      participant.setIndividual(
          MappingSupport.logical(
              "PractitionerRole",
              FhirConstants.SYSTEM_MUNICIPAL_PROFESSIONAL_ID,
              e.professionalId()));
    }
    if (e.conditionCodes() != null) {
      for (CanonicalEncounter.ConditionCode cc : e.conditionCodes()) {
        String system =
            "CIAP2".equalsIgnoreCase(cc.system()) ? FhirConstants.CS_ICPC2 : FhirConstants.CS_ICD10;
        enc.addReasonCode(
            new CodeableConcept().addCoding(new Coding().setSystem(system).setCode(cc.code())));
      }
    }
    extensionString(enc, FhirConstants.EXT_TEAM_INE, e.teamIne());
    extensionString(enc, FhirConstants.EXT_PROFESSIONAL_CBO, e.professionalCbo());
    if (e.referralsCount() != null) {
      enc.addExtension(FhirConstants.EXT_REFERRALS_COUNT, new IntegerType(e.referralsCount()));
    }
    if (e.examOrdersCount() != null) {
      enc.addExtension(FhirConstants.EXT_EXAM_ORDERS_COUNT, new IntegerType(e.examOrdersCount()));
    }
    if (e.careLines() != null) {
      for (String line : e.careLines()) {
        extensionString(enc, FhirConstants.EXT_CARE_LINE, line);
      }
    }
    return enc;
  }

  public static Coding encounterClass(String canonical) {
    String code =
        switch (lower(canonical)) {
          case "emergency" -> "EMER";
          case "inpatient" -> "IMP";
          case "aps_home_visit" -> "HH";
          case "ambulatory", "aps_individual", "aps_odonto", "aps_procedure", "aps_collective" ->
              "AMB";
          default -> throw new IllegalArgumentException("encounter_class canônica desconhecida");
        };
    String display =
        switch (code) {
          case "EMER" -> "emergency";
          case "IMP" -> "inpatient encounter";
          case "HH" -> "home health";
          default -> "ambulatory";
        };
    return new Coding().setSystem(FhirConstants.CS_V3_ACT_CODE).setCode(code).setDisplay(display);
  }

  public static EncounterStatus status(String canonical) {
    return switch (lower(canonical)) {
      case "planned" -> EncounterStatus.PLANNED;
      case "in_progress" -> EncounterStatus.INPROGRESS;
      case "finished" -> EncounterStatus.FINISHED;
      case "cancelled" -> EncounterStatus.CANCELLED;
      default -> throw new IllegalArgumentException("status de atendimento desconhecido");
    };
  }
}
