package br.gov.sus.nexus.core.platform.security;

/** Papéis (realm roles do Keycloak) usados em {@code @RolesAllowed}. */
public final class Roles {

  public static final String ADMIN_MUNICIPAL = "admin_municipal";
  public static final String GESTOR = "gestor";
  public static final String PROFISSIONAL_APS = "profissional_aps";
  public static final String ACS = "acs";
  public static final String REGULADOR = "regulador";
  public static final String AGENDADOR = "agendador";
  public static final String AUDITOR = "auditor";
  public static final String DPO = "dpo";
  public static final String OPERADOR_INTEGRACAO = "operador_integracao";
  public static final String AGENTE_IA = "agente_ia";

  private Roles() {}
}
