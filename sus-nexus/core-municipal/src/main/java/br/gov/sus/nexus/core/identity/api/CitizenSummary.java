package br.gov.sus.nexus.core.identity.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.LocalDate;
import java.util.List;

/** Resumo do cidadão (OpenAPI {@code CitizenSummary}). Identificadores sempre mascarados. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CitizenSummary(
    String id,
    String displayName,
    LocalDate birthdate,
    Sex sex,
    String motherNameMasked,
    RegistrationState registrationState,
    List<MaskedIdentifier> identifiers,
    String healthUnitCnes,
    String teamIne,
    String microarea,
    IdentityConfidence identityConfidence) {}
