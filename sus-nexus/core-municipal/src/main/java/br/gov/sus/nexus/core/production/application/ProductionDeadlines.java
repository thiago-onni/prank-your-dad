package br.gov.sus.nexus.core.production.application;

import br.gov.sus.nexus.core.sharedkernel.Competence;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Prazos de apresentação por competência (PRO-007): {@code production.production_deadline} (linha
 * do tenant → global/seed) e, na falta, o padrão configurado ({@code sus.production.deadline-day},
 * dia do mês seguinte às 23:59:59 em {@code sus.production.deadline-zone}). Deve ser chamado dentro
 * de transação com tenant aplicado.
 */
@ApplicationScoped
public class ProductionDeadlines {

  /** Prazo resolvido e sua origem ({@code tenant}, {@code global} ou {@code default}). */
  public record Deadline(
      String competence, Instant deadlineAt, List<Integer> alertDays, String by) {}

  @Inject EntityManager entityManager;

  @ConfigProperty(name = "sus.production.deadline-day", defaultValue = "10")
  int deadlineDay;

  @ConfigProperty(name = "sus.production.deadline-zone", defaultValue = "America/Sao_Paulo")
  String deadlineZone;

  public Deadline resolve(String competence) {
    @SuppressWarnings("unchecked")
    List<Object[]> rows =
        entityManager
            .createNativeQuery(
                "select deadline_at, tenant_id, alert_days from production.production_deadline"
                    + " where competence = ?1 order by (tenant_id is null) limit 1")
            .setParameter(1, competence)
            .getResultList();
    if (!rows.isEmpty()) {
      Object[] r = rows.get(0);
      return new Deadline(
          competence, toInstant(r[0]), alertDays(r[2]), r[1] == null ? "global" : "tenant");
    }
    YearMonth next = new Competence(competence).toYearMonth().plusMonths(1);
    int day = Math.min(Math.max(1, deadlineDay), next.lengthOfMonth());
    Instant at =
        next.atDay(day)
            .atTime(LocalTime.of(23, 59, 59))
            .atZone(ZoneId.of(deadlineZone))
            .toInstant();
    return new Deadline(competence, at, List.of(5, 1), "default");
  }

  private static List<Integer> alertDays(Object v) {
    if (v instanceof Integer[] a) {
      return Arrays.asList(a);
    }
    if (v instanceof int[] a) {
      List<Integer> out = new ArrayList<>();
      for (int i : a) {
        out.add(i);
      }
      return out;
    }
    if (v instanceof java.sql.Array a) {
      try {
        return Arrays.asList((Integer[]) a.getArray());
      } catch (java.sql.SQLException e) {
        return List.of(5, 1);
      }
    }
    return List.of(5, 1);
  }

  static Instant toInstant(Object v) {
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
