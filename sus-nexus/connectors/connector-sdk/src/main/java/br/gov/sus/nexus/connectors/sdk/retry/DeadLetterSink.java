package br.gov.sus.nexus.connectors.sdk.retry;

import java.util.List;

/** Destino de dead letters (arquivo, log, tópico {@code sus.dlq.v1}, ...). */
public interface DeadLetterSink {

  void accept(DeadLetter deadLetter);

  List<DeadLetter> open(int limit);
}
