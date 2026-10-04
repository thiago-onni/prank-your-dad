package br.gov.sus.nexus.connectors.sdk.core.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.math.BigDecimal;
import java.util.List;

/**
 * Payload de {@code POST /api/v1/exams/orders/{id}/results} e de {@code
 * .../by-source/{system}/{sourceRecordId}/results} (OpenAPI {@code ExamResultRegistration}). Sem
 * texto livre: só observações numéricas e referência segura ao documento.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ExamResultRegistration(
    SourceRef source,
    String reportedAt,
    String status,
    Boolean critical,
    String performerCnes,
    String documentRef,
    String documentContentType,
    String documentSha256,
    List<Observation> observations) {

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record Observation(
      String code,
      String codeSystem,
      BigDecimal value,
      String unit,
      String valueTextMasked,
      Boolean abnormal) {}
}
