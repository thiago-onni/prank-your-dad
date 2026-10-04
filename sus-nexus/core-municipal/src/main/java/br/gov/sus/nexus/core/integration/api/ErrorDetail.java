package br.gov.sus.nexus.core.integration.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.OffsetDateTime;

/** Último erro de uma mensagem (OpenAPI {@code IntegrationMessage.last_error}). Sem payload. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ErrorDetail(String code, String message, String stage, OffsetDateTime occurredAt) {}
