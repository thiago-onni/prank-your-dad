package br.gov.sus.nexus.connectors.template;

import static org.assertj.core.api.Assertions.assertThat;

import br.gov.sus.nexus.connectors.sdk.api.CanonicalBatch;
import br.gov.sus.nexus.connectors.sdk.api.RawMessage;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Teste de contrato (golden) entrada → saída canônica, sem subir o Quarkus. */
class ExampleConnectorTest {

  private final ExampleConnector connector = new ExampleConnector();

  @Test
  void descriptorValido() {
    assertThat(connector.descriptor().missingFields()).isEmpty();
  }

  @Test
  @SuppressWarnings("unchecked")
  void transformaEValida() {
    RawMessage raw =
        RawMessage.ofText(
            "lote.csv",
            "citizen",
            "id;nome;nascimento;sexo;cpf\n1;Maria Souza;01/02/1990;f;123.456.789-09\n",
            Map.of());
    CanonicalBatch batch = connector.transform(raw);
    Map<String, Object> d =
        (Map<String, Object>) batch.records().get(0).payload().get("demographics");
    assertThat(d)
        .containsEntry("legal_name", "MARIA SOUZA")
        .containsEntry("birthdate", "1990-02-01")
        .containsEntry("sex", "female");
    assertThat(connector.validate(batch).isValid()).isTrue();
  }
}
