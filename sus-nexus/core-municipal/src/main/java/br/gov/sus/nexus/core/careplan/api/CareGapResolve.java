package br.gov.sus.nexus.core.careplan.api;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Desfecho da busca ativa (CUI-006). */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CareGapResolve(
    @NotBlank
        @Pattern(regexp = "performed|scheduled|contact_made|refused|moved|deceased|not_found|cancelled")
        String resolution,
    @Size(max = 500) String note) {}
