package br.gov.sus.nexus.core.exams.infrastructure;

import br.gov.sus.nexus.core.exams.api.ExamOrderRegistration;
import br.gov.sus.nexus.core.exams.api.ExamResultRegistration;
import br.gov.sus.nexus.core.exams.api.ExamService;
import br.gov.sus.nexus.core.exams.api.ExamStatusChange;
import br.gov.sus.nexus.core.platform.errors.NotFoundException;
import br.gov.sus.nexus.core.platform.events.InboundEventProcessor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Incoming;

/**
 * Consome {@code sus.ingest.exam.v1} (canal {@code ingest-exam-in}): {@code data} é um {@code
 * ExamOrderRegistration} (tem {@code exam_code}), um {@code ExamResultRegistration} (tem {@code
 * reported_at}) ou um {@code ExamStatusChange} (demais). Resultado e status localizam o pedido por
 * {@code source.system} + {@code source_record_id}. Idempotente via {@code event_inbox}.
 */
@ApplicationScoped
public class ExamIngestConsumer {

  public static final String CONSUMER_GROUP = "core-ingest-exam";

  @Inject InboundEventProcessor processor;
  @Inject ExamService service;
  @Inject ObjectMapper objectMapper;

  @Incoming("ingest-exam-in")
  @Blocking
  public void onIngest(String payload) {
    processor.process(
        payload,
        CONSUMER_GROUP,
        in -> {
          JsonNode data = in.data();
          if (data.hasNonNull("exam_code")) {
            return service.register(objectMapper.convertValue(data, ExamOrderRegistration.class));
          }
          if (data.hasNonNull("reported_at")) {
            ExamResultRegistration r =
                objectMapper.convertValue(data, ExamResultRegistration.class);
            return service.registerResultBySource(
                r.source().system(), r.source().sourceRecordId(), r);
          }
          ExamStatusChange c = objectMapper.convertValue(data, ExamStatusChange.class);
          String orderId =
              service
                  .findBySource(c.source().system(), c.source().sourceRecordId())
                  .orElseThrow(
                      () ->
                          new NotFoundException(
                              "pedido de exame",
                              c.source().system() + "/" + c.source().sourceRecordId()))
                  .id();
          return service.changeStatus(orderId, c);
        });
  }
}
