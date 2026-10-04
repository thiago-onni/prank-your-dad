package br.gov.sus.nexus.core.production.application;

/**
 * Porta de armazenamento dos arquivos de exportação de produção, selecionada por {@code
 * sus.production.export-storage}: {@code file} (padrão em dev/test — {@code FileExportStorage}) ou
 * {@code s3} (object storage S3/MinIO com SSE — {@code S3ExportStorage}). O arquivo contém CNS em
 * claro: diretório/bucket com acesso restrito ao core.
 */
public interface ExportStorage {

  /** Arquivo armazenado: referência opaca (URI), SHA-256 (hex) e tamanho. */
  record StoredFile(String ref, String sha256, long sizeBytes) {}

  /**
   * Grava o conteúdo em {@code <tenant>/<competência>/<lote>/<name>} sem sobrescrever e devolve a
   * referência ({@code file://} ou {@code s3://}) e o SHA-256.
   */
  StoredFile store(String tenantId, String competence, String batchId, String name, byte[] content);

  /** Lê o conteúdo previamente armazenado (auditoria/reexportação). */
  byte[] read(String ref);

  /** Valida os componentes da chave (sem travessia de caminho). */
  static void checkKey(String tenantId, String competence, String batchId, String name) {
    if (tenantId == null
        || !tenantId.matches("^ibge_[0-9]{7}$")
        || competence == null
        || !competence.matches("^[0-9]{6}$")
        || batchId == null
        || !batchId.matches("^[A-Za-z0-9_]+$")
        || name == null
        || !name.matches("^[A-Za-z0-9_][A-Za-z0-9_.-]*$")) {
      throw new IllegalArgumentException("nome de arquivo de exportação inválido");
    }
  }

  /** SHA-256 em hexadecimal. */
  static String sha256(byte[] content) {
    try {
      return java.util.HexFormat.of()
          .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(content));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
