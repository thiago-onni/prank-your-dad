package br.gov.sus.nexus.core.careplan.api;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.Map;

/** Nova versão de protocolo em rascunho (OpenAPI {@code ProtocolCreate}). */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ProtocolCreate(
    @NotBlank String careLine,
    @NotBlank String name,
    String description,
    String baseVersion,
    Map<String, Object> eligibility,
    @NotEmpty @Valid List<ProtocolItemRule> items,
    @Min(1) Integer lostToFollowupDays,
    List<Map<String, Object>> testCases) {}
