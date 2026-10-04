package br.gov.sus.nexus.core.audit;

import static br.gov.sus.nexus.core.support.Api.integration;
import static org.assertj.core.api.Assertions.assertThat;

import br.gov.sus.nexus.core.audit.application.AuditServiceImpl;
import br.gov.sus.nexus.core.support.Api;
import br.gov.sus.nexus.core.support.Api.Registration;
import br.gov.sus.nexus.core.support.Fixtures;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * O audit_log é append-only e encadeado por hash; a cadeia deve ser verificável a partir do banco.
 */
@QuarkusTest
class AuditChainTest {

  static final String TENANT = "ibge_3170206"; // tenant exclusivo deste teste
  static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void hashChainIsConsistentAndTableIsAppendOnly() throws Exception {
    String cns = Fixtures.randomProvisionalCns();
    integration(TENANT)
        .body(Registration.of("Auditada Primeira", LocalDate.of(1990, 1, 1)).cns(cns).build())
        .post("/api/v1/citizens")
        .then()
        .statusCode(201);
    integration(TENANT)
        .body(
            Registration.of("Auditada Primeira", LocalDate.of(1990, 1, 1))
                .source("HIS", "connector-his", "HIS-" + System.nanoTime())
                .cns(cns)
                .build())
        .post("/api/v1/citizens")
        .then()
        .statusCode(200);
    integration(TENANT)
        .body(Registration.of("Auditada Primeira", LocalDate.of(1991, 1, 1)).cns(cns).build())
        .post("/api/v1/citizens")
        .then()
        .statusCode(202);

    int rows = 0;
    String prev = AuditServiceImpl.GENESIS;
    try (Connection c = Api.adminConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "select id, occurred_at, actor_id, array_to_string(actor_roles, ','), action,"
                    + " resource_type, resource_id, citizen_id, reason, details::text,"
                    + " correlation_id, prev_hash, hash from audit.audit_log where tenant_id = ?"
                    + " order by seq")) {
      ps.setString(1, TENANT);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          rows++;
          OffsetDateTime occurredAt =
              rs.getObject(2, OffsetDateTime.class).withOffsetSameInstant(ZoneOffset.UTC);
          String canonical =
              AuditServiceImpl.canonicalContent(
                  rs.getString(1),
                  TENANT,
                  occurredAt,
                  rs.getString(3),
                  rs.getString(4),
                  rs.getString(5),
                  rs.getString(6),
                  rs.getString(7),
                  rs.getString(8),
                  rs.getString(9),
                  recanonicalize(rs.getString(10)),
                  rs.getString(11));
          assertThat(rs.getString(12)).as("prev_hash do registro %s", rows).isEqualTo(prev);
          String expected = AuditServiceImpl.sha256(prev + "\n" + canonical);
          assertThat(rs.getString(13)).as("hash do registro %s", rows).isEqualTo(expected);
          prev = rs.getString(13);
        }
      }
    }
    // created + linked + created(divergente) + merge_case.opened
    assertThat(rows).isGreaterThanOrEqualTo(4);

    // append-only: UPDATE/DELETE negados mesmo para o superusuário (gatilho)
    try (Connection c = Api.adminConnection();
        Statement st = c.createStatement()) {
      assertThatSqlFails(
          st, "update audit.audit_log set reason = 'x' where tenant_id = '" + TENANT + "'");
      assertThatSqlFails(st, "delete from audit.audit_log where tenant_id = '" + TENANT + "'");
      assertThatSqlFails(st, "delete from audit.access_log where tenant_id = '" + TENANT + "'");
    }
  }

  @SuppressWarnings("unchecked")
  private static String recanonicalize(String detailsJsonb) throws Exception {
    return AuditServiceImpl.canonicalJson(
        MAPPER, MAPPER.readValue(detailsJsonb, java.util.Map.class));
  }

  private static void assertThatSqlFails(Statement st, String sql) {
    try {
      st.executeUpdate(sql);
      throw new AssertionError("esperava falha para: " + sql);
    } catch (SQLException e) {
      assertThat(e.getMessage()).contains("append-only");
    }
  }
}
