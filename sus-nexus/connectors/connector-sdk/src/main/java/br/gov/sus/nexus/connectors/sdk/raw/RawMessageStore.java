package br.gov.sus.nexus.connectors.sdk.raw;

import br.gov.sus.nexus.connectors.sdk.api.RawMessage;
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
}
