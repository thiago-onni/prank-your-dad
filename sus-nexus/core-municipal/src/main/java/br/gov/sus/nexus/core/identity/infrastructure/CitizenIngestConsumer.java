package br.gov.sus.nexus.core.identity.infrastructure;

import br.gov.sus.nexus.core.identity.api.CitizenRegistration;
import br.gov.sus.nexus.core.identity.api.CitizenService;
import br.gov.sus.nexus.core.platform.events.InboundEventProcessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Incoming;

/**
 * Consome {@code sus.ingest.pec.v1} (canal {@code ingest-pec-in}): envelope com {@code data} =
 * {@code CitizenRegistration}; chama a MESMA porta do {@code POST /citizens} (resolução de
 * identidade). Idempotente via {@code event_inbox}; tenant do envelope.
 */
@ApplicationScoped
public class CitizenIngestConsumer {

  public static final String CONSUMER_GROUP = "core-ingest-pec";

  @Inject InboundEventProcessor processor;
  @Inject CitizenService service;
  @Inject ObjectMapper objectMapper;

  @Incoming("ingest-pec-in")
  @Blocking
  public void onIngest(String payload) {
    processor.process(
        payload,
        CONSUMER_GROUP,
        in -> {
          CitizenRegistration reg = objectMapper.convertValue(in.data(), CitizenRegistration.class);
          return service.register(reg);
        });
  }
}
