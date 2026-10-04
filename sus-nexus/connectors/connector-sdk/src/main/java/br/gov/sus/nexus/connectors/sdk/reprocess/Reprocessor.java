package br.gov.sus.nexus.connectors.sdk.reprocess;

/**
 * Estratégia de reprocessamento de uma {@code integration_message}. O SDK fornece {@link
 * PipelineReprocessor} (padrão: relê a raw zone e reexecuta o pipeline reabrindo a mesma mensagem
 * do ledger); um conector com estado próprio (ex.: RNDS, submissões) declara o seu bean.
 */
public interface Reprocessor {

  /** Chamado só para comandos válidos deste conector/tenant (filtrados pelo handler). */
  ReprocessResult reprocess(ReprocessCommand command);
}
