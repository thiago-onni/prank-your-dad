package br.gov.sus.nexus.core.platform.rules;

import br.gov.sus.nexus.core.platform.ids.Ulid;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Leitura dos conjuntos de regras versionados ({@code platform.rule_set}/{@code rule_version}). A
 * RLS expõe as versões globais ({@code tenant_id IS NULL}) e as do tenant; a do tenant tem
 * precedência. Deve ser chamado dentro de transação com tenant aplicado.
 */
@ApplicationScoped
public class RuleSets {

  /** Versão vigente de um conjunto de regras. */
  public record RuleVersion(
      String id,
      String ruleSetId,
      String version,
      JsonNode definition,
      String approvedBy,
      Instant effectiveFrom) {

    /** Identificador registrado nas decisões (ex.: {@code post-discharge-risk/1}). */
    public String label(String ruleSetName) {
      return ruleSetName + "/" + version;
    }
  }

  private static final String CURRENT_SQL =
      "select v.id, v.rule_set_id, v.version, v.definition::text, v.approved_by, v.effective_from,"
          + " s.name from platform.rule_version v join platform.rule_set s on s.id = v.rule_set_id"
          + " where s.name = ?1 and v.status = 'active'"
          + " and (v.effective_from is null or v.effective_from <= now())"
          + " order by (v.tenant_id is null), v.effective_from desc limit 1";

  @Inject EntityManager entityManager;
  @Inject ObjectMapper objectMapper;

  /** Versão vigente pelo nome do conjunto (ex.: {@code post-discharge-risk}). */
  public Optional<RuleVersion> current(String ruleSetName) {
    @SuppressWarnings("unchecked")
    List<Object[]> rows =
        entityManager.createNativeQuery(CURRENT_SQL).setParameter(1, ruleSetName).getResultList();
    if (rows.isEmpty()) {
      return Optional.empty();
    }
    Object[] r = rows.get(0);
    try {
      return Optional.of(
          new RuleVersion(
              (String) r[0],
              (String) r[1],
              (String) r[2],
              objectMapper.readTree((String) r[3]),
              (String) r[4],
              toInstant(r[5])));
    } catch (Exception e) {
      throw new IllegalStateException("definição de regra ilegível: " + r[0], e);
    }
  }

  /**
   * Cria e ativa uma nova versão do conjunto para o tenant corrente (número sequencial sobre as
   * versões visíveis — globais + do tenant), revogando a versão ativa anterior do próprio tenant; a
   * versão global (seed) permanece, mas a do tenant tem precedência. Serializa criações
   * concorrentes com {@code FOR UPDATE} no {@code rule_set}. O chamador já validou a definição e
   * executou os casos de teste. Deve ser chamado dentro de transação com tenant aplicado.
   */
  public RuleVersion activateNewVersion(
      String ruleSetName,
      String tenantId,
      JsonNode definition,
      JsonNode testCases,
      String actorId) {
    @SuppressWarnings("unchecked")
    List<Object> sets =
        entityManager
            .createNativeQuery("select id from platform.rule_set where name = ?1 for update")
            .setParameter(1, ruleSetName)
            .getResultList();
    if (sets.isEmpty()) {
      throw new IllegalArgumentException("conjunto de regras inexistente: " + ruleSetName);
    }
    String ruleSetId = (String) sets.get(0);
    Number max =
        (Number)
            entityManager
                .createNativeQuery(
                    "select coalesce(max(case when version ~ '^[0-9]+$' then version::int end), 0)"
                        + " from platform.rule_version where rule_set_id = ?1")
                .setParameter(1, ruleSetId)
                .getSingleResult();
    String version = String.valueOf(max.intValue() + 1);
    entityManager
        .createNativeQuery(
            "update platform.rule_version set status = 'revoked' where rule_set_id = ?1"
                + " and tenant_id = ?2 and status = 'active'")
        .setParameter(1, ruleSetId)
        .setParameter(2, tenantId)
        .executeUpdate();
    String id = Ulid.generate(Ulid.RULE_VERSION);
    entityManager
        .createNativeQuery(
            "insert into platform.rule_version (id, tenant_id, rule_set_id, version, status,"
                + " definition, test_cases, approved_by, approved_at, effective_from, created_by)"
                + " values (?1, ?2, ?3, ?4, 'active', cast(?5 as jsonb), cast(?6 as jsonb), ?7,"
                + " now(), now(), ?7)")
        .setParameter(1, id)
        .setParameter(2, tenantId)
        .setParameter(3, ruleSetId)
        .setParameter(4, version)
        .setParameter(5, definition.toString())
        .setParameter(6, testCases.toString())
        .setParameter(7, actorId)
        .executeUpdate();
    return current(ruleSetName)
        .filter(v -> v.id().equals(id))
        .orElseThrow(() -> new IllegalStateException("nova versão não ficou vigente: " + id));
  }

  static Instant toInstant(Object v) {
    if (v == null) {
      return null;
    }
    if (v instanceof java.sql.Timestamp t) {
      return t.toInstant();
    }
    if (v instanceof Instant i) {
      return i;
    }
    if (v instanceof java.time.OffsetDateTime o) {
      return o.toInstant();
    }
    return Instant.parse(v.toString());
  }
}
