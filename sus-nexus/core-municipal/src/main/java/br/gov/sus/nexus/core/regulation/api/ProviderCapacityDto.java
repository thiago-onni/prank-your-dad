package br.gov.sus.nexus.core.regulation.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.OffsetDateTime;

/**
 * Oferta/capacidade por prestador, serviço e competência (OpenAPI {@code ProviderCapacity}). Sem
 * Bean Validation por item: no lote, itens inválidos são contados como {@code rejected}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ProviderCapacityDto(
    String providerCnes,
    String providerName,
    String serviceCode,
    String codeSystem,
    String competence,
    Integer offered,
    Integer used,
    Integer available,
    String sourceSystem,
    OffsetDateTime updatedAt) {}
