package br.gov.sus.nexus.connectors.sdk.api;

/** Mensagem que falhou em alguma etapa do pipeline. */
public record FailedMessage(
    String messageId, String stage, int attempts, Throwable error, boolean permanent) {}
