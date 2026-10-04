package br.gov.sus.nexus.core.hospital.api;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;

/** Resultado da tentativa de contato pós-alta (CUI-006). */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DischargeFollowup(
    @NotBlank
        @Pattern(regexp = "contact_made|appointment_scheduled|deceased|moved|refused|not_found")
        String outcome,
    @Size(max = 500) String note,
    OffsetDateTime contactedAt) {

  /** Desfechos que indicam contato efetivo (podem abrir plano de cuidado). */
  public boolean contactEstablished() {
    return "contact_made".equals(outcome) || "appointment_scheduled".equals(outcome);
  }
}
