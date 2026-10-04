package br.gov.sus.nexus.core.platform.tenant;

import jakarta.enterprise.context.RequestScoped;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Tenant corrente da requisição ({@code ibge_<código IBGE 7 dígitos>}). Resolvido pelo {@link
 * TenantFilter} a partir do claim JWT {@code municipality_id} ou, em dev/test, do header {@code
 * X-Tenant-Id}. Toda transação de banco recebe {@code SET LOCAL app.tenant_id} via {@link
 * TenantTransactional}.
 */
@RequestScoped
public class TenantContext {

  public static final Pattern TENANT_PATTERN = Pattern.compile("^ibge_[0-9]{7}$");

  private String tenantId;

  public void set(String tenantId) {
    if (tenantId == null || !TENANT_PATTERN.matcher(tenantId).matches()) {
      throw new TenantRequiredException("tenant inválido: esperado ibge_<7 dígitos>");
    }
    this.tenantId = tenantId;
  }

  public boolean isPresent() {
    return tenantId != null;
  }

  public Optional<String> find() {
    return Optional.ofNullable(tenantId);
  }

  /** Retorna o tenant corrente ou lança {@link TenantRequiredException}. */
  public String require() {
    if (tenantId == null) {
      throw new TenantRequiredException("tenant não resolvido para a requisição");
    }
    return tenantId;
  }

  public void clear() {
    this.tenantId = null;
  }
}
