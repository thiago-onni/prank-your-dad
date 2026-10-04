package br.gov.sus.nexus.core.integration.infrastructure;

import br.gov.sus.nexus.core.integration.api.IntegrationService;
import br.gov.sus.nexus.core.platform.events.InboundEventProcessor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import org.eclipse.microprofile.reactive.messaging.Incoming;

/**
 * Consome {@code sus.integration.status.v1} (canal {@code integration-status-in}) e atualiza o
 * registry de conectores; idempotente via {@code event_inbox}.
 */
@ApplicationScoped
public class IntegrationStatusConsumer {

  public static final String CONSUMER_GROUP = "core-integration-status";

  @Inject InboundEventProcessor processor;
  @Inject IntegrationService service;
  @Inject ObjectMapper objectMapper;

  @Incoming("integration-status-in")
  @Blocking
  public void onStatus(String payload) {
    processor.process(
        payload,
        CONSUMER_GROUP,
        in -> {
          Map<String, Object> data =
              objectMapper.convertValue(in.data(), new TypeReference<Map<String, Object>>() {});
          service.applyStatusEvent(in.action(), data, in.envelope().occurredAt());
          return Boolean.TRUE;
        });
  }
}
