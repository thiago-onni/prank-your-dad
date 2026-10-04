package br.gov.sus.nexus.connectors.sdk.raw;

import br.gov.sus.nexus.connectors.sdk.api.RawMessage;
import java.util.Map;
import java.util.Optional;

/**
 * Raw zone: grava a mensagem bruta (bytes + SHA-256 + metadados) antes de qualquer transformação.
 * Implementações: {@link FileSystemRawMessageStore} (dev/teste) e {@link S3RawMessageStore}
 * (MinIO/S3 em produção).
 */
public interface RawMessageStore {

  /** Grava e retorna a referência (URI + hash). Idempotente por hash. */
  RawMessageRef store(String connectorId, String messageId, RawMessage message);

  /** Lê o conteúdo bruto por referência (para reprocessamento). */
  Optional<byte[]> read(RawMessageRef ref);

  /**
   * Metadados gravados com o bruto (chaves {@code entity_type}, {@code source_record_id}, {@code
   * source_record_version}, {@code content_type} e {@code metadata.<chave>} quando disponíveis).
   * Usado para reconstruir a {@link RawMessage} no reprocessamento quando o ledger local não tem
   * mais a mensagem (ledger em memória após reinício). Vazio quando não suportado.
   */
  default Map<String, String> describe(RawMessageRef ref) {
    return Map.of();
  }
}
