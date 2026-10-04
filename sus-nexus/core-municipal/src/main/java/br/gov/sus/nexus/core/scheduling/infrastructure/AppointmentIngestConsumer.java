package br.gov.sus.nexus.core.scheduling.infrastructure;

import br.gov.sus.nexus.core.platform.events.InboundEventProcessor;
import br.gov.sus.nexus.core.scheduling.api.AppointmentRegistration;
import br.gov.sus.nexus.core.scheduling.api.AppointmentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Incoming;

/**
 * Consome {@code sus.ingest.agenda.v1} (canal {@code ingest-agenda-in}): envelope com {@code data}
 * = {@code AppointmentRegistration}; chama a MESMA porta do {@code POST /appointments}. Idempotente
 * via {@code event_inbox}; tenant do envelope.
 */
@ApplicationScoped
public class AppointmentIngestConsumer {

  public static final String CONSUMER_GROUP = "core-ingest-agenda";

  @Inject InboundEventProcessor processor;
  @Inject AppointmentService service;
  @Inject ObjectMapper objectMapper;

  @Incoming("ingest-agenda-in")
  @Blocking
  public void onIngest(String payload) {
    processor.process(
        payload,
        CONSUMER_GROUP,
        in -> {
          AppointmentRegistration reg =
              objectMapper.convertValue(in.data(), AppointmentRegistration.class);
          return service.register(reg);
        });
  }
}
