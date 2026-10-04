package br.gov.sus.nexus.core.audit.api;

/** Trilha de auditoria imutável (audit_log). Deve ser chamado dentro da transação de domínio. */
public interface AuditService {

  /** Grava a entrada encadeada ao registro anterior do tenant; retorna o id ({@code aud_...}). */
  String record(AuditEntry entry);
}
