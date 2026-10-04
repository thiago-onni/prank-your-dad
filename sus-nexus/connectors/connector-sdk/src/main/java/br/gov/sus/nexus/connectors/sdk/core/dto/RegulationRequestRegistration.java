package br.gov.sus.nexus.connectors.sdk.core.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * Payload de {@code POST /api/v1/regulation/requests} (OpenAPI {@code
 * RegulationRequestRegistration}). Upsert por {@code source.source_record_id}: reenviar com outro
 * {@code status} atualiza o pedido.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record RegulationRequestRegistration(
    SourceRef source,
    CitizenRef citizenRef,
    String kind,
    String status,
    String priority,
    String requestedAt,
    String requestedServiceCode,
    String codeSystem,
    String specialty,
    String requestingCnes,
    String requestingProfessionalId,
    String requestingProfessionalCbo,
    Boolean justificationPresent,
    Integer attachedDocumentsCount,
    String cidCode,
    String providerCnes,
    String scheduledAt,
    String regulatorId,
    String decisionReason,
    String occurredAt) {}
