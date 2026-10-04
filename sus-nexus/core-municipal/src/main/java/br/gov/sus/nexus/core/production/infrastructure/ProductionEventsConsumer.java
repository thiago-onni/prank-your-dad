package br.gov.sus.nexus.core.production.infrastructure;

import br.gov.sus.nexus.core.platform.events.InboundEventProcessor;
import br.gov.sus.nexus.core.production.api.ProductionService;
import br.gov.sus.nexus.core.production.infrastructure.temporal.ProductionWorkflowStarter;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Incoming;

/**
 * Starter do {@code ProductionPreAuditWorkflow} (Workflow 3): {@code sus.production.record.created}
 * inicia {@code production-preaudit:<prod_id>} com o prazo da competência lido no início. Produção
 * NÃO é projetada na timeline do cidadão (nenhum consumidor do journey assina {@code
 * sus.production.*}).
 */
@ApplicationScoped
public class ProductionEventsConsumer {

  public static final String CONSUMER_GROUP = "core-production-preaudit";

  @Inject InboundEventProcessor processor;
  @Inject ProductionWorkflowStarter starter;
  @Inject ProductionService service;

  @Incoming("production-record-in")
  @Blocking
  public void onRecord(String payload) {
    processor.process(
        payload,
        CONSUMER_GROUP,
        in -> {
          String id = in.data().path("production_record_id").asText(null);
          if (id == null || !"created".equals(in.action())) {
            return Boolean.FALSE;
          }
          String competence = in.data().path("competence").asText(null);
          if (competence == null) {
            return Boolean.FALSE;
          }
          return starter.startPreAudit(in.tenantId(), id, service.deadlineFor(competence));
        });
  }
}
