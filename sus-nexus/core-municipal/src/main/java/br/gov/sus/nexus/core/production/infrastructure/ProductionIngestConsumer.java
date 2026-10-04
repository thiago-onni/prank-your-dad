package br.gov.sus.nexus.core.production.infrastructure;

import br.gov.sus.nexus.core.platform.events.InboundEventProcessor;
import br.gov.sus.nexus.core.production.api.ProductionOutcomeRegistration;
import br.gov.sus.nexus.core.production.api.ProductionRecordRegistration;
import br.gov.sus.nexus.core.production.api.ProductionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Incoming;

/**
 * Consome {@code sus.ingest.production.v1} (canal {@code ingest-production-in}): {@code data} é um
 * {@code ProductionRecordRegistration} (tem {@code kind}) — mesma porta do {@code POST
 * /production/records} — ou um {@code ProductionOutcomeRegistration} (tem {@code outcome}) com o
 * retorno do processamento oficial. Idempotente via {@code event_inbox} (e por vínculo de origem).
 */
@ApplicationScoped
public class ProductionIngestConsumer {

  public static final String CONSUMER_GROUP = "core-ingest-production";

  @Inject InboundEventProcessor processor;
  @Inject ProductionService service;
  @Inject ObjectMapper objectMapper;

  @Incoming("ingest-production-in")
  @Blocking
  public void onIngest(String payload) {
    processor.process(
        payload,
        CONSUMER_GROUP,
        in -> {
          JsonNode data = in.data();
          if (data.hasNonNull("outcome")) {
            return service.registerOutcome(
                objectMapper.convertValue(data, ProductionOutcomeRegistration.class));
          }
          return service.register(
              objectMapper.convertValue(data, ProductionRecordRegistration.class));
        });
  }
}
