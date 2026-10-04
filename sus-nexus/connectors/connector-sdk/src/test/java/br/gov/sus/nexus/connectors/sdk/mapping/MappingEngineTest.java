package br.gov.sus.nexus.connectors.sdk.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MappingEngineTest {

  private final MappingVersion mapping =
      MappingLoader.fromClasspath("mappings/test-citizen-1.0.0.yaml");

  @Test
  void carregaMetadadosDoYaml() {
    assertThat(mapping.mappingSet()).isEqualTo("test-citizen");
    assertThat(mapping.version()).isEqualTo("1.0.0");
    assertThat(mapping.entityType()).isEqualTo("citizen");
    assertThat(mapping.fields()).hasSize(11);
    assertThat(mapping.lookup("sexo")).containsEntry("F", "female");
  }

  @Test
  @SuppressWarnings("unchecked")
  void aplicaTransformacoesEEstruturaAninhada() {
    Map<String, Object> source = new HashMap<>();
    source.put("id", "42");
    source.put("nome", "  José da Silva  ");
    source.put("nome_mae", "maria");
    source.put("dt_nasc", "05031980");
    source.put("sexo", "M");
    source.put("cpf", "123.456.789-09");
    source.put("ativo", "S");
    source.put("dt_cadastro", "20240101");

    Map<String, Object> out = MappingEngine.apply(mapping, source);

    Map<String, Object> demographics = (Map<String, Object>) out.get("demographics");
    assertThat(demographics)
        .containsEntry("legal_name", "JOSE DA SILVA")
        .containsEntry("mother_name", "MARIA")
        .containsEntry("birthdate", "1980-03-05")
        .containsEntry("sex", "male")
        .containsEntry("deceased", false);
    Map<String, Object> src = (Map<String, Object>) out.get("source");
    assertThat(src).containsEntry("system", "TESTE").containsEntry("source_record_id", "42");
    List<Map<String, Object>> ids = (List<Map<String, Object>>) out.get("identifiers");
    assertThat(ids).hasSize(1);
    assertThat(ids.get(0)).containsEntry("system", "CPF").containsEntry("value", "12345678909");
    Map<String, Object> territory = (Map<String, Object>) out.get("territory");
    assertThat(territory).containsEntry("microarea", "20");
  }

  @Test
  void lookupComDefaultEValorAusente() {
    Map<String, Object> out =
        MappingEngine.apply(
            mapping, Map.of("id", "1", "nome", "A", "dt_nasc", "01012000", "sexo", "X"));
    @SuppressWarnings("unchecked")
    Map<String, Object> demographics = (Map<String, Object>) out.get("demographics");
    assertThat(demographics).containsEntry("sex", "unknown");
    assertThat(out).doesNotContainKey("identifiers");
  }

  @Test
  void campoObrigatorioVazioFalha() {
    assertThatThrownBy(
            () ->
                MappingEngine.apply(mapping, Map.of("id", "1", "nome", " ", "dt_nasc", "01012000")))
        .isInstanceOf(MappingException.class)
        .hasMessageContaining("demographics.legal_name");
  }

  @Test
  void dataInvalidaFalha() {
    assertThatThrownBy(
            () ->
                MappingEngine.apply(mapping, Map.of("id", "1", "nome", "A", "dt_nasc", "99999999")))
        .isInstanceOf(MappingException.class)
        .hasMessageContaining("data inválida");
  }

  @Test
  void mappingSetSelecionaVersao() {
    MappingSet set = new MappingSet("test-citizen", List.of(mapping));
    assertThat(set.require("1.0.0")).isSameAs(mapping);
    assertThatThrownBy(() -> set.require("9.9.9")).hasMessageContaining("9.9.9");
  }

  @Test
  void transformacaoIsoDatetime() {
    Transformation.DateFormat t =
        new Transformation.DateFormat("yyyy-MM-dd HH:mm:ss", "iso-datetime", null);
    assertThat(t.apply("2026-10-03 14:00:00", table -> Map.of()))
        .isEqualTo("2026-10-03T14:00:00-03:00");
  }
}
