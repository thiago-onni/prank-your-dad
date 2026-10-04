package br.gov.sus.nexus.core.careplan.infrastructure;

import br.gov.sus.nexus.core.careplan.api.CarePlanService;
import br.gov.sus.nexus.core.platform.events.InboundEventProcessor;
import com.fasterxml.jackson.databind.JsonNode;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.OffsetDateTime;
import java.util.Set;
import org.eclipse.microprofile.reactive.messaging.Incoming;

/**
 * Evidência automática dos itens do plano (CUI-002): {@code sus.schedule.appointment.attended}
 * ({@code careplan-appointment-in}) e {@code sus.exam.order.*} com status realizado/laudado ({@code
 * careplan-exam-in}) marcam itens compatíveis como {@code done}. Grupo {@code
 * core-careplan-evidence}, idempotente via {@code event_inbox}.
 */
@ApplicationScoped
public class CarePlanEvidenceConsumer {

  public static final String CONSUMER_GROUP = "core-careplan-evidence";
  static final Set<String> EXAM_DONE = Set.of("performed", "collected", "reported");

  @Inject InboundEventProcessor processor;
  @Inject CarePlanService service;

  @Incoming("careplan-appointment-in")
  @Blocking
  public void onAppointment(String payload) {
    processor.process(
        payload,
        CONSUMER_GROUP,
        in -> {
          if (!"attended".equals(in.action()) || in.citizenId() == null) {
            return Boolean.FALSE;
          }
          JsonNode d = in.data();
          OffsetDateTime occurred =
              in.envelope().occurredAt() == null
                  ? OffsetDateTime.parse(d.path("scheduled_start").asText())
                  : in.envelope().occurredAt();
          service.applyAppointmentEvidence(
              in.citizenId(),
              d.path("appointment_id").asText(null),
              d.path("service_code").asText(null),
              d.hasNonNull("exam_order_id"),
              occurred);
          return Boolean.TRUE;
        });
  }

  @Incoming("careplan-exam-in")
  @Blocking
  public void onExam(String payload) {
    processor.process(
        payload,
        CONSUMER_GROUP,
        in -> {
          JsonNode d = in.data();
          if (in.citizenId() == null || !EXAM_DONE.contains(d.path("status").asText(""))) {
            return Boolean.FALSE;
          }
          service.applyExamEvidence(
              in.citizenId(),
              d.path("exam_order_id").asText(null),
              d.path("exam_code").asText(null),
              in.envelope().occurredAt());
          return Boolean.TRUE;
        });
  }
}
