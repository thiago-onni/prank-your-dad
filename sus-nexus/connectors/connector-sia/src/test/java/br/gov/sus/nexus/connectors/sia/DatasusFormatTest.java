package br.gov.sus.nexus.connectors.sia;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Leitura dos formatos de disseminação do DATASUS (.dbf/.dbc) e regras derivadas (unitário). */
class DatasusFormatTest {

  private static final List<DatasusFiles.Col> COLS =
      List.of(
          new DatasusFiles.Col("N_AIH", 13),
          new DatasusFiles.Col("ST_SITUAC", 1),
          new DatasusFiles.Col("DS", 20));

  private static byte[] sample() {
    return DatasusFiles.dbf(
        COLS,
        List.of(
            List.of("3126200098765", "1", "PERMANÊNCIA"),
            List.of("3126200098766", "0", "EXCLUIDO"),
            List.of("3126200098767", "1", "")),
        List.of(1));
  }

  @Test
  void leDbfComDescritoresRegistrosExcluidosELatin1() {
    DbfReader.Table t = DbfReader.read(sample(), StandardCharsets.ISO_8859_1);
    assertThat(t.fields())
        .extracting(DbfReader.Field::name, DbfReader.Field::type, DbfReader.Field::length)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("N_AIH", 'C', 13),
            org.assertj.core.groups.Tuple.tuple("ST_SITUAC", 'C', 1),
            org.assertj.core.groups.Tuple.tuple("DS", 'C', 20));
    assertThat(t.rows()).hasSize(2);
    assertThat(t.rows().get(0))
        .containsEntry("N_AIH", "3126200098765")
        .containsEntry("DS", "PERMANÊNCIA");
    assertThat(t.rows().get(1)).containsEntry("N_AIH", "3126200098767").containsEntry("DS", "");
  }

  @Test
  void dbcDescomprimeParaOMesmoDbf() {
    byte[] dbf = sample();
    byte[] dbc = DatasusFiles.dbc(dbf);
    assertThat(DbcDecompressor.looksLikeDbc(dbc)).isTrue();
    assertThat(DbcDecompressor.looksLikeDbc(dbf)).isFalse();
    assertThat(DbcDecompressor.toDbf(dbc)).isEqualTo(dbf);
    assertThat(DbfReader.read(dbc, StandardCharsets.ISO_8859_1).rows())
        .isEqualTo(DbfReader.read(dbf, StandardCharsets.ISO_8859_1).rows());
  }

  @Test
  void rejeitaDbcEDbfInvalidos() {
    byte[] dbc = DatasusFiles.dbc(sample());
    int headerLen = (dbc[8] & 0xff) | (dbc[9] & 0xff) << 8;
    byte[] badDict = dbc.clone();
    badDict[headerLen + 5] = 3; // tamanho de dicionário fora de 4..6
    assertThatThrownBy(() -> DbcDecompressor.toDbf(badDict))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("dicionário");
    byte[] truncated = java.util.Arrays.copyOf(dbc, headerLen + 10);
    assertThatThrownBy(() -> DbcDecompressor.toDbf(truncated))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("truncado");
    byte[] dbf = sample();
    byte[] shortDbf = java.util.Arrays.copyOf(dbf, dbf.length - 30);
    assertThatThrownBy(() -> DbfReader.read(shortDbf, StandardCharsets.ISO_8859_1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("truncado");
  }

  @Test
  void situacaoQualificadaEMesDeProcessamento() {
    Map<String, String> c = new HashMap<>();
    c.put("situacao_prefixo", "SIA-PA");
    c.put("situacao", "6");
    c.put("mes_processamento", "202609");
    SiaRules.applySituationPrefix(c);
    SiaRules.applyProcessingMonth(c);
    assertThat(c)
        .containsEntry("situacao", "SIA-PA:6")
        .containsEntry("data_processamento", "20260901");

    Map<String, String> rd = new HashMap<>();
    rd.put("situacao_prefixo", "SIH-RD");
    rd.put("ano_processamento", "2026");
    rd.put("mes_processamento", "9");
    SiaRules.applySituationPrefix(rd);
    SiaRules.applyProcessingMonth(rd);
    assertThat(rd)
        .containsEntry("situacao", "SIH-RD")
        .containsEntry("data_processamento", "20260901");

    Map<String, String> explicit = new HashMap<>();
    explicit.put("data_processamento", "30/09/2026");
    explicit.put("mes_processamento", "202608");
    SiaRules.applyProcessingMonth(explicit);
    assertThat(explicit).containsEntry("data_processamento", "30/09/2026");
  }
}
