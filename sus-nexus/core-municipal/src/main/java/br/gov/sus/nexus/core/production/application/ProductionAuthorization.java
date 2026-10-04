package br.gov.sus.nexus.core.production.application;

import br.gov.sus.nexus.core.platform.errors.ProblemException;
import br.gov.sus.nexus.core.platform.security.AuthorizationPolicy;
import br.gov.sus.nexus.core.platform.security.CurrentActor;
import br.gov.sus.nexus.core.platform.security.Purpose;
import br.gov.sus.nexus.core.platform.tenant.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jboss.logging.Logger;

/**
 * Autorização das ações de produção pela {@link AuthorizationPolicy} (RBAC local em dev/test; OPA
 * {@code policies/sus/production} com {@code sus.authz.mode=opa}, fail-closed). Complementa o
 * {@code @RolesAllowed} do recurso: tenant, finalidade ({@code production_audit} quando o cliente
 * não declara outra), agente de IA só lê pendências e quatro olhos na aprovação do lote.
 *
 * <p>Ações ({@code <tipo>:<ação>}; o sufixo é a ação do contrato OPA): {@code
 * production_record:read|register_record|correct}, {@code production_issue:read}, {@code
 * production_batch:read|create_batch|approve_batch|export_batch}, {@code
 * production_outcome:register_outcome}, {@code production_summary:read}, {@code
 * production_deadline:read}, {@code production_rule:create_rule_version}.
 */
@ApplicationScoped
public class ProductionAuthorization {

  private static final Logger LOG = Logger.getLogger(ProductionAuthorization.class);

  public static final String FOUR_EYES_REASON = "production_four_eyes_creator_cannot_approve";
  public static final String FOUR_EYES_TYPE = "urn:sus-nexus:problem:four-eyes";
  public static final String FOUR_EYES_DETAIL =
      "quatro olhos: quem gerou o lote de produção não pode aprová-lo (PRO-010)";

  @Inject AuthorizationPolicy policy;
  @Inject TenantContext tenantContext;
  @Inject CurrentActor currentActor;

  public enum Resource {
    RECORD("production_record"),
    ISSUE("production_issue"),
    BATCH("production_batch"),
    OUTCOME("production_outcome"),
    SUMMARY("production_summary"),
    DEADLINE("production_deadline"),
    RULE("production_rule");

    private final String type;

    Resource(String type) {
      this.type = type;
    }

    public String type() {
      return type;
    }
  }

  /** Exige permissão; negado ⇒ 403 {@code application/problem+json} com os motivos. */
  public AuthorizationPolicy.Decision require(Resource resource, String action, String resourceId) {
    return require(resource, action, resourceId, Map.of());
  }

  public AuthorizationPolicy.Decision require(
      Resource resource, String action, String resourceId, Map<String, Object> attributes) {
    Map<String, Object> attrs = new LinkedHashMap<>();
    attrs.put("domain", "production");
    attrs.put("sensitivity", "internal");
    attrs.putAll(attributes);
    AuthorizationPolicy.Decision d =
        policy.evaluate(
            new AuthorizationPolicy.Input(
                tenantContext.require(),
                currentActor.actorId(),
                currentActor.roles(),
                resource.type() + ":" + action,
                resource.type(),
                resourceId,
                currentActor.purpose().orElse(Purpose.PRODUCTION_AUDIT),
                attrs));
    if (!d.allowed()) {
      LOG.infof("produção: %s:%s negado (%s)", resource.type(), action, d.reasons());
      if (d.reasons().contains(FOUR_EYES_REASON)) {
        throw new ProblemException(403, "Quatro olhos", FOUR_EYES_DETAIL, FOUR_EYES_TYPE);
      }
      throw new ProblemException(
          403,
          "Acesso negado",
          "negado pela política de produção ("
              + resource.type()
              + ":"
              + action
              + "): "
              + String.join(", ", d.reasons()),
          "urn:sus-nexus:problem:forbidden");
    }
    return d;
  }
}
