package br.gov.sus.nexus.core.production.api;

import br.gov.sus.nexus.core.platform.pagination.Page;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * API pública do módulo production (PRO-001..010; porta única: REST, consumidor de ingestão,
 * workflow e job de prazos). Pré-auditoria por regras versionadas ({@code platform.rule_set
 * production-validation}); lotes só com registros {@code validated} e aprovação humana; exportação
 * em layout de referência — a transmissão oficial continua no sistema oficial. Agentes de IA nunca
 * corrigem, aprovam nem exportam (PRO-010).
 */
public interface ProductionService {

  /** Upsert por vínculo de origem + pré-auditoria imediata. */
  ProductionRecordResult register(ProductionRecordRegistration registration);

  ProductionRecordDto get(String recordId);

  Optional<ProductionRecordDto> findBySource(String sourceSystem, String sourceRecordId);

  Page<ProductionRecordDto> list(
      String competence,
      String cnes,
      ProductionKind kind,
      ProductionRecordStatus status,
      String procedureCode,
      String citizenId,
      String batchId,
      String cursor,
      Integer limit);

  /** Correção humana com justificativa (PRO-006) → revalidação; sinaliza o workflow. */
  ProductionRecordDto correct(String recordId, ProductionCorrection correction);

  Page<ProductionIssueDto> issues(
      String severity,
      String ruleId,
      String competence,
      String cnes,
      ProductionKind kind,
      String status,
      String recordId,
      String cursor,
      Integer limit);

  ProductionBatchDto createBatch(ProductionBatchCreate create);

  ProductionBatchDto getBatch(String batchId);

  Page<ProductionBatchDto> listBatches(
      String competence, String cnes, String status, String cursor, Integer limit);

  ProductionBatchDto approveBatch(String batchId, ProductionBatchApproval approval);

  ProductionBatchDto exportBatch(String batchId, ProductionBatchExportRequest request);

  ProductionOutcomeResult registerOutcome(ProductionOutcomeRegistration outcome);

  ProductionSummary summary(String competence, String cnes);

  List<ProductionDeadlineDto> deadlines(String fromCompetence, String toCompetence);

  // --- suporte ao ProductionPreAuditWorkflow / CompetenceDeadlineJob ---

  /** Estado da pré-auditoria para o workflow. */
  record PreAuditSnapshot(String status, String deadlineAt, int openErrors) {}

  Optional<PreAuditSnapshot> preAuditSnapshot(String recordId);

  /** Revalida (idempotente) se o registro ainda está em pré-auditoria; devolve o status. */
  String revalidate(String recordId);

  /**
   * Prazo da competência vencido com o registro ainda pendente: pendência {@code deadline_missed}
   * (erro, origem workflow). Idempotente; devolve o status final.
   */
  String expire(String recordId);

  /** Prazo de apresentação da competência (tenant → global → padrão configurado). */
  Instant deadlineFor(String competence);

  /** Alertas D-5/D-1 da competência com produção em aberto (tarefas na fila auditoria). */
  int raiseDeadlineAlerts(Instant now);
}
