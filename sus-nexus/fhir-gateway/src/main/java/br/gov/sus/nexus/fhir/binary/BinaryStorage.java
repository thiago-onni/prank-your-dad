package br.gov.sus.nexus.fhir.binary;

import java.util.Optional;

/**
 * Armazenamento do conteúdo de {@code Binary} (object storage). O banco guarda apenas metadados
 * ({@code fhir.fhir_binary}); o conteúdo nunca é persistido inline (invariante {@code sus-doc-1}).
 */
public interface BinaryStorage {

  /** Grava o conteúdo e devolve a chave de armazenamento. */
  String put(String tenantId, String id, String contentType, byte[] content);

  /** Lê o conteúdo pela chave devolvida por {@link #put}. */
  Optional<byte[]> get(String key);

  /** Descrição curta (para logs/diagnóstico, sem segredos). */
  String describe();
}
