package br.gov.sus.nexus.core.regulation.infrastructure;

import br.gov.sus.nexus.core.regulation.api.RegulationQueueItemDto;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Agregação SQL da fila regulatória ({@code GET /regulation/queues/summary}): pedidos abertos, por
 * prioridade, espera média/p90 (dias; {@code percentile_cont}), SLA estourado, com pendências,
 * agendados/faltas nos últimos 30 dias e capacidade disponível na competência corrente.
 */
@ApplicationScoped
public class RegulationQueueRepository {

  public static final Set<String> GROUP_BY =
      Set.of("service_code", "specialty", "provider_cnes", "requesting_cnes", "priority");

  private static final Map<String, String> COLUMN =
      Map.of(
          "service_code", "requested_service_code",
          "specialty", "specialty",
          "provider_cnes", "provider_cnes",
          "requesting_cnes", "requesting_cnes",
          "priority", "priority");

  private static final String OPEN =
      "status in ('requested','pending_documents','returned','under_review','authorized')";

  @Inject EntityManager entityManager;

  public List<RegulationQueueItemDto> summary(String groupBy) {
    String col = COLUMN.get(groupBy);
    String waiting = "extract(epoch from (now() - requested_at)) / 86400.0";
    String sql =
        "select coalesce("
            + col
            + ", '') as g,"
            + " count(*) filter (where "
            + OPEN
            + ") as open_requests,"
            + " count(*) filter (where "
            + OPEN
            + " and priority = 'elective'),"
            + " count(*) filter (where "
            + OPEN
            + " and priority = 'priority'),"
            + " count(*) filter (where "
            + OPEN
            + " and priority = 'urgent'),"
            + " count(*) filter (where "
            + OPEN
            + " and priority = 'emergency'),"
            + " avg("
            + waiting
            + ") filter (where "
            + OPEN
            + "),"
            + " percentile_cont(0.9) within group (order by "
            + waiting
            + ") filter (where "
            + OPEN
            + "),"
            + " count(*) filter (where "
            + OPEN
            + " and sla_breached),"
            + " count(*) filter (where "
            + OPEN
            + " and exists (select 1 from regulation.regulation_issue i"
            + " where i.request_id = r.id and i.status = 'open')),"
            + " count(*) filter (where status = 'scheduled'"
            + " and updated_at >= now() - interval '30 days'),"
            + " count(*) filter (where status = 'no_show'"
            + " and updated_at >= now() - interval '30 days')"
            + " from regulation.regulation_request r group by 1 order by 1";
    @SuppressWarnings("unchecked")
    List<Object[]> rows = entityManager.createNativeQuery(sql).getResultList();
    Map<String, Integer> capacity = capacityByGroup(groupBy);
    List<RegulationQueueItemDto> out = new ArrayList<>();
    for (Object[] r : rows) {
      String key = (String) r[0];
      Map<String, Long> byPriority = new LinkedHashMap<>();
      byPriority.put("elective", num(r[2]));
      byPriority.put("priority", num(r[3]));
      byPriority.put("urgent", num(r[4]));
      byPriority.put("emergency", num(r[5]));
      out.add(
          new RegulationQueueItemDto(
              key.isEmpty() ? "unknown" : key,
              key.isEmpty() ? "não informado" : key,
              num(r[1]),
              byPriority,
              dbl(r[6]),
              dbl(r[7]),
              num(r[8]),
              num(r[9]),
              num(r[10]),
              num(r[11]),
              capacity.get(key)));
    }
    return out;
  }

  /**
   * Vagas disponíveis na competência corrente, somadas pelo mesmo agrupamento (quando aplicável).
   */
  private Map<String, Integer> capacityByGroup(String groupBy) {
    String col =
        switch (groupBy) {
          case "service_code" -> "service_code";
          case "provider_cnes" -> "provider_cnes";
          default -> null;
        };
    Map<String, Integer> out = new HashMap<>();
    if (col == null) {
      return out;
    }
    @SuppressWarnings("unchecked")
    List<Object[]> rows =
        entityManager
            .createNativeQuery(
                "select "
                    + col
                    + ", sum(available)::int from regulation.provider_capacity"
                    + " where competence >= to_char(now(), 'YYYYMM') group by 1")
            .getResultList();
    for (Object[] r : rows) {
      out.put((String) r[0], ((Number) r[1]).intValue());
    }
    return out;
  }

  private static long num(Object o) {
    return o == null ? 0L : ((Number) o).longValue();
  }

  private static Double dbl(Object o) {
    if (o == null) {
      return null;
    }
    double v = ((Number) o).doubleValue();
    return Math.round(v * 100.0) / 100.0;
  }
}
