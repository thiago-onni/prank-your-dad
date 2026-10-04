package br.gov.sus.nexus.connectors.rnds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import io.smallrye.config.common.MapBackedConfigSource;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Endereços oficiais da RNDS por ambiente/UF (Guia "Ambientes"; Manual DATASUS v1.2, cap. 5). */
class RndsEndpointsTest {

  private static RndsConfig config(Map<String, String> extra) {
    Map<String, String> props = new HashMap<>();
    props.put("rnds.endpoints.homologacao.auth-url", "https://ehr-auth-hmg.saude.gov.br/api/token");
    props.put("rnds.endpoints.homologacao.ehr-url", "https://ehr-services.hmg.saude.gov.br/api");
    props.put("rnds.endpoints.producao.auth-url", "https://ehr-auth.saude.gov.br/api/token");
    props.put("rnds.endpoints.producao.ehr-url", "https://{uf}-ehr-services.saude.gov.br/api");
    props.put("rnds.models.resultado-exame.mapping", "mappings/rnds-resultado-exame-1.1.0.yaml");
    props.putAll(extra);
    SmallRyeConfig cfg =
        new SmallRyeConfigBuilder()
            .withMapping(RndsConfig.class)
            .withSources(new MapBackedConfigSource("teste", props) {})
            .build();
    return cfg.getConfigMapping(RndsConfig.class);
  }

  @Test
  void homologacaoUnicaParaOBrasil() {
    RndsConfig c = config(Map.of());

    assertThat(RndsEndpoints.authUrl(c)).isEqualTo("https://ehr-auth-hmg.saude.gov.br/api/token");
    assertThat(RndsEndpoints.ehrUrl(c)).isEqualTo("https://ehr-services.hmg.saude.gov.br/api");
  }

  @Test
  void producaoUsaEhrDaUf() {
    RndsConfig c = config(Map.of("rnds.environment", "producao", "rnds.uf", "MG"));

    assertThat(RndsEndpoints.authUrl(c)).isEqualTo("https://ehr-auth.saude.gov.br/api/token");
    assertThat(RndsEndpoints.ehrUrl(c)).isEqualTo("https://mg-ehr-services.saude.gov.br/api");
  }

  @Test
  void producaoSemUfOuComUfInvalidaFalha() {
    assertThatThrownBy(() -> RndsEndpoints.ehrUrl(config(Map.of("rnds.environment", "producao"))))
        .hasMessageContaining("rnds.uf");
    assertThatThrownBy(
            () ->
                RndsEndpoints.ehrUrl(
                    config(Map.of("rnds.environment", "producao", "rnds.uf", "xx"))))
        .hasMessageContaining("inválida");
  }

  @Test
  void sobrescritaExplicitaTemPrecedencia() {
    RndsConfig c =
        config(
            Map.of(
                "rnds.ehr-url", "http://proxy.local/rnds/api",
                "rnds.auth-url", "http://proxy.local/auth/api/token"));

    assertThat(RndsEndpoints.ehrUrl(c)).isEqualTo("http://proxy.local/rnds/api");
    assertThat(RndsEndpoints.authUrl(c)).isEqualTo("http://proxy.local/auth/api/token");
  }
}
