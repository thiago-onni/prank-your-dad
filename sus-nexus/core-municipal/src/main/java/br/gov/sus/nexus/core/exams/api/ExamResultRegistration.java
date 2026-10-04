package br.gov.sus.nexus.core.exams.api;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Resultado/laudo (OpenAPI {@code ExamResultRegistration}, EXA-006/007): metadados e referência
 * segura; o conteúdo NUNCA entra no barramento. Observações estruturadas mínimas, sem texto livre.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ExamResultRegistration(
    @NotNull @Valid ExamOrderRegistration.SourceRef source,
    @NotNull OffsetDateTime reportedAt,
    @NotNull @Pattern(regexp = "final|preliminary|amended|inconclusive|cancelled") String status,
    Boolean critical,
    String performerCnes,
    @Size(max = 2000) String documentRef,
    String documentContentType,
    @Pattern(regexp = "[0-9a-fA-F]{64}") String documentSha256,
    @Size(max = 500) List<@Valid Observation> observations) {

  /** Observação codificada: código, sistema, valor numérico/unidade e flag de anormalidade. */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record Observation(
      @NotBlank String code,
      @NotBlank @Pattern(regexp = "LOINC|SIGTAP|LOCAL") String codeSystem,
      Double value,
      String unit,
      String valueTextMasked,
      Boolean abnormal) {}
}
