package br.gov.sus.nexus.core.regulation.infrastructure;

import br.gov.sus.nexus.core.platform.events.InboundEventProcessor;
import br.gov.sus.nexus.core.regulation.api.RegulationRequestRegistration;
import br.gov.sus.nexus.core.regulation.api.RegulationService;
import br.gov.sus.nexus.core.regulation.api.RegulationStatusChange;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Incoming;

/**
 * Consome {@code sus.ingest.regulation.v1} (canal {@code ingest-regulation-in}): {@code data} é um
 * {@code RegulationRequestRegistration} (tem {@code kind}) ou um {@code RegulationStatusChange}
 * (sem {@code kind}; o pedido é localizado por {@code source.system} + {@code source_record_id}).
 * Mesma porta dos endpoints REST; idempotente via {@code event_inbox}; tenant do envelope.
 */
@ApplicationScoped
public class RegulationIngestConsumer {

  public static final String CONSUMER_GROUP = "core-ingest-regulation";

  @Inject InboundEventProcessor processor;
  @Inject RegulationService service;
  @Inject ObjectMapper objectMapper;

  @Incoming("ingest-regulation-in")
  @Blocking
  public void onIngest(String payload) {
    processor.process(
        payload,
        CONSUMER_GROUP,
        in -> {
          JsonNode data = in.data();
          if (data.hasNonNull("kind")) {
            return service.register(
                objectMapper.convertValue(data, RegulationRequestRegistration.class));
          }
          RegulationStatusChange change =
              objectMapper.convertValue(data, RegulationStatusChange.class);
          return service.changeStatusBySource(
              change.source().system(), change.source().sourceRecordId(), change);
        });
  }
}
