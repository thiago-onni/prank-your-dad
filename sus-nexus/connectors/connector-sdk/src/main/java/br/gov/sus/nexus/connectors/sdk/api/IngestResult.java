package br.gov.sus.nexus.connectors.sdk.api;

import java.util.List;

/** Resultado de uma ingestão: mensagens entregues ao pipeline. */
public record IngestResult(int messagesReceived, List<String> messageIds, String detail) {

  public IngestResult {
    messageIds = List.copyOf(messageIds);
  }

  public static IngestResult empty(String detail) {
    return new IngestResult(0, List.of(), detail);
  }
}
