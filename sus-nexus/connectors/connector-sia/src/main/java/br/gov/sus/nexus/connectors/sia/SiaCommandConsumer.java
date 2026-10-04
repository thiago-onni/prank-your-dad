package br.gov.sus.nexus.connectors.sia;

import br.gov.sus.nexus.connectors.sdk.reprocess.ReprocessCommandHandler;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Incoming;

/**
 * Comandos do core em {@code sus.integration.command.v1} (canal {@code sia-integration-command}):
 * {@code sus.integration.reprocess.requested} para mensagens deste conector é tratado pelo handler
 * genérico do SDK (relê a raw zone e reexecuta o pipeline reabrindo a mesma mensagem; o {@code
 * event_id} determinístico mantém o consumo no core idempotente).
 */
@ApplicationScoped
public class SiaCommandConsumer {

  private final ReprocessCommandHandler handler;

  @Inject
  public SiaCommandConsumer(ReprocessCommandHandler handler) {
    this.handler = handler;
  }

  @Incoming("sia-integration-command")
  @Blocking
  public void onCommand(String envelope) {
    handler.handle(envelope);
  }
}
