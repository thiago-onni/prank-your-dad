package br.gov.sus.nexus.core.platform;

import static br.gov.sus.nexus.core.support.Api.TENANT_A;
import static br.gov.sus.nexus.core.support.Api.aps;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

import br.gov.sus.nexus.core.platform.idempotency.IdempotencyStore;
import br.gov.sus.nexus.core.support.Api;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Idempotency-Key: replay da resposta, 422 em reuso com corpo diferente e expurgo de 72 h. */
@QuarkusTest
class IdempotencyFilterTest {

  @Inject IdempotencyStore store;

  @Test
  void sameKeyAndBodyReplaysStoredResponse() {
    String key = "idem-" + System.nanoTime();
    Map<String, Object> body =
        Map.of("task_type", "generic", "priority", "low", "title", "Tarefa idempotente");
    Response first = aps(TENANT_A).header("Idempotency-Key", key).body(body).post("/api/v1/tasks");
    first.then().statusCode(201);
    String id = first.path("id");

    Response replay = aps(TENANT_A).header("Idempotency-Key", key).body(body).post("/api/v1/tasks");
    replay
        .then()
        .statusCode(201)
        .header("Idempotent-Replayed", equalTo("true"))
        .body("id", equalTo(id));

    aps(TENANT_A)
        .header("Idempotency-Key", key)
        .body(Map.of("task_type", "generic", "priority", "high", "title", "Outra"))
        .post("/api/v1/tasks")
        .then()
        .statusCode(422)
        .body("type", equalTo("urn:sus-nexus:problem:idempotency-key-reused"));

    // outro tenant com a mesma chave não enxerga a resposta armazenada (RLS)
    aps(Api.TENANT_B)
        .header("Idempotency-Key", key)
        .body(body)
        .post("/api/v1/tasks")
        .then()
        .statusCode(201);
  }

  @Test
  void invalidKeyIsRejected() {
    aps(TENANT_A)
        .header("Idempotency-Key", "chave com espaços")
        .body(Map.of("task_type", "generic", "priority", "low", "title", "x"))
        .post("/api/v1/tasks")
        .then()
        .statusCode(400);
  }

  @Test
  void purgeRemovesKeysOlderThanTtl() throws Exception {
    String key = "idem-old-" + System.nanoTime();
    aps(TENANT_A)
        .header("Idempotency-Key", key)
        .body(Map.of("task_type", "generic", "priority", "low", "title", "Antiga"))
        .post("/api/v1/tasks")
        .then()
        .statusCode(201);
    try (Connection c = Api.adminConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "update platform.idempotency_key set created_at = now() - interval '73 hours'"
                    + " where idempotency_key = ?")) {
      ps.setString(1, key);
      assertThat(ps.executeUpdate()).isEqualTo(1);
    }
    assertThat(store.purge()).isGreaterThanOrEqualTo(1);
    // chave expirada: nova requisição é processada de novo (não é replay)
    aps(TENANT_A)
        .header("Idempotency-Key", key)
        .body(Map.of("task_type", "generic", "priority", "low", "title", "Antiga"))
        .post("/api/v1/tasks")
        .then()
        .statusCode(201)
        .header("Idempotent-Replayed", nullValue());
  }
}
