package br.gov.sus.nexus.connectors.sdk.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.gov.sus.nexus.connectors.sdk.parse.JsonFlattener;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Transformações adicionadas na fase 2 (regulação/laboratório) e o achatamento de JSON. */
class Phase2TransformationsTest {

  private static final String YAML =
      """
      mapping_set: t
      version: "1.0.0"
      entity_type: x
      fields:
        - { source: d1, target: d1, transforms: [{ date: { from: "dd/MM/yyyy HH:mm:ss|dd/MM/yyyy", to: iso-datetime } }] }
        - { source: d2, target: d2, transforms: [{ date: { from: "dd/MM/yyyy HH:mm:ss|dd/MM/yyyy", to: iso-datetime } }] }
        - { source: d3, target: d3, transforms: [{ date: { from: "dd/MM/yyyy", to: iso-datetime } }] }
        - { source: comp, target: comp, transforms: [{ year_month: { from: "MM/yyyy|yyyyMM" } }] }
        - { source: comp2, target: comp2, transforms: [{ year_month: { from: "MM/yyyy|yyyyMM" } }] }
        - { source: vagas, target: vagas, transforms: [{ regex: { pattern: "[^0-9]", replacement: "" } }, to_integer] }
        - { source: valor, target: valor, transforms: [to_decimal] }
        - { source: "paciente.cns", target: cns, transforms: [digits] }
        - { source: "anexos[1].id", target: anexo, transforms: [trim] }
        - { source: "anexos.length", target: n, transforms: [to_integer] }
      """;

  @Test
  void dataTolerante_competencia_regex_decimal() {
    MappingVersion m = MappingLoader.fromYaml(YAML);
    Map<String, Object> out =
        MappingEngine.apply(
            m,
            Map.of(
                "d1", "05/01/2026 09:15:00",
                "d2", "05/01/2026",
                "d3", "2026-01-05T09:15:00-03:00",
                "comp", "01/2026",
                "comp2", "202602",
                "vagas", "1.200 vagas",
                "valor", "38,5"));
    assertThat(out)
        .containsEntry("d1", "2026-01-05T09:15:00-03:00")
        .containsEntry("d2", "2026-01-05T00:00:00-03:00")
        .containsEntry("d3", "2026-01-05T09:15:00-03:00")
        .containsEntry("comp", "202601")
        .containsEntry("comp2", "202602")
        .containsEntry("vagas", 1200)
        .containsEntry("valor", new BigDecimal("38.5"));
    assertThatThrownBy(() -> MappingEngine.apply(m, Map.of("d1", "ontem")))
        .isInstanceOf(MappingException.class);
  }

  @Test
  void achataJsonNaNotacaoDoMappingEngine() throws Exception {
    Map<String, String> flat =
        JsonFlattener.flatten(
            new ObjectMapper()
                .readTree(
                    "{\"paciente\":{\"cns\":\"898001234567891\",\"nome\":null},\"anexos\":[{\"id\":\"a\"},{\"id\":\"b\"}],\"n\":2.0,\"ok\":true}"));
    assertThat(flat)
        .containsEntry("paciente.cns", "898001234567891")
        .containsEntry("anexos[0].id", "a")
        .containsEntry("anexos[1].id", "b")
        .containsEntry("anexos.length", "2")
        .containsEntry("n", "2")
        .containsEntry("ok", "true")
        .doesNotContainKey("paciente.nome");
    Map<String, Object> out = MappingEngine.apply(MappingLoader.fromYaml(YAML), flat);
    assertThat(out)
        .containsEntry("cns", "898001234567891")
        .containsEntry("anexo", "b")
        .containsEntry("n", 2);
  }
}
