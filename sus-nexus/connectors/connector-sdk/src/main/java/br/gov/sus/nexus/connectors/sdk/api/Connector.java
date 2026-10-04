package br.gov.sus.nexus.connectors.sdk.api;

/**
 * Contrato de todo conector SUS Nexus (PLANO_IMPLEMENTACAO §7.1).
 *
 * <p>Regras: o conector nunca escreve direto em tópicos de domínio nem no banco do core; publica
 * sempre pela API de entrada do core ({@link #publish(CanonicalBatch)}). Cada mensagem é persistida
 * bruta na raw zone antes de qualquer transformação.
 */
public interface Connector {

  /** Metadados obrigatórios do conector. */
  ConnectorDescriptor descriptor();

  /** Autentica na fonte (e no core, se aplicável). */
  AuthResult authenticate();

  /** Verifica disponibilidade da fonte e dependências; agregado em {@code /q/health}. */
  HealthStatus healthCheck();

  /** Descobre capacidades reais da fonte (entidades, versões, modos). */
  Capabilities discoverCapabilities();

  /** Puxa/recebe dados da fonte e os entrega ao pipeline como {@link RawMessage}. */
  IngestResult ingest(IngestRequest request);

  /** Converte a mensagem bruta em lote canônico usando o {@code field_mapping_version}. */
  CanonicalBatch transform(RawMessage raw);

  /** Valida o lote canônico contra o contrato do core (OpenAPI) e regras do conector. */
  ValidationReport validate(CanonicalBatch batch);

  /** Publica no core (porta única de escrita). */
  PublishResult publish(CanonicalBatch batch);

  /** Compara contagens fonte × publicadas no período. */
  ReconciliationReport reconcile(Period period);

  /** Decide se/quando uma mensagem falha deve ser reprocessada. */
  RetryDecision retry(FailedMessage failed);

  /** Detalhes do erro de uma mensagem (para operador de integração). */
  ErrorDetails getErrorDetails(String messageId);

  /** Emite métricas no sink fornecido (Micrometer por padrão). */
  void emitMetrics(MetricsSink sink);
}
