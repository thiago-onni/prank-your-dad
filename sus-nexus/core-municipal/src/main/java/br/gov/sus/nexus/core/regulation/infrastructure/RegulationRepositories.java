package br.gov.sus.nexus.core.regulation.infrastructure;

import br.gov.sus.nexus.core.regulation.api.RegulationQuery;
import br.gov.sus.nexus.core.regulation.domain.ProviderCapacity;
import br.gov.sus.nexus.core.regulation.domain.RegulationDecision;
import br.gov.sus.nexus.core.regulation.domain.RegulationIssue;
import br.gov.sus.nexus.core.regulation.domain.RegulationRequest;
import br.gov.sus.nexus.core.regulation.domain.RegulationSourceLink;
import br.gov.sus.nexus.core.regulation.domain.RegulationStatusHistory;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Repositórios Panache do módulo regulation (RLS garante o tenant). */
public final class RegulationRepositories {

  static final String OPEN =
      "('requested','pending_documents','returned','under_review','authorized')";

  private RegulationRepositories() {}

  @ApplicationScoped
  public static class Requests implements PanacheRepositoryBase<RegulationRequest, String> {

    public long countOpen(String citizenId) {
      return count("citizenId = ?1 and status in " + OPEN, citizenId);
    }

    /** Outros pedidos abertos do cidadão para o mesmo serviço (REG-005 duplicate). */
    public List<RegulationRequest> openDuplicates(
        String citizenId, String serviceCode, String excludeId) {
      return list(
          "citizenId = ?1 and requestedServiceCode = ?2 and id <> ?3 and status in "
              + OPEN
              + " order by requestedAt",
          citizenId,
          serviceCode,
          excludeId);
    }

    /**
     * Fila com filtros e ordenação do contrato. Paginação por deslocamento (cursor opaco = offset),
     * já que a ordenação por tempo de espera/prioridade não é keyset por id.
     */
    public List<RegulationRequest> list(RegulationQuery q, int offset, int limitPlusOne) {
      StringBuilder jpql = new StringBuilder("from RegulationRequest r where 1 = 1");
      List<Object> params = new ArrayList<>();
      eq(jpql, params, "r.citizenId", blank(q.citizenId()));
      eq(jpql, params, "r.status", q.status() == null ? null : q.status().wire());
      eq(jpql, params, "r.priority", q.priority() == null ? null : q.priority().wire());
      eq(jpql, params, "r.requestedServiceCode", blank(q.serviceCode()));
      eq(jpql, params, "r.specialty", blank(q.specialty()));
      eq(jpql, params, "r.requestingCnes", blank(q.requestingCnes()));
      eq(jpql, params, "r.providerCnes", blank(q.providerCnes()));
      String issue = blank(q.issue());
      if (issue != null) {
        switch (issue) {
          case "returned" -> jpql.append(" and r.status = 'returned'");
          case "sla_breached" -> jpql.append(" and r.slaBreached = true");
          case "expired" ->
              jpql.append(
                  " and (r.status = 'expired' or exists (select i from RegulationIssue i where"
                      + " i.requestId = r.id and i.status = 'open' and i.kind = 'expired'))");
          case "incomplete" ->
              jpql.append(
                  " and exists (select i from RegulationIssue i where i.requestId = r.id and"
                      + " i.status = 'open' and i.kind in"
                      + " ('missing_document','missing_field','clinical_justification'))");
          default -> {
            params.add(issue);
            jpql.append(
                    " and exists (select i from RegulationIssue i where i.requestId = r.id and"
                        + " i.status = 'open' and i.kind = ?")
                .append(params.size())
                .append(")");
          }
        }
      }
      String sort = q.sort() == null || q.sort().isBlank() ? "waiting_time_desc" : q.sort().trim();
      String order =
          switch (sort) {
            case "created_at_asc" -> " order by r.id asc";
            case "priority_desc" ->
                " order by case r.priority when 'emergency' then 3 when 'urgent' then 2"
                    + " when 'priority' then 1 else 0 end desc, r.requestedAt asc, r.id asc";
            default -> " order by r.requestedAt asc, r.id asc";
          };
      jpql.append(order);
      return find(jpql.toString(), params.toArray())
          .range(offset, offset + limitPlusOne - 1)
          .list();
    }

    private static void eq(StringBuilder q, List<Object> params, String field, String value) {
      if (value != null) {
        params.add(value);
        q.append(" and ").append(field).append(" = ?").append(params.size());
      }
    }
  }

  @ApplicationScoped
  public static class History implements PanacheRepositoryBase<RegulationStatusHistory, String> {
    public List<RegulationStatusHistory> byRequest(String requestId) {
      return list("requestId = ?1 order by occurredAt, recordedAt", requestId);
    }
  }

  @ApplicationScoped
  public static class Decisions implements PanacheRepositoryBase<RegulationDecision, String> {}

  @ApplicationScoped
  public static class Issues implements PanacheRepositoryBase<RegulationIssue, String> {
    public List<RegulationIssue> byRequest(String requestId) {
      return list("requestId = ?1 order by createdAt, id", requestId);
    }

    public List<RegulationIssue> openByRequest(String requestId) {
      return list("requestId = ?1 and status = 'open' order by createdAt, id", requestId);
    }

    public Optional<RegulationIssue> openByKind(String requestId, String kind) {
      return find("requestId = ?1 and status = 'open' and kind = ?2", requestId, kind)
          .firstResultOptional();
    }
  }

  @ApplicationScoped
  public static class SourceLinks implements PanacheRepositoryBase<RegulationSourceLink, String> {
    public Optional<RegulationSourceLink> findBySource(
        String tenantId, String sourceSystem, String sourceRecordId) {
      return find(
              "tenantId = ?1 and sourceSystem = ?2 and sourceRecordId = ?3",
              tenantId,
              sourceSystem,
              sourceRecordId)
          .firstResultOptional();
    }

    public Optional<RegulationSourceLink> findAnySystem(String tenantId, String sourceRecordId) {
      return find(
              "tenantId = ?1 and sourceRecordId = ?2 order by createdAt", tenantId, sourceRecordId)
          .firstResultOptional();
    }
  }

  @ApplicationScoped
  public static class Capacity implements PanacheRepositoryBase<ProviderCapacity, String> {
    public Optional<ProviderCapacity> findByKey(
        String tenantId, String providerCnes, String serviceCode, String competence) {
      return find(
              "tenantId = ?1 and providerCnes = ?2 and serviceCode = ?3 and competence = ?4",
              tenantId,
              providerCnes,
              serviceCode,
              competence)
          .firstResultOptional();
    }

    /** Há registro de oferta para o serviço (feed de capacidade ativo para esse serviço)? */
    public boolean knowsService(String serviceCode) {
      return count("serviceCode = ?1", serviceCode) > 0;
    }

    /** Há vaga disponível para o serviço na competência informada ou posterior? */
    public boolean hasAvailable(String serviceCode, String fromCompetence) {
      return count(
              "serviceCode = ?1 and competence >= ?2 and available > 0",
              serviceCode,
              fromCompetence)
          > 0;
    }

    public List<ProviderCapacity> list(
        String providerCnes, String serviceCode, String competence, int offset, int limitPlusOne) {
      StringBuilder q = new StringBuilder("1 = 1");
      List<Object> params = new ArrayList<>();
      if (providerCnes != null) {
        params.add(providerCnes);
        q.append(" and providerCnes = ?").append(params.size());
      }
      if (serviceCode != null) {
        params.add(serviceCode);
        q.append(" and serviceCode = ?").append(params.size());
      }
      if (competence != null) {
        params.add(competence);
        q.append(" and competence = ?").append(params.size());
      }
      q.append(" order by providerCnes, serviceCode, competence");
      return find(q.toString(), params.toArray()).range(offset, offset + limitPlusOne - 1).list();
    }
  }

  static String blank(String s) {
    return s == null || s.isBlank() ? null : s.trim();
  }
}
