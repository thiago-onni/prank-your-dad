package br.gov.sus.nexus.core.consent.api;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.time.OffsetDateTime;

/** Registro de consentimento (concedido/revogado) por finalidade. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ConsentRecord(
    @NotBlank String citizenId,
    @NotBlank String purpose,
    @NotBlank @Pattern(regexp = "granted|revoked") String status,
    String channel,
    OffsetDateTime recordedAt,
    @NotBlank String source) {

  public static final String PURPOSE_COMMUNICATION = "communication";
}
