package br.gov.sus.nexus.core.hospital.infrastructure;

import br.gov.sus.nexus.core.hospital.api.HospitalService;
import br.gov.sus.nexus.core.hospital.application.HospitalServiceImpl;
import br.gov.sus.nexus.core.hospital.infrastructure.temporal.HospitalWorkflowStarter;
import br.gov.sus.nexus.core.platform.events.InboundEventProcessor;
import com.fasterxml.jackson.databind.JsonNode;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Optional;
import org.eclipse.microprofile.reactive.messaging.Incoming;

/**
 * Starter/sinalizador do {@code DischargeFollowUpWorkflow}: {@code
 * sus.hospital.discharge.completed} inicia ({@code discharge-followup:<hep>}, salvo óbito); {@code
 * sus.task.completed} da tarefa de contato (origem {@code discharge-followup:<hep>}) sinaliza com o
 * {@code outcome}.
 */
@ApplicationScoped
public class HospitalEventsConsumer {

  public static final String CONSUMER_GROUP = "core-hospital-followup";

  @Inject InboundEventProcessor processor;
  @Inject HospitalWorkflowStarter starter;
  @Inject HospitalService service;

  @Incoming("hospital-discharge-in")
  @Blocking
  public void onDischarge(String payload) {
    processor.process(
        payload,
        CONSUMER_GROUP,
        in -> {
          JsonNode d = in.data();
          String id = d.path("hospital_episode_id").asText(null);
          if (id == null || !"completed".equals(in.action())) {
            return Boolean.FALSE;
          }
          if ("deceased".equals(d.path("disposition").asText(""))) {
            return Boolean.FALSE;
          }
          Optional<HospitalService.FollowupSnapshot> snapshot = service.followupSnapshot(id);
          if (snapshot.isEmpty() || snapshot.get().dueAt() == null) {
            return Boolean.FALSE;
          }
          starter.startDischargeFollowUp(
              in.tenantId(),
              id,
              Instant.parse(snapshot.get().dueAt()),
              d.path("risk_level").asText(snapshot.get().riskLevel()));
          return Boolean.TRUE;
        });
  }

  @Incoming("hospital-task-in")
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
          if (!originId.startsWith(HospitalServiceImpl.FOLLOWUP_ORIGIN_PREFIX)) {
            return Boolean.FALSE;
          }
          String rest = originId.substring(HospitalServiceImpl.FOLLOWUP_ORIGIN_PREFIX.length());
          if (rest.contains(":")) {
            return Boolean.FALSE; // tarefas auxiliares (active_search) não encerram o contato
          }
          starter.signalContact(rest, in.data().path("outcome").asText("contact_made"));
          return Boolean.TRUE;
        });
  }
}
