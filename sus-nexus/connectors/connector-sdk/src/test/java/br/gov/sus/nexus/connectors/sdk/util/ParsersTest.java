package br.gov.sus.nexus.connectors.sdk.util;

import static org.assertj.core.api.Assertions.assertThat;

import br.gov.sus.nexus.connectors.sdk.parse.DelimitedParser;
import br.gov.sus.nexus.connectors.sdk.parse.FixedWidthParser;
import br.gov.sus.nexus.connectors.sdk.parse.LayoutRegistry;
import br.gov.sus.nexus.connectors.sdk.parse.TableLayout;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ParsersTest {

  @Test
  void delimitadoComAspasECabecalho() {
    List<Map<String, String>> rows =
        DelimitedParser.semicolonWithHeader()
            .parse(
                "\uFEFFcodigo;descricao\nA00;\"C\u00f3lera; \"\"cl\u00e1ssica\"\"\"\n\nA01;Febre\n");
    assertThat(rows).hasSize(2);
    assertThat(rows.get(0))
        .containsEntry("codigo", "A00")
        .containsEntry("descricao", "Cólera; \"clássica\"");
  }

  @Test
  void larguraFixaComLinhaCurta() {
    FixedWidthParser p =
        new FixedWidthParser(
            List.of(
                new FixedWidthParser.Column("CO", 1, 3),
                new FixedWidthParser.Column("NO", 4, 10),
                new FixedWidthParser.Column("X", 11, 12)));
    assertThat(p.parseLine("001Nome"))
        .containsEntry("CO", "001")
        .containsEntry("NO", "Nome")
        .containsEntry("X", "");
  }

  @Test
  void registroDeLayoutsSelecionaPorArquivo() {
    LayoutRegistry reg = LayoutRegistry.load("classpath:layouts/test-layouts.yaml");
    assertThat(reg.version()).isEqualTo("2.0.0");
    TableLayout fixed = reg.forFile("tb_teste202601.txt").orElseThrow();
    assertThat(fixed.format()).isEqualTo(TableLayout.Format.FIXED_WIDTH);
    List<Map<String, String>> rows =
        fixed.parse("0101010010CONSULTA  202601".getBytes(StandardCharsets.ISO_8859_1));
    assertThat(fixed.value(rows.get(0), "code")).isEqualTo("0101010010");
    assertThat(fixed.value(rows.get(0), "display")).isEqualTo("CONSULTA");
    assertThat(fixed.value(rows.get(0), "competence")).isEqualTo("202601");
    TableLayout csv = reg.forFile("cid10.csv").orElseThrow();
    assertThat(csv.parse("codigo;descricao\nA00;Cólera".getBytes(StandardCharsets.UTF_8)).get(0))
        .containsEntry("codigo", "A00");
    assertThat(reg.forFile("nada.bin")).isEmpty();
  }
}
