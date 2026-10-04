package br.gov.sus.nexus.core.consent.api;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Preferência de comunicação: o valor em claro é usado só para hash/máscara e nunca persistido. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record PreferenceUpsert(
    @NotBlank String citizenId,
    @NotBlank @Pattern(regexp = "sms|whatsapp|phone|email|app|letter") String channel,
    String value,
    boolean preferred,
    boolean allowed,
    @NotBlank String source) {}
