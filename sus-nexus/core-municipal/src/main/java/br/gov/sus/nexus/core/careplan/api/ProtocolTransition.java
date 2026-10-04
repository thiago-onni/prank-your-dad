package br.gov.sus.nexus.core.careplan.api;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;

/** Ciclo de aprovação de versão: submit → approve → activate → revoke. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ProtocolTransition(
    @NotBlank @Pattern(regexp = "submit|approve|activate|revoke") String action,
    @Size(max = 500) String justification,
    OffsetDateTime effectiveFrom) {}
