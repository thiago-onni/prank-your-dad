package br.gov.sus.nexus.fhir.mapping;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;
import java.util.List;

/**
 * Canônico {@code ExamResult} ({@code ExamOrder.results[]} no OpenAPI) acrescido, quando o core os
 * expõe/autoriza, dos campos de {@code ExamResultRegistration}: {@code observations[]} (resultados
 * estruturados mínimos, sem texto livre), {@code document_ref} (referência opaca ao object storage
 * da origem; nunca o conteúdo), {@code document_content_type} e {@code document_sha256}. {@code
 * binary_id} é preenchido pelo gateway quando o conteúdo foi copiado para um {@code Binary}
 * próprio.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record CanonicalExamResult(
    String id,
    Instant reportedAt,
    String status,
    Boolean critical,
    String performerCnes,
    Boolean hasDocument,
    Integer observationsCount,
    String followupTaskId,
    String sourceSystem,
    String sourceRecordId,
    String documentRef,
    String documentContentType,
    String documentSha256,
    String binaryId,
    List<Observation> observations) {

  /** Item de {@code ExamResultRegistration.observations[]}. */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Observation(
      String code,
      String codeSystem,
      java.math.BigDecimal value,
      String unit,
      String valueTextMasked,
      Boolean abnormal) {}
}
