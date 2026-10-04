package br.gov.sus.nexus.core.integration.api;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;

/**
 * Escrita no ledger espelho pelos conectores (cliente técnico). Upsert por {@code id} ({@code
 * msg_...} gerado pelo conector). Quando {@code dead_letter} vem preenchido, abre/atualiza o
 * registro de DLQ correspondente. Nunca contém o payload da mensagem (apenas {@code raw_ref}/{@code
 * raw_sha256}).
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record IntegrationMessageWrite(
    @NotBlank String id,
    @NotBlank String connectorId,
    @NotBlank String sourceSystem,
    String sourceRecordId,
    String sourceRecordVersion,
    String entityType,
    @NotNull IntegrationMessageStatus status,
    String rawRef,
    String rawSha256,
    String correlationId,
    OffsetDateTime receivedAt,
    OffsetDateTime processedAt,
    Integer attempts,
    ErrorDetail lastError,
    DeadLetterWrite deadLetter) {

  /** Dados do dead letter aberto pelo conector. */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record DeadLetterWrite(
      String topic, @NotBlank String reason, String stage, String owner, String payloadRef) {}
}
