package br.gov.sus.nexus.core.exams.infrastructure;

import br.gov.sus.nexus.core.exams.api.ExamOrderStatus;
import br.gov.sus.nexus.core.exams.api.ExamService;
import br.gov.sus.nexus.core.exams.application.ExamServiceImpl;
import br.gov.sus.nexus.core.exams.infrastructure.temporal.ExamWorkflowStarter;
import br.gov.sus.nexus.core.platform.events.InboundEventProcessor;
import com.fasterxml.jackson.databind.JsonNode;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.OffsetDateTime;
import org.eclipse.microprofile.reactive.messaging.Incoming;

/**
 * Starter/sinalizador do {@code ExamFollowUpWorkflow} (plano §8.1): {@code sus.exam.order.created}
 * inicia ({@code exam-followup:<id>}); {@code status_changed}, {@code sus.exam.result.*}, {@code
 * sus.task.completed} (tarefa de retorno) e {@code sus.schedule.appointment.no_show} sinalizam.
 */
@ApplicationScoped
public class ExamEventsConsumer {

  public static final String CONSUMER_GROUP = "core-exams-followup";

  @Inject InboundEventProcessor processor;
  @Inject ExamWorkflowStarter starter;
  @Inject ExamService service;

  @Incoming("exams-order-in")
  @Blocking
  public void onOrder(String payload) {
    processor.process(
        payload,
        CONSUMER_GROUP,
        in -> {
          JsonNode d = in.data();
          String id = d.path("exam_order_id").asText(null);
          if (id == null) {
            return Boolean.FALSE;
          }
          String status = d.path("status").asText("requested");
          if ("created".equals(in.action())) {
            if (!ExamOrderStatus.fromWire(status).isTerminal()) {
              starter.startExamFollowUp(
                  in.tenantId(),
                  id,
                  OffsetDateTime.parse(d.path("requested_at").asText()).toInstant());
            }
          } else {
            starter.signalStatus(id, status);
          }
          return Boolean.TRUE;
        });
  }

  @Incoming("exams-result-in")
  @Blocking
  public void onResult(String payload) {
    processor.process(
        payload,
        CONSUMER_GROUP,
        in -> {
          String id = in.data().path("exam_order_id").asText(null);
          if (id == null) {
            return Boolean.FALSE;
          }
          if ("available".equals(in.action())) {
            starter.signalStatus(id, ExamOrderStatus.REPORTED.wire());
          }
          return Boolean.TRUE;
        });
  }

  @Incoming("exams-task-in")
  @Blocking
  public void onTask(String payload) {
    processor.process(
        payload,
        CONSUMER_GROUP,
        in -> {
          if (!"completed".equals(in.action())) {
            return Boolean.FALSE;
          }
          String originId = in.data().path("origin").path("id").asText("");
          if (!originId.startsWith(ExamServiceImpl.FOLLOWUP_ORIGIN_PREFIX)) {
            return Boolean.FALSE;
          }
          String rest = originId.substring(ExamServiceImpl.FOLLOWUP_ORIGIN_PREFIX.length());
          int colon = rest.indexOf(':');
          if (colon >= 0) {
            return Boolean
                .FALSE; // tarefas auxiliares (not_scheduled/no_show) não encerram o retorno
          }
          starter.signalFollowupCompleted(rest);
          return Boolean.TRUE;
        });
  }

  @Incoming("exams-appointment-in")
  @Blocking
  public void onAppointment(String payload) {
    processor.process(
        payload,
        CONSUMER_GROUP,
        in -> {
          if (!"no_show".equals(in.action())) {
            return Boolean.FALSE;
          }
          JsonNode d = in.data();
          String orderId = d.path("exam_order_id").asText(null);
          if (orderId == null) {
            String appointmentId = d.path("appointment_id").asText(null);
            orderId =
                appointmentId == null
                    ? null
                    : service.findByAppointment(appointmentId).map(o -> o.id()).orElse(null);
          }
          if (orderId == null) {
            return Boolean.FALSE;
          }
          starter.signalNoShow(orderId);
          return Boolean.TRUE;
        });
  }
}
