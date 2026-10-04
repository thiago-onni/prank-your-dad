package br.gov.sus.nexus.core.regulation.infrastructure;

import br.gov.sus.nexus.core.platform.events.InboundEventProcessor;
import br.gov.sus.nexus.core.regulation.api.RegulationStatus;
import br.gov.sus.nexus.core.regulation.infrastructure.temporal.RegulationWorkflowStarter;
import com.fasterxml.jackson.databind.JsonNode;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.OffsetDateTime;
import org.eclipse.microprofile.reactive.messaging.Incoming;

/**
 * Starter do {@code RegulationSlaWorkflow}: {@code sus.regulation.request.created} inicia ({@code
 * workflowId = regulation-sla:<id>}, idempotente em replay) e {@code sus.regulation.status.changed}
 * sinaliza. Fica no consumidor, não no serviço (plano §8.1).
 */
@ApplicationScoped
public class RegulationEventsConsumer {

  public static final String CONSUMER_GROUP = "core-regulation-sla";

  @Inject InboundEventProcessor processor;
  @Inject RegulationWorkflowStarter starter;

  @Incoming("regulation-request-in")
  @Blocking
  public void onRequest(String payload) {
    processor.process(
        payload,
        CONSUMER_GROUP,
        in -> {
          JsonNode d = in.data();
          String id = d.path("regulation_request_id").asText(null);
          if (id == null) {
            return Boolean.FALSE;
          }
          if ("created".equals(in.action())) {
            String due = d.path("sla_due_at").asText(null);
            String status = d.path("status").asText("requested");
            if (due != null && !RegulationStatus.fromWire(status).isDecided()) {
              starter.startRegulationSla(
                  in.tenantId(),
                  id,
                  OffsetDateTime.parse(d.path("requested_at").asText()).toInstant(),
                  OffsetDateTime.parse(due).toInstant());
            }
          } else if ("cancelled".equals(in.action())) {
            starter.signalRegulationStatus(id, "cancelled");
          }
          return Boolean.TRUE;
        });
  }

  @Incoming("regulation-status-in")
  @Blocking
  public void onStatus(String payload) {
    processor.process(
        payload,
        CONSUMER_GROUP,
        in -> {
          JsonNode d = in.data();
          String id = d.path("regulation_request_id").asText(null);
          if (id == null) {
            return Boolean.FALSE;
          }
          if (!d.path("sla_breached").asBoolean(false)) {
            starter.signalRegulationStatus(id, d.path("status").asText("requested"));
          }
          return Boolean.TRUE;
        });
  }
}
