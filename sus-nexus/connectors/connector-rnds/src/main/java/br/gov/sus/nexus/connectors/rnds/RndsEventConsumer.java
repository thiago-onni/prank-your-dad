package br.gov.sus.nexus.connectors.rnds;

import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Incoming;

/**
 * Consumidores Kafka (SmallRye Reactive Messaging) dos gatilhos: {@code sus.exam.result.v1} (canal
 * {@code rnds-exam-result}) e {@code sus.hospital.discharge.v1} (canal {@code
 * rnds-hospital-discharge}). O envio é síncrono e bloqueante (worker thread); o ack/commit do
 * offset só ocorre após o pipeline terminar (aceito ou DLQ).
 */
@ApplicationScoped
public class RndsEventConsumer {

  private final RndsDispatcher dispatcher;

  @Inject
  public RndsEventConsumer(RndsDispatcher dispatcher) {
    this.dispatcher = dispatcher;
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
}
