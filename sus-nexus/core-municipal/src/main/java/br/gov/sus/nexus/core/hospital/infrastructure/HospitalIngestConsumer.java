package br.gov.sus.nexus.core.hospital.infrastructure;

import br.gov.sus.nexus.core.hospital.api.CounterReferralRegistration;
import br.gov.sus.nexus.core.hospital.api.DischargeRegistration;
import br.gov.sus.nexus.core.hospital.api.HospitalMovementRegistration;
import br.gov.sus.nexus.core.hospital.api.HospitalService;
import br.gov.sus.nexus.core.platform.errors.NotFoundException;
import br.gov.sus.nexus.core.platform.events.InboundEventProcessor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Incoming;

/**
 * Consome {@code sus.ingest.hospital.v1} (canal {@code ingest-hospital-in}): {@code data} é um
 * {@code HospitalMovementRegistration} (tem {@code movement}), um {@code DischargeRegistration}
 * (tem {@code disposition}; localiza o episódio por {@code source}) ou um {@code
 * CounterReferralRegistration} (tem {@code received_at}). Idempotente via {@code event_inbox}.
 */
@ApplicationScoped
public class HospitalIngestConsumer {

  public static final String CONSUMER_GROUP = "core-ingest-hospital";

  @Inject InboundEventProcessor processor;
  @Inject HospitalService service;
  @Inject ObjectMapper objectMapper;

  @Incoming("ingest-hospital-in")
  @Blocking
  public void onIngest(String payload) {
    processor.process(
        payload,
        CONSUMER_GROUP,
        in -> {
          JsonNode data = in.data();
          if (data.hasNonNull("movement")) {
            return service.register(
                objectMapper.convertValue(data, HospitalMovementRegistration.class));
          }
          if (data.hasNonNull("disposition")) {
            DischargeRegistration d = objectMapper.convertValue(data, DischargeRegistration.class);
            return service.dischargeBySource(d.source().system(), d.source().sourceRecordId(), d);
          }
          CounterReferralRegistration c =
              objectMapper.convertValue(data, CounterReferralRegistration.class);
          String episodeId =
              service
                  .findBySource(c.source().system(), c.source().sourceRecordId())
                  .orElseThrow(
                      () ->
                          new NotFoundException(
                              "episódio hospitalar",
                              c.source().system() + "/" + c.source().sourceRecordId()))
                  .id();
          return service.counterReferral(episodeId, c);
        });
  }
}
