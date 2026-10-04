package br.gov.sus.nexus.fhir.mapping;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;
import java.util.List;

/**
 * Atendimento APS para projeção. O core ainda não expõe um módulo de atendimento; o formato é o
 * {@code data} do evento {@code sus.aps.encounter.v1} ({@code
 * contracts/events/aps/encounter.v1.schema.json}) acrescido dos campos do envelope necessários à
 * projeção: {@code citizen_id} ({@code subject.municipal_citizen_id}), {@code source_system}/{@code
 * source_record_id} ({@code source}), {@code occurred_at} e {@code sensitivity} ({@code
 * privacy.classification}). Também pode ser derivado de um {@code TimelineEvent} de domínio {@code
 * aps} ({@link #fromTimelineEvent}).
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record CanonicalEncounter(
    String action,
    String encounterId,
    String citizenId,
    String encounterClass,
    String status,
    String healthUnitCnes,
    String teamIne,
    String professionalCbo,
    String professionalId,
    Instant start,
    Instant end,
    List<ConditionCode> conditionCodes,
    Integer referralsCount,
    Integer examOrdersCount,
    List<String> careLines,
    String sourceSystem,
    String sourceRecordId,
    Instant occurredAt,
    String sensitivity) {

  /** Código de condição (CID10/CIAP2) — presente apenas quando a finalidade permitir. */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ConditionCode(String system, String code) {}

  /** Mínimo de um {@code TimelineEvent} (domínio aps): sem classe detalhada nem período de fim. */
  public static CanonicalEncounter fromTimelineEvent(CanonicalTimelineEvent e) {
    return new CanonicalEncounter(
        "updated",
        e.detailRef() != null && e.detailRef().startsWith("enc_") ? e.detailRef() : e.id(),
        e.citizenId(),
        "ambulatory",
        e.status() == null ? "finished" : e.status(),
        e.cnes(),
        null,
        null,
        e.professionalRef(),
        e.occurredAt(),
        null,
        List.of(),
        null,
        null,
        List.of(),
        e.sourceSystem(),
        e.id(),
        e.recordedAt(),
        e.sensitivity());
  }
}
