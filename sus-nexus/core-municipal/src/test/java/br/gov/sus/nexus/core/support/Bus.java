package br.gov.sus.nexus.core.support;

import br.gov.sus.nexus.core.platform.events.OutboxRelay;
import io.smallrye.reactive.messaging.memory.InMemoryConnector;
import io.smallrye.reactive.messaging.memory.InMemorySink;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.eclipse.microprofile.reactive.messaging.spi.Connector;

/**
 * "Kafka" dos testes: o {@link OutboxRelay} emite nos canais de saída (in-memory) e este helper
 * entrega cada mensagem aos canais de entrada que, em produção, consomem o mesmo tópico.
 */
@ApplicationScoped
public class Bus {

  /** canal de saída → canais de entrada que assinam o mesmo tópico. */
  static final Map<String, List<String>> FANOUT =
      Map.of(
          "citizen-out", List.of("journey-identity-in"),
          "merge-out", List.of("journey-merge-in", "tasks-merge-in"),
          "appointment-out", List.of("journey-appointment-in", "exams-appointment-in"),
          "task-out", List.of("journey-task-in", "tasks-task-in", "exams-task-in"),
          "integration-command-out", List.of(),
          "regulation-request-out",
              List.of("journey-regulation-request-in", "regulation-request-in"),
          "regulation-status-out", List.of("journey-regulation-status-in", "regulation-status-in"),
          "exam-order-out", List.of("journey-exam-order-in", "exams-order-in"),
          "exam-result-out", List.of("journey-exam-result-in", "exams-result-in"));

  @Inject
  @Connector("smallrye-in-memory")
  InMemoryConnector connector;

  @Inject OutboxRelay relay;

  /** Publica o outbox pendente e entrega aos consumidores. Retorna os payloads entregues. */
  public List<String> relayAndDeliver() {
    relay.relayOnce();
    List<String> delivered = new ArrayList<>();
    for (Map.Entry<String, List<String>> e : FANOUT.entrySet()) {
      InMemorySink<String> sink = connector.sink(e.getKey());
      List<? extends Message<String>> received = new ArrayList<>(sink.received());
      sink.clear();
      for (Message<String> m : received) {
        delivered.add(m.getPayload());
        for (String in : e.getValue()) {
          connector.source(in).send(m.getPayload());
        }
      }
    }
    return delivered;
  }

  public void send(String channel, String payload) {
    connector.source(channel).send(payload);
  }

  public InMemorySink<String> sink(String channel) {
    return connector.sink(channel);
  }
}
