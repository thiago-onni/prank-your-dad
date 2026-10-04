package br.gov.sus.nexus.core.regulation.api;

import br.gov.sus.nexus.core.platform.ingestion.UpsertResult;
import br.gov.sus.nexus.core.platform.pagination.Page;
import java.util.List;
import java.util.Optional;

/**
 * API pública do módulo regulation (porta única: REST, consumidor de ingestão e workflows). O
 * barramento espelha a fila do sistema oficial, detecta pendências (REG-005) e acompanha SLA; NUNCA
 * decide nem altera prioridade (REG-009).
 */
public interface RegulationService {

  /**
   * Cria ou atualiza por vínculo de origem (tenant, source.system, source_record_id); resolve o
   * cidadão via MPI; calcula {@code sla_due_at} pela política por prioridade; detecta pendências;
   * publica {@code sus.regulation.request.*} (e {@code status.changed} quando o status muda).
   */
  RegulationResult register(RegulationRequestRegistration registration);

  /** Mudança de status recebida do sistema oficial (histórico, decisão, vínculo com agenda...). */
  RegulationRequestDto changeStatus(String requestId, RegulationStatusChange change);

  /** Idem, localizando o pedido pelo vínculo de origem ({@code 404} se inexistente). */
  RegulationRequestDto changeStatusBySource(
      String sourceSystem, String sourceRecordId, RegulationStatusChange change);

  /**
   * Pendência registrada por humano, regra ou agente (origem {@code agent} exige papel próprio).
   */
  RegulationRequestDto addIssue(String requestId, RegulationIssueCreate issue);

  RegulationRequestDto get(String requestId);

  Optional<RegulationRequestDto> findBySourceRecord(String sourceSystem, String sourceRecordId);

  Page<RegulationRequestDto> list(RegulationQuery query);

  Page<ProviderCapacityDto> listCapacity(
      String providerCnes, String serviceCode, String competence, String cursor, Integer limit);

  UpsertResult upsertCapacity(ProviderCapacityUpsert upsert);

  List<RegulationQueueItemDto> queueSummary(String groupBy);

  /** Pedidos abertos do cidadão (JOR-008). */
  long countOpen(String citizenId);

  /**
   * Estouro de SLA registrado pelo {@code RegulationSlaWorkflow} (idempotente): pendência {@code
   * sla_breached}, flag no pedido e evento {@code status.changed} com {@code sla_breached=true}.
   * Vazio se o pedido já tem decisão.
   */
  Optional<RegulationRequestDto> breachSla(String requestId);

  /**
   * Metade do SLA sem decisão (REG-010): se há pendência documental aberta, abre tarefa {@code
   * regulation_pending_document} para a unidade solicitante. Retorna o id da tarefa, se criada.
   */
  Optional<String> halfSlaReached(String requestId);
}
