package br.gov.sus.nexus.fhir.projection;

import br.gov.sus.nexus.fhir.persistence.TenantTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * Inbox idempotente do consumidor ({@code fhir.projection_inbox}, chave {@code event_id}). O
 * registro é gravado após a projeção; como a projeção é idempotente por conteúdo, um
 * reprocessamento entre a projeção e a gravação do inbox é inócuo.
 */
@ApplicationScoped
public class ProjectionInboxRepository {

  @Inject TenantTransaction tx;

  public boolean alreadyProcessed(String tenantId, String eventId) {
    return tx.execute(
        tenantId,
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement("SELECT 1 FROM fhir.projection_inbox WHERE event_id = ?")) {
            ps.setString(1, eventId);
            try (ResultSet rs = ps.executeQuery()) {
              return rs.next();
            }
          }
        });
  }

  /** Registra o evento; devolve {@code false} se outro consumidor já o registrou. */
  public boolean markProcessed(
      String tenantId,
      String eventId,
      String topic,
      String eventType,
      String targetType,
      String targetId) {
    return tx.execute(
        tenantId,
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO fhir.projection_inbox (event_id, tenant_id, topic, event_type,"
                      + " target_type, target_id) VALUES (?, ?, ?, ?, ?, ?)"
                      + " ON CONFLICT (event_id) DO NOTHING")) {
            ps.setString(1, eventId);
            ps.setString(2, tenantId);
            ps.setString(3, topic);
            ps.setString(4, eventType);
            ps.setString(5, targetType);
            ps.setString(6, targetId);
            return ps.executeUpdate() == 1;
          }
        });
  }
}
