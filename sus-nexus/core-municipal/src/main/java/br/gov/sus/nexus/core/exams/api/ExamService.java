package br.gov.sus.nexus.core.exams.api;

import br.gov.sus.nexus.core.platform.pagination.Page;
import java.util.Optional;

/**
 * API pública do módulo exams (porta única: REST, consumidor de ingestão e workflow). Ciclo pedido
 * → agendamento → realização → laudo → retorno (EXA-001..010). Conteúdo de laudo nunca é
 * armazenado.
 */
public interface ExamService {

  /**
   * Cria/atualiza por vínculo de origem; vincula regulação e agenda por {@code source_record_id}.
   */
  ExamOrderResult register(ExamOrderRegistration registration);

  /**
   * Histórico; {@code scheduled} grava {@code scheduled_at}; {@code performed} grava {@code
   * performed_at}.
   */
  ExamOrderDto changeStatus(String orderId, ExamStatusChange change);

  /**
   * Grava o resultado (metadados), marca {@code reported}, publica {@code
   * sus.exam.result.available} ({@code data_ref} = document_ref); crítico → {@code
   * critical_flagged} + tarefa urgente SEM conteúdo (EXA-008).
   */
  ExamOrderDto registerResult(String orderId, ExamResultRegistration result);

  /** Idem, localizando o pedido pelo vínculo de origem ({@code 404} se inexistente). */
  ExamOrderDto registerResultBySource(
      String sourceSystem, String sourceRecordId, ExamResultRegistration result);

  ExamOrderDto get(String orderId);

  Optional<ExamOrderDto> findByAppointment(String appointmentId);

  Optional<ExamOrderDto> findBySource(String sourceSystem, String sourceRecordId);

  Page<ExamOrderDto> list(
      String citizenId,
      ExamOrderStatus status,
      ExamIssue issue,
      String requestingCnes,
      String cursor,
      Integer limit);

  /**
   * URL assinada (HMAC, 5 min) para o laudo; registra {@code access_log} com finalidade; 404 sem
   * documento.
   */
  ExamDocumentLink documentLink(String orderId, String resultId);

  /** Exames pendentes do cidadão (JOR-008). */
  long countPending(String citizenId);

  // --- suporte ao ExamFollowUpWorkflow (activities idempotentes) ---

  /**
   * Prazo de agendamento vencido: pendência {@code not_scheduled} + tarefa {@code
   * exam_not_scheduled}.
   */
  Optional<String> flagNotScheduled(String orderId);

  /** Falta no agendamento do exame: tarefa {@code no_show_recovery} (se ainda não houver). */
  Optional<String> openNoShowRecovery(String orderId);

  /** Realizado sem laudo no prazo: pendência {@code result_pending}. */
  boolean flagResultPending(String orderId);

  /** Laudo sem retorno no prazo: tarefa {@code exam_result_followup} (se ainda não houver). */
  Optional<String> ensureResultFollowup(String orderId);
}
