package br.gov.sus.nexus.core.integration.api;

import br.gov.sus.nexus.core.platform.pagination.Page;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** API pública do módulo integration (registry, ledger espelho, DLQ, reconciliação). */
public interface IntegrationService {

  List<ConnectorStatusDto> listConnectors();

  ConnectorStatusDto heartbeat(String connectorId, ConnectorHeartbeat heartbeat);

  /** Upsert no ledger espelho (conectores). Retorna a mensagem resultante. */
  IntegrationMessageDto recordMessage(IntegrationMessageWrite write);

  Page<IntegrationMessageDto> listMessages(
      String connectorId,
      IntegrationMessageStatus status,
      OffsetDateTime from,
      OffsetDateTime to,
      String cursor,
      Integer limit);

  IntegrationMessageDto getMessage(String messageId);

  /**
   * Solicita reprocessamento: marca {@code reprocessing} e publica {@code
   * sus.integration.reprocess.requested} (tópico {@code sus.integration.command.v1}) para o
   * conector, com efeitos externos suprimidos (KAF-012). Retorna o event_id publicado.
   */
  String reprocess(String messageId, String reason);

  Page<DeadLetterDto> listDeadLetters(String connectorId, String cursor, Integer limit);

  ReconciliationEntryDto recordReconciliation(ReconciliationEntryDto entry);

  Page<ReconciliationEntryDto> listReconciliation(String connectorId, String cursor, Integer limit);

  /**
   * Aplica um evento {@code sus.integration.status.*} ao registry (consumidor Kafka). {@code data}
   * conforme {@code integration/status.v1.schema.json}.
   */
  void applyStatusEvent(String action, Map<String, Object> data, OffsetDateTime occurredAt);
}
