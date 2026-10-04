package br.gov.sus.nexus.core.hospital.application;

import br.gov.sus.nexus.core.hospital.api.SourceRef;
import br.gov.sus.nexus.core.hospital.domain.HospitalBedMovement;
import br.gov.sus.nexus.core.hospital.domain.HospitalEpisode;
import br.gov.sus.nexus.core.platform.events.DomainEvent;
import br.gov.sus.nexus.core.platform.events.EventEnvelope;
import br.gov.sus.nexus.core.platform.events.EventPublisher;
import br.gov.sus.nexus.core.platform.security.Purpose;
import br.gov.sus.nexus.core.sharedkernel.Cnes;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Publica {@code sus.hospital.adt.*} ({@code hospital/adt.v1.schema.json}) e {@code
 * sus.hospital.discharge.*} ({@code hospital/discharge.v1.schema.json}). Classificação {@code
 * restricted}; {@code highly_restricted} quando o CID principal é sensível — e nesse caso o código
 * NÃO trafega no evento (só por leitura autorizada da API). O sumário de alta vai por {@code
 * data_ref}.
 */
@ApplicationScoped
public class HospitalEvents {

  public static final String ADT_PREFIX = "sus.hospital.adt.";
  public static final String DISCHARGE_PREFIX = "sus.hospital.discharge.";
  public static final String ADT_AGGREGATE = "hospital_episode";
  public static final String DISCHARGE_AGGREGATE = "hospital_discharge";
  static final String EVENT_VERSION = "1.0";
  static final List<String> PURPOSES = List.of(Purpose.CARE_COORDINATION.wire());

  @Inject EventPublisher publisher;

  public String publishAdt(
      String action, HospitalEpisode e, HospitalBedMovement m, SourceRef source, String causationId) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("action", action);
    data.put("hospital_episode_id", e.id);
    data.put("hospital_cnes", e.hospitalCnes);
    data.put("episode_class", e.episodeClass);
    data.put("status", e.status);
    data.put("occurred_at", utc(m.occurredAt));
    data.put("admitted_at", utc(e.admittedAt));
    put(data, "ward", m.ward);
    put(data, "bed", m.bed);
    put(data, "previous_ward", m.previousWard);
    put(data, "attending_professional_id", m.attendingProfessionalId);
    diagnosis(data, e);
    put(data, "admission_source", e.admissionSource);
    put(data, "regulation_request_id", e.regulationRequestId);
    put(data, "aih_number", e.aihNumber);
    put(data, "reason", truncate(m.reason));
    return publisher.publish(
        new DomainEvent(
            ADT_AGGREGATE,
            e.id,
            ADT_PREFIX + action,
            EVENT_VERSION,
            utc(m.occurredAt),
            new EventEnvelope.Subject(e.citizenId, null),
            source(source, e),
            data,
            privacy(e),
            causationId));
  }

  public String publishDischarge(
      String action, HospitalEpisode e, SourceRef source, boolean counterReferralDocument, String causationId) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("action", action);
    data.put("hospital_episode_id", e.id);
    data.put("hospital_cnes", e.hospitalCnes);
    data.put("discharged_at", utc(e.dischargedAt));
    data.put("admitted_at", utc(e.admittedAt));
    if (e.lengthOfStayDays != null) {
      data.put("length_of_stay_days", e.lengthOfStayDays);
    }
    data.put("disposition", e.disposition);
    diagnosis(data, e);
    if (e.proceduresCount != null) {
      data.put("procedures_count", e.proceduresCount);
    }
    data.put("readmission_within_30d", e.readmissionWithin30d);
    if (e.followupPlanPresent != null) {
      data.put("followup_plan_present", e.followupPlanPresent);
    }
    if (e.followupDueDays != null) {
      data.put("followup_due_days", e.followupDueDays);
    }
    cnes(data, "reference_health_unit_cnes", e.referenceHealthUnitCnes);
    put(data, "reference_team_ine", e.referenceTeamIne);
    put(data, "risk_level", e.riskLevel);
    put(data, "risk_rule_version", e.riskRuleVersion);
    data.put("care_lines", Arrays.asList(e.careLines));
    if ("counter_referral_received".equals(action)) {
      data.put("counter_referral_document_present", counterReferralDocument);
    }
    return publisher.publish(
        new DomainEvent(
            DISCHARGE_AGGREGATE,
            e.id,
            DISCHARGE_PREFIX + action,
            EVENT_VERSION,
            utc(e.dischargedAt),
            new EventEnvelope.Subject(e.citizenId, null),
            source(source, e),
            data,
            privacy(e),
            causationId,
            e.summaryDocumentRef));
  }

  /** CID só quando NÃO sensível; sensível → classificação highly_restricted e código omitido. */
  private static void diagnosis(Map<String, Object> data, HospitalEpisode e) {
    if (e.principalDiagnosisCid != null && !e.cidHighlyRestricted) {
      Map<String, Object> d = new LinkedHashMap<>();
      d.put("system", "CID10");
      d.put("code", e.principalDiagnosisCid);
      data.put("principal_diagnosis", d);
    }
  }

  private static EventEnvelope.Privacy privacy(HospitalEpisode e) {
    return new EventEnvelope.Privacy(
        e.cidHighlyRestricted ? "highly_restricted" : "restricted", PURPOSES);
  }

  private static EventEnvelope.Source source(SourceRef source, HospitalEpisode e) {
    if (source == null) {
      return new EventEnvelope.Source(
          e.sourceSystem, "core-municipal", e.sourceRecordId, null, e.hospitalCnes);
    }
    return new EventEnvelope.Source(
        source.system(),
        source.connector(),
        source.sourceRecordId(),
        source.sourceRecordVersion(),
        Cnes.isValid(source.cnes()) ? source.cnes().trim() : null);
  }

  private static OffsetDateTime utc(Instant i) {
    return i.atOffset(ZoneOffset.UTC);
  }

  private static void cnes(Map<String, Object> m, String k, String v) {
    if (Cnes.isValid(v)) {
      m.put(k, v);
    }
  }

  private static void put(Map<String, Object> m, String k, String v) {
    if (v != null && !v.isBlank()) {
      m.put(k, v);
    }
  }

  private static String truncate(String s) {
    return s == null ? null : s.length() > 500 ? s.substring(0, 500) : s;
  }
}
