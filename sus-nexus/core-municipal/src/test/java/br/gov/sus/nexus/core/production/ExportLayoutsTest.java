package br.gov.sus.nexus.core.production;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.gov.sus.nexus.core.production.application.ExportLayouts;
import br.gov.sus.nexus.core.production.application.ExportLayouts.Field;
import br.gov.sus.nexus.core.production.application.ExportLayouts.Line;
import br.gov.sus.nexus.core.production.application.ExportLayouts.Patient;
import br.gov.sus.nexus.core.production.application.ExportLayouts.RecordLayout;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Layouts oficiais de exportação conferidos campo a campo contra as tabelas de posição/tamanho da
 * especificação (transcritas abaixo de forma independente da implementação — fonte em
 * docs/integracoes/layouts-sia-sih.md) e o campo de controle. Fixtures sintéticas (CNS/CPF/nomes
 * fictícios).
 */
class ExportLayoutsTest {

  /** Campo esperado: nome, início, fim (1-based, inclusivo). */
  private record Spec(String name, int start, int end) {
    int size() {
      return end - start + 1;
    }
  }

  private static Spec s(String name, int start, int end) {
    return new Spec(name, start, end);
  }

  // "Layout de Exportação BPA" (DATASUS/SIA, 12/2024) — cabeçalho, BPA-C (02), BPA-I (03)
  private static final List<Spec> BPA_HEADER =
      List.of(
          s("cbc_hdr", 1, 2),
          s("cbc_hdr_bpa", 3, 7),
          s("cbc_mvm", 8, 13),
          s("cbc_lin", 14, 19),
          s("cbc_flh", 20, 25),
          s("cbc_smt_vrf", 26, 29),
          s("cbc_rsp", 30, 59),
          s("cbc_sgl", 60, 65),
          s("cbc_cgccpf", 66, 79),
          s("cbc_dst", 80, 119),
          s("cbc_dst_in", 120, 120),
          s("cbc_versao", 121, 130));

  private static final List<Spec> BPA_C =
      List.of(
          s("prd_ident", 1, 2),
          s("prd_cnes", 3, 9),
          s("prd_cmp", 10, 15),
          s("prd_cbo", 16, 21),
          s("prd_flh", 22, 24),
          s("prd_seq", 25, 26),
          s("prd_pa", 27, 36),
          s("prd_idade", 37, 39),
          s("prd_qt", 40, 45),
          s("prd_org", 46, 48));

  private static final List<Spec> BPA_I =
      List.of(
          s("prd_ident", 1, 2),
          s("prd_cnes", 3, 9),
          s("prd_cmp", 10, 15),
          s("prd_cnsmed", 16, 30),
          s("prd_cbo", 31, 36),
          s("prd_dtaten", 37, 44),
          s("prd_flh", 45, 47),
          s("prd_seq", 48, 49),
          s("prd_pa", 50, 59),
          s("prd_cnspac", 60, 74),
          s("prd_sexo", 75, 75),
          s("prd_ibge", 76, 81),
          s("prd_cid", 82, 85),
          s("prd_idade", 86, 88),
          s("prd_qt", 89, 94),
          s("prd_caten", 95, 96),
          s("prd_naut", 97, 109),
          s("prd_org", 110, 112),
          s("prd_nmpac", 113, 142),
          s("prd_dtnasc", 143, 150),
          s("prd_raca", 151, 152),
          s("prd_etnia", 153, 156),
          s("prd_nac", 157, 159),
          s("prd_srv", 160, 162),
          s("prd_clf", 163, 165),
          s("prd_equipe_seq", 166, 173),
          s("prd_equipe_area", 174, 177),
          s("prd_cnpj", 178, 191),
          s("prd_cep_pcnte", 192, 199),
          s("prd_lograd_pcnte", 200, 202),
          s("prd_end_pcnte", 203, 232),
          s("prd_compl_pcnte", 233, 242),
          s("prd_num_pcnte", 243, 247),
          s("prd_bairro_pcnte", 248, 277),
          s("prd_ddtel_pcnte", 278, 288),
          s("prd_email_pcnte", 289, 328),
          s("prd_ine", 329, 338),
          s("prd_cpf_pcnte", 339, 349),
          s("prd_situacao_rua", 350, 350));

  // Layout de interface texto APAC/SIA (07/2026) — cabeçalho (01), corpo (14), procedimentos (13)
  private static final List<Spec> APAC_HEADER =
      List.of(
          s("cbc_hdr", 1, 2),
          s("cbc_apac", 3, 7),
          s("cbc_cmp", 8, 13),
          s("cbc_lin", 14, 19),
          s("cbc_smt_vrf", 20, 23),
          s("cbc_rsp", 24, 53),
          s("cbc_sgl", 54, 59),
          s("cbc_cgccpf", 60, 73),
          s("cbc_dst", 74, 113),
          s("cbc_dst_in", 114, 114),
          s("cbc_dtger", 115, 122),
          s("cbc_versao", 123, 137));

  private static final List<Spec> APAC_BODY =
      List.of(
          s("apa_corpo", 1, 2),
          s("apa_cmp", 3, 8),
          s("apa_num", 9, 21),
          s("apa_coduf", 22, 23),
          s("apa_codcnes", 24, 30),
          s("apa_pr", 31, 38),
          s("apa_dtiinval", 39, 46),
          s("apa_dtfimval", 47, 54),
          s("apa_tipate", 55, 56),
          s("apa_tipapac", 57, 57),
          s("apa_nomepcnte", 58, 87),
          s("apa_nomemae", 88, 117),
          s("apa_logpcnte", 118, 147),
          s("apa_numpcnte", 148, 152),
          s("apa_cplpcnte", 153, 162),
          s("apa_ceppcnte", 163, 170),
          s("apa_munpcnte", 171, 177),
          s("apa_datanascim", 178, 185),
          s("apa_sexopcnte", 186, 186),
          s("apa_nomeresp_med", 187, 216),
          s("apa_codprinc", 217, 226),
          s("apa_motsaida", 227, 228),
          s("apa_dtobitoalta", 229, 236),
          s("apa_nomediretor", 237, 266),
          s("apa_cnspct", 267, 281),
          s("apa_cnsres", 282, 296),
          s("apa_cnsdir", 297, 311),
          s("apa_cidca", 312, 315),
          s("apa_npront", 316, 325),
          s("apa_codsol", 326, 332),
          s("apa_datsol", 333, 340),
          s("apa_dataut", 341, 348),
          s("apa_codemis", 349, 358),
          s("apa_carate", 359, 360),
          s("apa_apacant", 361, 373),
          s("apa_raca", 374, 375),
          s("apa_nomeresp_pac", 376, 405),
          s("apa_nascpcnte", 406, 408),
          s("apa_etnia", 409, 412),
          s("apa_cdlogr", 413, 415),
          s("apa_bairro", 416, 445),
          s("apa_dddtelcontato", 446, 447),
          s("apa_telcontato", 448, 456),
          s("apa_email", 457, 496),
          s("apa_cnsexec", 497, 511),
          s("apa_cpfpcnte", 512, 522),
          s("apa_ine", 523, 532),
          s("apa_strua", 533, 533),
          s("apa_fntorca", 534, 535),
          s("apa_emenpar", 536, 536),
          s("apa_semcpf", 537, 537));

  private static final List<Spec> APAC_PROCEDURE =
      List.of(
          s("pap_corpo", 1, 2),
          s("pap_cmp", 3, 8),
          s("pap_num", 9, 21),
          s("pap_codproc", 22, 31),
          s("pap_cbo", 32, 37),
          s("pap_qtdprod", 38, 44),
          s("pap_cgc", 45, 58),
          s("pap_nf", 59, 64),
          s("pap_cidp", 65, 68),
          s("pap_cids", 69, 72),
          s("pap_srv", 73, 75),
          s("pap_clf", 76, 78),
          s("pap_equipe_seq", 79, 86),
          s("pap_equipe_area", 87, 90),
          s("pap_cnes_terc", 91, 97));

  private static final String PROF_CNS = "700000000000001";
  private static final String PROF_CNS_2 = "700000000000002";
  private static final String CIT_CNS = "898000000000001";
  private static final String CIT_CPF = "00000000191";

  private static final ExportLayouts.Header HEADER =
      new ExportLayouts.Header(
          "Secretaria Municipal de Saúde",
          "SMS",
          "12345678000199",
          "Secretaria Municipal de Saúde de Teste",
          "M",
          "31",
          LocalDate.of(2026, 10, 4));

  private static final Patient MARIA =
      new Patient(
          "Maria da Conceição Teste",
          "Ana Teste",
          LocalDate.of(1980, 5, 17),
          "39400000",
          "Rua das Flores",
          "123",
          "Apto 2",
          "Centro");

  // ------------------------------------------------------------------------------------------

  @Test
  void tabelasDeCamposConferemComAEspecificacao() {
    assertLayout(ExportLayouts.BPA_HEADER, BPA_HEADER, 130);
    assertLayout(ExportLayouts.BPA_C, BPA_C, 48);
    assertLayout(ExportLayouts.BPA_I, BPA_I, 350);
    assertLayout(ExportLayouts.APAC_HEADER, APAC_HEADER, 137);
    assertLayout(ExportLayouts.APAC_BODY, APAC_BODY, 537);
    assertLayout(ExportLayouts.APAC_PROCEDURE, APAC_PROCEDURE, 97);
  }

  @Test
  void bpaMagneticoCamposPosicoesEControle() {
    Line bpaI1 =
        new Line(
            "prod_1",
            "bpa_i",
            "1234567",
            "202610",
            PROF_CNS,
            "225142",
            LocalDate.of(2026, 10, 1),
            "0301010064",
            CIT_CNS,
            null,
            "F",
            "3143302",
            "I10",
            46,
            1,
            "elective",
            null,
            MARIA);
    Line bpaI2 = // paciente identificado por CPF: CNS em branco
        new Line(
            "prod_2",
            "bpa_i",
            "1234567",
            "202610",
            PROF_CNS_2,
            "223505",
            LocalDate.of(2026, 10, 3),
            "0301100039",
            null,
            CIT_CPF,
            "M",
            "3143302",
            null,
            7,
            2,
            "other",
            "3126200000001",
            null);
    Line bpaC =
        new Line(
            "prod_3",
            "bpa_c",
            "1234567",
            "202610",
            null,
            "515105",
            LocalDate.of(2026, 10, 2),
            "0101010010",
            null,
            null,
            null,
            null,
            null,
            null,
            25,
            null,
            null,
            null);
    ExportLayouts.Rendered r = ExportLayouts.bpaMag("202610", List.of(bpaI1, bpaI2, bpaC), HEADER);
    assertThat(r.extension()).isEqualTo("txt");
    assertThat(r.lines()).isEqualTo(3);
    assertThat(r.missingIdentifiers()).isZero();
    String text = new String(r.content(), StandardCharsets.US_ASCII);
    assertThat(text).endsWith("\r\n").doesNotContain("\n\n");
    String[] lines = text.split("\r\n");
    assertThat(lines).hasSize(4);

    // controle = (Σ procedimento + quantidade) mod 1111 + 1111
    long sum = 301010064L + 1 + 301100039L + 2 + 101010010L + 25;
    int control = (int) (sum % 1111) + 1111;
    assertThat(ExportLayouts.controlField(sum)).isEqualTo(control).isBetween(1111, 2221);

    Map<String, String> h = slice(lines[0], BPA_HEADER);
    assertThat(h)
        .containsEntry("cbc_hdr", "01")
        .containsEntry("cbc_hdr_bpa", "#BPA#")
        .containsEntry("cbc_mvm", "202610")
        .containsEntry("cbc_lin", "000003")
        .containsEntry("cbc_flh", "000003") // 1 folha BPA-C + 2 folhas BPA-I (2 profissionais)
        .containsEntry("cbc_smt_vrf", String.valueOf(control))
        .containsEntry("cbc_rsp", pad("SECRETARIA MUNICIPAL DE SAUDE", 30))
        .containsEntry("cbc_sgl", pad("SMS", 6))
        .containsEntry("cbc_cgccpf", "12345678000199")
        .containsEntry("cbc_dst", pad("SECRETARIA MUNICIPAL DE SAUDE DE TESTE", 40))
        .containsEntry("cbc_dst_in", "M")
        .containsEntry("cbc_versao", "SUSNEXUS01");

    Map<String, String> c = slice(lines[1], BPA_C);
    assertThat(c)
        .containsEntry("prd_ident", "02")
        .containsEntry("prd_cnes", "1234567")
        .containsEntry("prd_cmp", "202610")
        .containsEntry("prd_cbo", "515105")
        .containsEntry("prd_flh", "001")
        .containsEntry("prd_seq", "01")
        .containsEntry("prd_pa", "0101010010")
        .containsEntry("prd_idade", "000")
        .containsEntry("prd_qt", "000025")
        .containsEntry("prd_org", "BPA");

    Map<String, String> i1 = slice(lines[2], BPA_I);
    assertThat(i1)
        .containsEntry("prd_ident", "03")
        .containsEntry("prd_cnes", "1234567")
        .containsEntry("prd_cmp", "202610")
        .containsEntry("prd_cnsmed", PROF_CNS)
        .containsEntry("prd_cbo", "225142")
        .containsEntry("prd_dtaten", "20261001")
        .containsEntry("prd_flh", "001")
        .containsEntry("prd_seq", "01")
        .containsEntry("prd_pa", "0301010064")
        .containsEntry("prd_cnspac", CIT_CNS)
        .containsEntry("prd_sexo", "F")
        .containsEntry("prd_ibge", "314330")
        .containsEntry("prd_cid", "I10 ")
        .containsEntry("prd_idade", "046")
        .containsEntry("prd_qt", "000001")
        .containsEntry("prd_caten", "01")
        .containsEntry("prd_naut", blanks(13))
        .containsEntry("prd_org", "BPA")
        .containsEntry("prd_nmpac", pad("MARIA DA CONCEICAO TESTE", 30))
        .containsEntry("prd_dtnasc", "19800517")
        .containsEntry("prd_raca", "99")
        .containsEntry("prd_etnia", blanks(4))
        .containsEntry("prd_nac", blanks(3))
        .containsEntry("prd_cep_pcnte", "39400000")
        .containsEntry("prd_end_pcnte", pad("RUA DAS FLORES", 30))
        .containsEntry("prd_compl_pcnte", pad("APTO 2", 10))
        .containsEntry("prd_num_pcnte", pad("123", 5))
        .containsEntry("prd_bairro_pcnte", pad("CENTRO", 30))
        .containsEntry("prd_cpf_pcnte", blanks(11)) // CNS informado → CPF em branco
        .containsEntry("prd_situacao_rua", " ");

    Map<String, String> i2 = slice(lines[3], BPA_I);
    assertThat(i2)
        .containsEntry("prd_cnsmed", PROF_CNS_2)
        .containsEntry("prd_flh", "002") // outro profissional → nova folha
        .containsEntry("prd_seq", "01")
        .containsEntry("prd_cnspac", blanks(15)) // CPF informado → CNS em branco
        .containsEntry("prd_cpf_pcnte", CIT_CPF)
        .containsEntry("prd_caten", blanks(2)) // "other" não determina código 04–06
        .containsEntry("prd_naut", "3126200000001")
        .containsEntry("prd_cid", blanks(4))
        .containsEntry("prd_qt", "000002");
  }

  @Test
  void bpaMagneticoFolhasDe20LinhasEIdentificadoresAusentes() {
    List<Line> lines = new ArrayList<>();
    for (int i = 0; i < 21; i++) {
      lines.add(
          new Line(
              "prod_" + i,
              "bpa_i",
              "1234567",
              "202610",
              PROF_CNS,
              "225142",
              LocalDate.of(2026, 10, 1),
              "0301010064",
              i == 20 ? null : CIT_CNS,
              null,
              "F",
              null,
              null,
              30,
              1,
              "urgency",
              null,
              null));
    }
    ExportLayouts.Rendered r = ExportLayouts.bpaMag("202610", lines, HEADER);
    String[] out = new String(r.content(), StandardCharsets.US_ASCII).split("\r\n");
    assertThat(slice(out[0], BPA_HEADER)).containsEntry("cbc_flh", "000002");
    assertThat(slice(out[20], BPA_I))
        .containsEntry("prd_flh", "001")
        .containsEntry("prd_seq", "20");
    assertThat(slice(out[21], BPA_I))
        .containsEntry("prd_flh", "002")
        .containsEntry("prd_seq", "01");
    assertThat(r.missingIdentifiers()).isEqualTo(1);
  }

  @Test
  void apacCabecalhoCorpoProcedimentosEControle() {
    Line principal =
        new Line(
            "prod_a1",
            "apac",
            "7654321",
            "202610",
            PROF_CNS,
            "225270",
            LocalDate.of(2026, 10, 5),
            "0304020010",
            CIT_CNS,
            null,
            "F",
            "3143302",
            "C50.9",
            46,
            1,
            "elective",
            "3126200000001",
            MARIA);
    Line secundario =
        new Line(
            "prod_a2",
            "apac",
            "7654321",
            "202610",
            PROF_CNS,
            "225270",
            LocalDate.of(2026, 10, 5),
            "0304100013",
            CIT_CNS,
            null,
            "F",
            "3143302",
            "C50.9",
            46,
            3,
            "elective",
            "3126200000001",
            MARIA);
    Line outraApac =
        new Line(
            "prod_b1",
            "apac",
            "7654321",
            "202610",
            PROF_CNS_2,
            "225270",
            LocalDate.of(2026, 10, 6),
            "0304020010",
            null,
            CIT_CPF,
            "M",
            "3143302",
            "C61",
            70,
            1,
            "urgency",
            "3126200000002",
            null);
    ExportLayouts.Rendered r =
        ExportLayouts.apacMag("202610", List.of(principal, secundario, outraApac), HEADER);
    assertThat(r.lines()).isEqualTo(3);
    assertThat(r.missingIdentifiers()).isZero();
    String[] lines = new String(r.content(), StandardCharsets.US_ASCII).split("\r\n");
    // 01, 14 + 13 + 13, 14 + 13
    assertThat(lines).hasSize(6);
    assertThat(lines)
        .extracting(l -> l.substring(0, 2))
        .containsExactly("01", "14", "13", "13", "14", "13");

    // controle = (Σ nº das APAC + Σ procedimento + quantidade dos registros 13) mod 1111 + 1111
    long sum =
        3126200000001L + 3126200000002L + (304020010L + 1) + (304100013L + 3) + (304020010L + 1);
    Map<String, String> h = slice(lines[0], APAC_HEADER);
    assertThat(h)
        .containsEntry("cbc_hdr", "01")
        .containsEntry("cbc_apac", "#APAC")
        .containsEntry("cbc_cmp", "202610")
        .containsEntry("cbc_lin", "000002") // quantidade de APAC (registros 14)
        .containsEntry("cbc_smt_vrf", String.valueOf(sum % 1111 + 1111))
        .containsEntry("cbc_rsp", pad("SECRETARIA MUNICIPAL DE SAUDE", 30))
        .containsEntry("cbc_cgccpf", "12345678000199")
        .containsEntry("cbc_dst_in", "M")
        .containsEntry("cbc_dtger", "20261004")
        .containsEntry("cbc_versao", pad("SUSNEXUS01", 15));

    Map<String, String> b = slice(lines[1], APAC_BODY);
    assertThat(b)
        .containsEntry("apa_corpo", "14")
        .containsEntry("apa_cmp", "202610")
        .containsEntry("apa_num", "3126200000001")
        .containsEntry("apa_coduf", "31")
        .containsEntry("apa_codcnes", "7654321")
        .containsEntry("apa_nomepcnte", pad("MARIA DA CONCEICAO TESTE", 30))
        .containsEntry("apa_nomemae", pad("ANA TESTE", 30))
        .containsEntry("apa_logpcnte", pad("RUA DAS FLORES", 30))
        .containsEntry("apa_numpcnte", pad("123", 5))
        .containsEntry("apa_cplpcnte", pad("APTO 2", 10))
        .containsEntry("apa_ceppcnte", "39400000")
        .containsEntry("apa_munpcnte", "3143302")
        .containsEntry("apa_datanascim", "19800517")
        .containsEntry("apa_sexopcnte", "F")
        .containsEntry("apa_codprinc", "0304020010")
        .containsEntry("apa_cnspct", CIT_CNS)
        .containsEntry("apa_carate", "01")
        .containsEntry("apa_raca", "99")
        .containsEntry("apa_bairro", pad("CENTRO", 30))
        .containsEntry("apa_cnsexec", PROF_CNS)
        .containsEntry("apa_cpfpcnte", blanks(11))
        // não conhecidos pelo barramento: completados no APAC-Mag/SIA
        .containsEntry("apa_dtiinval", blanks(8))
        .containsEntry("apa_tipapac", " ")
        .containsEntry("apa_cnsdir", blanks(15))
        .containsEntry("apa_semcpf", " ");

    Map<String, String> p1 = slice(lines[2], APAC_PROCEDURE);
    assertThat(p1)
        .containsEntry("pap_corpo", "13")
        .containsEntry("pap_cmp", "202610")
        .containsEntry("pap_num", "3126200000001")
        .containsEntry("pap_codproc", "0304020010")
        .containsEntry("pap_cbo", "225270")
        .containsEntry("pap_qtdprod", "0000001")
        .containsEntry("pap_cidp", "C509")
        .containsEntry("pap_cnes_terc", blanks(7));
    assertThat(slice(lines[3], APAC_PROCEDURE))
        .containsEntry("pap_codproc", "0304100013")
        .containsEntry("pap_qtdprod", "0000003");

    Map<String, String> b2 = slice(lines[4], APAC_BODY);
    assertThat(b2)
        .containsEntry("apa_num", "3126200000002")
        .containsEntry("apa_cnspct", blanks(15))
        .containsEntry("apa_cpfpcnte", CIT_CPF)
        .containsEntry("apa_carate", "02")
        .containsEntry("apa_nomepcnte", blanks(30));
  }

  @Test
  void apacSemNumeroDeAutorizacaoERecusada() {
    Line semNumero =
        new Line(
            "prod_x",
            "apac",
            "7654321",
            "202610",
            PROF_CNS,
            "225270",
            LocalDate.of(2026, 10, 5),
            "0304020010",
            CIT_CNS,
            null,
            "F",
            null,
            null,
            null,
            1,
            null,
            null,
            null);
    assertThatThrownBy(() -> ExportLayouts.apacMag("202610", List.of(semNumero), HEADER))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("APAC sem número");
  }

  // ------------------------------------------------------------------------------------------

  private static void assertLayout(RecordLayout layout, List<Spec> expected, int length) {
    assertThat(layout.length()).as(layout.type()).isEqualTo(length);
    assertThat(layout.fields())
        .as(layout.type())
        .extracting(Field::name, Field::start, Field::end)
        .containsExactlyElementsOf(
            expected.stream()
                .map(e -> org.assertj.core.groups.Tuple.tuple(e.name(), e.start(), e.end()))
                .toList());
    int sum = expected.stream().mapToInt(Spec::size).sum();
    assertThat(sum).as(layout.type() + " soma dos tamanhos").isEqualTo(length);
  }

  private static Map<String, String> slice(String line, List<Spec> specs) {
    int length = specs.get(specs.size() - 1).end();
    assertThat(line).as("tamanho do registro %s", line.substring(0, 2)).hasSize(length);
    Map<String, String> out = new java.util.LinkedHashMap<>();
    for (Spec spec : specs) {
      out.put(spec.name(), line.substring(spec.start() - 1, spec.end()));
    }
    return out;
  }

  private static String pad(String v, int width) {
    return v + " ".repeat(width - v.length());
  }

  private static String blanks(int width) {
    return " ".repeat(width);
  }
}
