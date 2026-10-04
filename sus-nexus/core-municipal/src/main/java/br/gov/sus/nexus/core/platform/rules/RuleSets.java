package br.gov.sus.nexus.core.platform.rules;

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
