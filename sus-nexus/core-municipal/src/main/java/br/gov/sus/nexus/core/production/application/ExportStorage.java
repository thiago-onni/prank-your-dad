package br.gov.sus.nexus.core.production.application;

/**
 * Porta de armazenamento dos arquivos de exportação de produção. Implementação padrão em sistema de
 * arquivos ({@code FileExportStorage}); em produção, object storage (MinIO/S3, bucket com
 * retenção/WORM e criptografia) com a mesma interface.
 */
public interface ExportStorage {

  /** Arquivo armazenado: referência opaca (URI), SHA-256 (hex) e tamanho. */
  record StoredFile(String ref, String sha256, long sizeBytes) {}

  /** Grava o conteúdo em {@code <tenant>/<name>} e devolve a referência e o SHA-256. */
  StoredFile store(String tenantId, String name, byte[] content);

  /** Lê o conteúdo previamente armazenado (auditoria/reexportação). */
  byte[] read(String ref);
}
