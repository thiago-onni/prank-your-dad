package br.gov.sus.nexus.core.hospital.api;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;

/** Contrarreferência recebida (HOS-008): metadados + referência segura ao documento. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CounterReferralRegistration(
    @NotNull @Valid SourceRef source,
    @NotNull OffsetDateTime receivedAt,
    String targetHealthUnitCnes,
    String documentRef,
    String documentSha256,
    @Min(0) Integer recommendationsCount) {}
