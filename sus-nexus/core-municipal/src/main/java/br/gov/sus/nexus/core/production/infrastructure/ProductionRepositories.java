package br.gov.sus.nexus.core.production.infrastructure;

import br.gov.sus.nexus.core.production.domain.ProductionBatch;
import br.gov.sus.nexus.core.production.domain.ProductionBatchItem;
import br.gov.sus.nexus.core.production.domain.ProductionOutcome;
import br.gov.sus.nexus.core.production.domain.ProductionRecord;
import br.gov.sus.nexus.core.production.domain.ProductionRecordHistory;
import br.gov.sus.nexus.core.production.domain.ProductionSourceLink;
import br.gov.sus.nexus.core.production.domain.ProductionValidationIssue;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.TypedQuery;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Repositórios Panache do módulo production (RLS garante o tenant). */
public final class ProductionRepositories {

  private ProductionRepositories() {}

  /** Monta cláusulas {@code and campo = ?n} com parâmetros posicionais. */
  static final class Where {
    final StringBuilder q;
    final List<Object> params = new ArrayList<>();

    Where(String base) {
      this.q = new StringBuilder(base);
    }

    Where eq(String field, Object value) {
      if (value != null) {
        params.add(value);
        q.append(" and ").append(field).append(" = ?").append(params.size());
      }
      return this;
    }

    Where lt(String field, Object value) {
      if (value != null) {
        params.add(value);
        q.append(" and ").append(field).append(" < ?").append(params.size());
      }
      return this;
    }
  }

  @ApplicationScoped
  public static class Records implements PanacheRepositoryBase<ProductionRecord, String> {

    public List<ProductionRecord> list(
        String competence,
        String cnes,
        String kind,
        String status,
        String procedureCode,
        String citizenId,
        String batchId,
        String beforeId,
        int limitPlusOne) {
      Where w =
          new Where("1 = 1")
              .eq("competence", competence)
              .eq("cnes", cnes)
              .eq("kind", kind)
              .eq("status", status)
              .eq("procedureCode", procedureCode)
              .eq("citizenId", citizenId)
              .eq("batchId", batchId)
              .lt("id", beforeId);
      return find(w.q + " order by id desc", w.params.toArray()).page(0, limitPlusOne).list();
    }

    /**
     * Duplicidade: mesmo cidadão + procedimento + data + CNES em registro anterior (criado antes)
     * não rejeitado.
     */
    public boolean existsEarlierDuplicate(ProductionRecord r) {
      if (r.citizenId == null) {
        return false;
      }
      return count(
              "citizenId = ?1 and procedureCode = ?2 and attendanceDate = ?3 and cnes = ?4"
                  + " and id <> ?5 and status <> 'rejected'"
                  + " and (createdAt < ?6 or (createdAt = ?6 and id < ?5))",
              r.citizenId,
              r.procedureCode,
              r.attendanceDate,
              r.cnes,
              r.id,
              r.createdAt)
          > 0;
    }

    /** Registros validados da competência/CNES/tipo fora de qualquer lote (ordem estável). */
    public List<ProductionRecord> validatedWithoutBatch(
        String competence, String cnes, String kind) {
      return list(
          "competence = ?1 and cnes = ?2 and kind = ?3 and status = 'validated'"
              + " and batchId is null order by attendanceDate, id",
          competence,
          cnes,
          kind);
    }

    public List<ProductionRecord> byBatch(String batchId) {
      return list("batchId = ?1 order by attendanceDate, id", batchId);
    }

    public long countOpenInCompetence(String competence) {
      return count(
          "competence = ?1 and status in ('generated','pending','validated','corrected')",
          competence);
    }

    public long countByCompetenceAndStatus(String competence, String status) {
      return count("competence = ?1 and status = ?2", competence, status);
    }

    /** Competências com produção ainda no barramento (pré-auditoria). */
    public List<String> openCompetences() {
      return getEntityManager()
          .createQuery(
              "select distinct r.competence from ProductionRecord r"
                  + " where r.status in ('generated','pending','validated','corrected')"
                  + " order by r.competence",
              String.class)
          .getResultList();
    }
  }

  @ApplicationScoped
  public static class History implements PanacheRepositoryBase<ProductionRecordHistory, String> {
    public List<ProductionRecordHistory> byRecord(String recordId) {
      return list("recordId = ?1 order by occurredAt, id", recordId);
    }
  }

  @ApplicationScoped
  public static class Issues implements PanacheRepositoryBase<ProductionValidationIssue, String> {

    public List<ProductionValidationIssue> byRecord(String recordId) {
      return list("recordId = ?1 order by createdAt, id", recordId);
    }

    public List<ProductionValidationIssue> openByRecord(String recordId) {
      return list("recordId = ?1 and status = 'open' order by createdAt, id", recordId);
    }

    /** Pendências com dados do registro (sem cidadão): {@code [issue, record]}. */
    public List<Object[]> list(
        String severity,
        String ruleId,
        String competence,
        String cnes,
        String kind,
        String status,
        String recordId,
        String beforeId,
        int limitPlusOne) {
      Where w =
          new Where(
                  "select i, r from ProductionValidationIssue i, ProductionRecord r"
                      + " where r.id = i.recordId")
              .eq("i.severity", severity)
              .eq("i.ruleId", ruleId)
              .eq("r.competence", competence)
              .eq("r.cnes", cnes)
              .eq("r.kind", kind)
              .eq("i.status", status)
              .eq("i.recordId", recordId)
              .lt("i.id", beforeId);
      TypedQuery<Object[]> query =
          getEntityManager().createQuery(w.q + " order by i.id desc", Object[].class);
      for (int i = 0; i < w.params.size(); i++) {
        query.setParameter(i + 1, w.params.get(i));
      }
      return query.setMaxResults(limitPlusOne).getResultList();
    }

    /** Pendências abertas por regra/severidade da competência (painel). */
    public List<Object[]> openByRule(String competence, String cnes) {
      Where w =
          new Where(
                  "select i.ruleId, i.severity, count(i) from ProductionValidationIssue i,"
                      + " ProductionRecord r where r.id = i.recordId and i.status = 'open'")
              .eq("r.competence", competence)
              .eq("r.cnes", cnes);
      TypedQuery<Object[]> query =
          getEntityManager()
              .createQuery(
                  w.q + " group by i.ruleId, i.severity order by count(i) desc, i.ruleId",
                  Object[].class);
      for (int i = 0; i < w.params.size(); i++) {
        query.setParameter(i + 1, w.params.get(i));
      }
      return query.getResultList();
    }
  }

  @ApplicationScoped
  public static class Batches implements PanacheRepositoryBase<ProductionBatch, String> {
    public List<ProductionBatch> list(
        String competence, String cnes, String status, String beforeId, int limitPlusOne) {
      Where w =
          new Where("1 = 1")
              .eq("competence", competence)
              .eq("cnes", cnes)
              .eq("status", status)
              .lt("id", beforeId);
      return find(w.q + " order by id desc", w.params.toArray()).page(0, limitPlusOne).list();
    }
  }

  @ApplicationScoped
  public static class BatchItems implements PanacheRepositoryBase<ProductionBatchItem, String> {
    public List<ProductionBatchItem> active(String batchId) {
      return list("batchId = ?1 and removedAt is null order by lineNumber", batchId);
    }

    public Optional<ProductionBatchItem> activeFor(String batchId, String recordId) {
      return find("batchId = ?1 and recordId = ?2 and removedAt is null", batchId, recordId)
          .firstResultOptional();
    }
  }

  @ApplicationScoped
  public static class Outcomes implements PanacheRepositoryBase<ProductionOutcome, String> {
    public Optional<ProductionOutcome> existing(
        String sourceSystem,
        String sourceRecordId,
        String outcome,
        String recordId,
        String batchId) {
      if (recordId != null) {
        return find(
                "sourceSystem = ?1 and sourceRecordId = ?2 and outcome = ?3 and recordId = ?4",
                sourceSystem,
                sourceRecordId,
                outcome,
                recordId)
            .firstResultOptional();
      }
      return find(
              "sourceSystem = ?1 and sourceRecordId = ?2 and outcome = ?3 and batchId = ?4"
                  + " and recordId is null",
              sourceSystem,
              sourceRecordId,
              outcome,
              batchId)
          .firstResultOptional();
    }
  }

  @ApplicationScoped
  public static class SourceLinks implements PanacheRepositoryBase<ProductionSourceLink, String> {
    public Optional<ProductionSourceLink> findBySource(
        String tenantId, String sourceSystem, String sourceRecordId) {
      return find(
              "tenantId = ?1 and sourceSystem = ?2 and sourceRecordId = ?3",
              tenantId,
              sourceSystem,
              sourceRecordId)
          .firstResultOptional();
    }
  }

  /** Marca temporal truncada a microssegundos (precisão do PostgreSQL). */
  public static Instant micros(Instant i) {
    return i == null ? null : i.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
  }
}
