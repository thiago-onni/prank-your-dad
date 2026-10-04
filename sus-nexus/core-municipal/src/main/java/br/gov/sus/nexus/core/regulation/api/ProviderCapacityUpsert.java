package br.gov.sus.nexus.core.regulation.api;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Lote de capacidade ({@code POST /regulation/capacity}). */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ProviderCapacityUpsert(
    @NotNull @Size(min = 1, max = 1000) List<@Valid ProviderCapacityDto> items) {}
