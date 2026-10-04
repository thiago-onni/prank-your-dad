package br.gov.sus.nexus.core.careplan.api;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;

/** Registro de realização/desfecho de um item (CUI-002). */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CarePlanItemUpdate(
    @NotBlank @Pattern(regexp = "planned|scheduled|done|missed|cancelled") String status,
    OffsetDateTime performedAt,
    String evidenceRef,
    @Size(max = 500) String note) {}
