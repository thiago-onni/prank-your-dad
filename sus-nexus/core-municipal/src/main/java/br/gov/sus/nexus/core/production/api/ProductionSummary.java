package br.gov.sus.nexus.core.production.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** Painel de produção da competência (PRO-009; OpenAPI {@code ProductionSummary}). */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ProductionSummary(
    String competence,
    String cnes,
    OffsetDateTime deadlineAt,
    Integer daysToDeadline,
    Totals totals,
    Values values,
    List<RuleCount> issuesByRule,
    List<KindCount> byKind) {

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record Totals(
      long records,
      long generated,
      long validated,
      long pending,
      long exported,
      long transmitted,
      long received,
      long rejected,
      long corrected,
      long approved,
      long paid) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record Values(
      BigDecimal estimated,
      BigDecimal validated,
      BigDecimal paid,
      BigDecimal pending,
      BigDecimal rejected,
      BigDecimal avoidableLossEstimated) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record RuleCount(String ruleId, String severity, long open) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record KindCount(ProductionKind kind, long records, BigDecimal estimated) {}
}
