package br.gov.sus.nexus.core.integration.infrastructure;

import br.gov.sus.nexus.core.integration.domain.Connector;
import br.gov.sus.nexus.core.integration.domain.DeadLetter;
import br.gov.sus.nexus.core.integration.domain.IntegrationError;
import br.gov.sus.nexus.core.integration.domain.IntegrationMessage;
import br.gov.sus.nexus.core.integration.domain.Reconciliation;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Repositórios Panache do módulo integration (RLS garante o tenant). */
public final class IntegrationRepositories {

  private IntegrationRepositories() {}

  @ApplicationScoped
  public static class Connectors implements PanacheRepositoryBase<Connector, Connector.Key> {
    public Optional<Connector> find(String tenantId, String connectorId) {
      return findByIdOptional(new Connector.Key(tenantId, connectorId));
    }

    public List<Connector> all() {
      return list("order by connectorId");
    }
  }

  @ApplicationScoped
  public static class Messages implements PanacheRepositoryBase<IntegrationMessage, String> {

    public long countSince(String connectorId, Instant since) {
      return count("connectorId = ?1 and receivedAt >= ?2", connectorId, since);
    }

    public long countFailedSince(String connectorId, Instant since) {
      return count(
          "connectorId = ?1 and receivedAt >= ?2 and status in ('failed','dead_lettered')",
          connectorId,
          since);
    }

    /** Listagem keyset por id (ULID ⇒ ordem temporal de criação no conector). */
    public List<IntegrationMessage> list(
        String connectorId,
        String status,
        Instant from,
        Instant to,
        String beforeId,
        int limitPlusOne) {
      StringBuilder q = new StringBuilder("1 = 1");
      List<Object> params = new ArrayList<>();
      if (connectorId != null) {
        params.add(connectorId);
        q.append(" and connectorId = ?").append(params.size());
      }
      if (status != null) {
        params.add(status);
        q.append(" and status = ?").append(params.size());
      }
      if (from != null) {
        params.add(from);
        q.append(" and receivedAt >= ?").append(params.size());
      }
      if (to != null) {
        params.add(to);
        q.append(" and receivedAt <= ?").append(params.size());
      }
      if (beforeId != null) {
        params.add(beforeId);
        q.append(" and id < ?").append(params.size());
      }
      q.append(" order by id desc");
      return find(q.toString(), params.toArray()).page(0, limitPlusOne).list();
    }
  }

  @ApplicationScoped
  public static class Errors implements PanacheRepositoryBase<IntegrationError, String> {}

  @ApplicationScoped
  public static class DeadLetters implements PanacheRepositoryBase<DeadLetter, String> {
    public long countOpen(String connectorId) {
      return count("connectorId = ?1 and triagedAt is null", connectorId);
    }

    public Optional<DeadLetter> findOpenByMessage(String messageId) {
      return find("messageId = ?1 and triagedAt is null", messageId).firstResultOptional();
    }

    public List<DeadLetter> list(String connectorId, String beforeId, int limitPlusOne) {
      StringBuilder q = new StringBuilder("1 = 1");
      List<Object> params = new ArrayList<>();
      if (connectorId != null) {
        params.add(connectorId);
        q.append(" and connectorId = ?").append(params.size());
      }
      if (beforeId != null) {
        params.add(beforeId);
        q.append(" and id < ?").append(params.size());
      }
      q.append(" order by id desc");
      return find(q.toString(), params.toArray()).page(0, limitPlusOne).list();
    }
  }

  @ApplicationScoped
  public static class Reconciliations implements PanacheRepositoryBase<Reconciliation, String> {
    public Optional<Reconciliation> latest(String connectorId) {
      return find("connectorId = ?1 order by checkedAt desc, id desc", connectorId)
          .firstResultOptional();
    }

    public List<Reconciliation> list(String connectorId, String beforeId, int limitPlusOne) {
      StringBuilder q = new StringBuilder("1 = 1");
      List<Object> params = new ArrayList<>();
      if (connectorId != null) {
        params.add(connectorId);
        q.append(" and connectorId = ?").append(params.size());
      }
      if (beforeId != null) {
        params.add(beforeId);
        q.append(" and id < ?").append(params.size());
      }
      q.append(" order by id desc");
      return find(q.toString(), params.toArray()).page(0, limitPlusOne).list();
    }
  }
}
