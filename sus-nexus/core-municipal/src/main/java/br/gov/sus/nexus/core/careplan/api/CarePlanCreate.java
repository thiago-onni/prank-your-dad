package br.gov.sus.nexus.core.careplan.api;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.OffsetDateTime;

/** Criação de plano a partir de um protocolo (OpenAPI {@code CarePlanCreate}). */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CarePlanCreate(
    @NotBlank String citizenId,
    @NotBlank String protocolId,
    String protocolVersion,
    String teamIne,
    String healthUnitCnes,
    String responsibleProfessionalId,
    OffsetDateTime startAt,
    @Valid CarePlanOrigin origin) {}
