package br.gov.sus.nexus.core.production.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** Lote de produção (OpenAPI {@code ProductionBatch}). */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ProductionBatchDto(
    String id,
    String competence,
    String cnes,
    ProductionKind kind,
    String status,
    int recordsCount,
    int totalQuantity,
    BigDecimal estimatedValue,
    List<String> recordIds,
    String createdBy,
    OffsetDateTime createdAt,
    String approvedBy,
    OffsetDateTime approvedAt,
    String approvalJustification,
    Export export,
    String protocolNumber,
    long version) {

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record Export(
      String layout,
      String fileRef,
      String sha256,
      Long sizeBytes,
      Integer lines,
      Integer linesMissingIdentifiers,
      String exportedBy,
      OffsetDateTime exportedAt) {}
}
