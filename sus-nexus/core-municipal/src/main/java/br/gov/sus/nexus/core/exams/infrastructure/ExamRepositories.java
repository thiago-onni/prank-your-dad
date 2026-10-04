package br.gov.sus.nexus.core.exams.infrastructure;

import br.gov.sus.nexus.core.exams.domain.ExamOrder;
import br.gov.sus.nexus.core.exams.domain.ExamOrderSourceLink;
import br.gov.sus.nexus.core.exams.domain.ExamOrderStatusHistory;
import br.gov.sus.nexus.core.exams.domain.ExamResult;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Repositórios Panache do módulo exams (RLS garante o tenant). */
public final class ExamRepositories {

  static final String PENDING = "('requested','authorized','scheduled','collected','performed')";

  private ExamRepositories() {}

  @ApplicationScoped
  public static class Orders implements PanacheRepositoryBase<ExamOrder, String> {

    public long countPending(String citizenId) {
      return count("citizenId = ?1 and status in " + PENDING, citizenId);
    }

    public Optional<ExamOrder> findByAppointment(String appointmentId) {
      return find("appointmentId = ?1 order by id desc", appointmentId).firstResultOptional();
    }

    public List<ExamOrder> list(
        String citizenId,
        String status,
        String issue,
        String requestingCnes,
        String beforeId,
        int limitPlusOne) {
      StringBuilder q = new StringBuilder("1 = 1");
      List<Object> params = new ArrayList<>();
      if (citizenId != null) {
        params.add(citizenId);
        q.append(" and citizenId = ?").append(params.size());
      }
      if (status != null) {
        params.add(status);
        q.append(" and status = ?").append(params.size());
      }
      if (requestingCnes != null) {
        params.add(requestingCnes);
        q.append(" and requestingCnes = ?").append(params.size());
      }
      if (issue != null) {
        List<String> ids = idsWithIssue(issue);
        if (ids.isEmpty()) {
          return List.of();
        }
        params.add(ids);
        q.append(" and id in ?").append(params.size());
      }
      if (beforeId != null) {
        params.add(beforeId);
        q.append(" and id < ?").append(params.size());
      }
      q.append(" order by id desc");
      return find(q.toString(), params.toArray()).page(0, limitPlusOne).list();
    }

    /** Pedidos (do tenant, via RLS) com a pendência persistida em {@code issues text[]}. */
    @SuppressWarnings("unchecked")
    private List<String> idsWithIssue(String issue) {
      return getEntityManager()
          .createNativeQuery("select id from exams.exam_order where cast(?1 as text) = any(issues)")
          .setParameter(1, issue)
          .getResultList();
    }
  }

  @ApplicationScoped
  public static class History implements PanacheRepositoryBase<ExamOrderStatusHistory, String> {
    public List<ExamOrderStatusHistory> byOrder(String orderId) {
      return list("orderId = ?1 order by occurredAt, recordedAt", orderId);
    }
  }

  @ApplicationScoped
  public static class Results implements PanacheRepositoryBase<ExamResult, String> {
    public List<ExamResult> byOrder(String orderId) {
      return list("orderId = ?1 order by reportedAt, id", orderId);
    }

    public Optional<ExamResult> latest(String orderId) {
      return find("orderId = ?1 order by reportedAt desc, id desc", orderId).firstResultOptional();
    }
  }

  @ApplicationScoped
  public static class SourceLinks implements PanacheRepositoryBase<ExamOrderSourceLink, String> {
    public Optional<ExamOrderSourceLink> findBySource(
        String tenantId, String sourceSystem, String sourceRecordId) {
      return find(
              "tenantId = ?1 and sourceSystem = ?2 and sourceRecordId = ?3",
              tenantId,
              sourceSystem,
              sourceRecordId)
          .firstResultOptional();
    }
  }
}
