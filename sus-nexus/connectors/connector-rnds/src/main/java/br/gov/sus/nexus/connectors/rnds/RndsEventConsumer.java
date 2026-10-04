package br.gov.sus.nexus.connectors.rnds;

import br.gov.sus.nexus.connectors.sdk.reprocess.ReprocessCommandHandler;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Incoming;

/**
 * Consumidores Kafka (SmallRye Reactive Messaging) dos gatilhos: {@code sus.exam.result.v1} (canal
 * {@code rnds-exam-result}) e {@code sus.hospital.discharge.v1} (canal {@code
 * rnds-hospital-discharge}). O envio é síncrono e bloqueante (worker thread); o ack/commit do
 * offset só ocorre após o pipeline terminar (aceito ou DLQ).
 *
 * <p>Comandos do core em {@code sus.integration.command.v1} (canal {@code
 * rnds-integration-command}) vão ao {@link ReprocessCommandHandler} do SDK, que filtra por {@code
 * connector_id}/tenant e delega ao {@link RndsReprocessor} (reenvio de submissões {@code failed}).
 */
@ApplicationScoped
public class RndsEventConsumer {

  private final RndsDispatcher dispatcher;
  private final ReprocessCommandHandler commands;

  @Inject
  public RndsEventConsumer(RndsDispatcher dispatcher, ReprocessCommandHandler commands) {
    this.dispatcher = dispatcher;
    this.commands = commands;
  }

  @Incoming("rnds-exam-result")
  @Blocking
  public void onExamResult(String envelope) {
    dispatcher.handle(BundleAssembler.RESULTADO_EXAME, envelope);
  }

  @Incoming("rnds-hospital-discharge")
  @Blocking
  public void onDischarge(String envelope) {
    dispatcher.handle(BundleAssembler.SUMARIO_ALTA, envelope);
  }

  @Incoming("rnds-integration-command")
  @Blocking
  public void onIntegrationCommand(String envelope) {
    commands.handle(envelope);
  }
}
