package br.gov.sus.nexus.core.platform.security;

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;
import java.util.Set;

/**
 * Política local baseada em papéis. Matriz mínima da Fase 1; será substituída/complementada pela
 * avaliação OPA. {@code admin_municipal} tem acesso a tudo.
 */
@ApplicationScoped
@DefaultBean
public class RoleBasedAuthorizationPolicy implements AuthorizationPolicy {

  private static final Map<String, Set<String>> MATRIX =
      Map.ofEntries(
          Map.entry(
              "citizen:read",
              Set.of(
                  Roles.PROFISSIONAL_APS,
                  Roles.ACS,
                  Roles.REGULADOR,
                  Roles.AGENDADOR,
                  Roles.GESTOR,
                  Roles.OPERADOR_INTEGRACAO,
                  Roles.AGENTE_IA)),
          Map.entry(
              "citizen:register",
              Set.of(Roles.OPERADOR_INTEGRACAO, Roles.PROFISSIONAL_APS, Roles.ACS)),
          Map.entry("citizen:reveal_identifier", Set.of(Roles.PROFISSIONAL_APS, Roles.REGULADOR)),
          Map.entry(
              "mpi:review", Set.of(Roles.GESTOR, Roles.PROFISSIONAL_APS, Roles.CADASTRO_MESTRE)),
          Map.entry("mpi:merge", Set.of(Roles.GESTOR, Roles.CADASTRO_MESTRE)),
          Map.entry(
              "timeline:read",
              Set.of(
                  Roles.PROFISSIONAL_APS,
                  Roles.ACS,
                  Roles.REGULADOR,
                  Roles.AGENDADOR,
                  Roles.PROFISSIONAL_HOSPITALAR,
                  Roles.OPERADOR_INTEGRACAO,
                  Roles.AGENTE_IA)),
          Map.entry(
              "appointment:read",
              Set.of(
                  Roles.PROFISSIONAL_APS,
                  Roles.ACS,
                  Roles.REGULADOR,
                  Roles.AGENDADOR,
                  Roles.GESTOR,
                  Roles.OPERADOR_INTEGRACAO,
                  Roles.AGENTE_IA)),
          Map.entry(
              "task:read",
              Set.of(
                  Roles.PROFISSIONAL_APS,
                  Roles.ACS,
                  Roles.REGULADOR,
                  Roles.AGENDADOR,
                  Roles.PROFISSIONAL_HOSPITALAR,
                  Roles.GESTOR,
                  Roles.CADASTRO_MESTRE,
                  Roles.AGENTE_IA)),
          Map.entry("integration:read", Set.of(Roles.OPERADOR_INTEGRACAO)),
          Map.entry("integration:reprocess", Set.of(Roles.OPERADOR_INTEGRACAO)),
          Map.entry("audit:read", Set.of(Roles.DPO, Roles.AUDITOR)),
          Map.entry("reference:read", Set.of()),
          Map.entry("terminology:read", Set.of()));

  /**
   * Produção ({@code policies/sus/production}): matriz {@code <tipo>:<ação>} → papéis, espelhando o
   * {@code ProductionResource}. Sem atalho de {@code admin_municipal}; agente de IA só lê
   * pendências; quatro olhos na aprovação do lote ({@code created_by} ≠ ator).
   */
  private static final Map<String, Set<String>> PRODUCTION =
      Map.ofEntries(
          Map.entry(
              "production_record:read",
              Set.of(
                  Roles.AUDITOR, Roles.GESTOR, Roles.OPERADOR_INTEGRACAO, Roles.ADMIN_MUNICIPAL)),
          Map.entry(
              "production_issue:read",
              Set.of(Roles.AUDITOR, Roles.GESTOR, Roles.ADMIN_MUNICIPAL, Roles.AGENTE_IA)),
          Map.entry(
              "production_batch:read", Set.of(Roles.AUDITOR, Roles.GESTOR, Roles.ADMIN_MUNICIPAL)),
          Map.entry(
              "production_summary:read",
              Set.of(Roles.AUDITOR, Roles.GESTOR, Roles.ADMIN_MUNICIPAL)),
          Map.entry(
              "production_deadline:read",
              Set.of(
                  Roles.AUDITOR, Roles.GESTOR, Roles.OPERADOR_INTEGRACAO, Roles.ADMIN_MUNICIPAL)),
          Map.entry(
              "production_record:register_record",
              Set.of(Roles.OPERADOR_INTEGRACAO, Roles.AUDITOR)),
          Map.entry("production_record:correct", Set.of(Roles.AUDITOR)),
          Map.entry("production_batch:create_batch", Set.of(Roles.AUDITOR)),
          Map.entry("production_batch:approve_batch", Set.of(Roles.AUDITOR, Roles.GESTOR)),
          Map.entry("production_batch:export_batch", Set.of(Roles.AUDITOR)),
          Map.entry(
              "production_outcome:register_outcome",
              Set.of(Roles.OPERADOR_INTEGRACAO, Roles.AUDITOR)),
          Map.entry(
              "production_rule:create_rule_version", Set.of(Roles.GESTOR, Roles.ADMIN_MUNICIPAL)));

  /** Domínios clínicos cujos eventos restritos o ACS não vê (contrato OPA, papel {@code acs}). */
  private static final Set<String> CLINICAL_DOMAINS = Set.of("aps", "hospital", "exam");

  private static final Set<String> RESTRICTED_LEVELS = Set.of("restricted", "highly_restricted");

  @Override
  public Decision evaluate(Input input) {
    if (PRODUCTION.containsKey(input.action())) {
      return production(input, PRODUCTION.get(input.action()));
    }
    if (input.roles().contains(Roles.ADMIN_MUNICIPAL)) {
      return Decision.allow();
    }
    Set<String> allowed = MATRIX.get(input.action());
    if (allowed == null) {
      return Decision.deny("ação desconhecida: " + input.action());
    }
    if ("timeline:read".equals(input.action())) {
      return timeline(input, allowed);
    }
    if (allowed.isEmpty()) {
      return Decision.allow();
    }
    for (String role : input.roles()) {
      if (allowed.contains(role)) {
        return Decision.allow();
      }
    }
    return Decision.deny("papel insuficiente para " + input.action());
  }

  private static Decision production(Input input, Set<String> allowed) {
    boolean agent = input.roles().contains(Roles.AGENTE_IA);
    if (agent && !"production_issue:read".equals(input.action())) {
      return Decision.deny("production_agent_read_only");
    }
    if (input.roles().stream().noneMatch(allowed::contains)) {
      return Decision.deny("production_role_not_allowed");
    }
    if ("production_batch:approve_batch".equals(input.action())) {
      Object createdBy = input.attributes().get("created_by");
      if (createdBy == null) {
        return Decision.deny("production_batch_creator_unknown");
      }
      if (createdBy.equals(input.actorId())) {
        return Decision.deny("production_four_eyes_creator_cannot_approve");
      }
    }
    return Decision.allow();
  }

  /**
   * Leitura de evento da timeline: filtragem por sensibilidade. O ACS (sem outro papel clínico)
   * nunca vê {@code highly_restricted} nem eventos {@code restricted} de {@code aps}/{@code
   * hospital}/{@code exam}; agendador e operador de integração ficam limitados a {@code
   * restricted}.
   */
  private Decision timeline(Input input, Set<String> allowed) {
    String domain = String.valueOf(input.attributes().getOrDefault("domain", ""));
    String sensitivity = String.valueOf(input.attributes().getOrDefault("sensitivity", "internal"));
    boolean anyRole = input.roles().stream().anyMatch(allowed::contains);
    if (!anyRole) {
      return Decision.deny("papel insuficiente para timeline:read");
    }
    boolean fullClinical =
        input.roles().contains(Roles.PROFISSIONAL_APS)
            || input.roles().contains(Roles.PROFISSIONAL_HOSPITALAR)
            || input.roles().contains(Roles.REGULADOR);
    if (fullClinical) {
      return Decision.allow();
    }
    if ("highly_restricted".equals(sensitivity)) {
      return Decision.deny("sensitivity_above_role");
    }
    if (input.roles().contains(Roles.ACS)
        && RESTRICTED_LEVELS.contains(sensitivity)
        && CLINICAL_DOMAINS.contains(domain)) {
      return Decision.deny("acs_clinical_domain_restricted");
    }
    return Decision.allow();
  }
}
