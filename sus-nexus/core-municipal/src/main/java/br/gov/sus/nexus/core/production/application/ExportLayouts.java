package br.gov.sus.nexus.core.production.application;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Layouts de exportação de lote de produção. O barramento NÃO transmite: o arquivo é entregue ao
 * sistema oficial (SIA/SIH) pelo faturamento municipal. Tabela de campos, fontes e pendências em
 * {@code docs/integracoes/layouts-sia-sih.md}.
 *
 * <h2>{@code bpa_mag_v202412} — "Layout de Exportação BPA" (BPA-Magnético → SIA)</h2>
 *
 * Fonte: DATASUS/SIA, {@code Layout_Exportacao_BPA.pdf} publicado em
 * https://sia.datasus.gov.br/documentos/listar_ftp_bpa.php (12/12/2024 — versão com {@code
 * prd_situacao_rua}, vigente a partir de 12/2024). O PDF não pôde ser baixado deste ambiente (host
 * bloqueado pela política de rede); as posições foram transcritas de cópia do PDF oficial
 * (docs/data-dictionary-bpa.md do projeto VINIClUS/CnesData) e conferidas pela soma dos tamanhos
 * (cabeçalho 132, BPA-C 50, BPA-I 352 bytes com CR+LF) — <b>conferência direta com o PDF original
 * pendente</b>. Texto ASCII, largura fixa, CR+LF; numéricos à direita com zeros; alfanuméricos à
 * esquerda com espaços; campo sem informação = espaços.
 *
 * <pre>
 * 01 cabeçalho (130 + CRLF): "01" "#BPA#" cbc_mvm(6) cbc_lin(6) cbc_flh(6) cbc_smt_vrf(4)
 *    cbc_rsp(30) cbc_sgl(6) cbc_cgccpf(14) cbc_dst(40) cbc_dst_in(1: E|M) cbc_versao(10)
 *    cbc_smt_vrf = (Σ (código do procedimento + quantidade) de todas as linhas) mod 1111 + 1111
 * 02 BPA-C (48 + CRLF): cnes(7) cmp(6) cbo(6) flh(3) seq(2: 01..20) pa(10) idade(3) qt(6) org(3)
 * 03 BPA-I (350 + CRLF): cnes(7) cmp(6) cnsmed(15) cbo(6) dtaten(8) flh(3) seq(2) pa(10)
 *    cnspac(15) sexo(1) ibge(6) cid(4) idade(3) qt(6) caten(2) naut(13) org(3) nmpac(30)
 *    dtnasc(8) raca(2) etnia(4) nac(3) srv(3) clf(3) equipe_seq(8) equipe_area(4) cnpj(14)
 *    cep(8) lograd(3) end(30) compl(10) num(5) bairro(30) ddtel(11) email(40) ine(10) cpf(11)
 *    situacao_rua(1)
 * </pre>
 *
 * Paciente: CNS <b>ou</b> CPF (o outro em branco). Folhas de até 20 linhas, numeradas por tipo
 * (BPA-C e BPA-I); no BPA-I a folha também quebra por profissional (CNS + CBO), como no BPA-Mag.
 *
 * <h2>{@code apac_mag_v202607} — "Layout da interface texto do APAC e do SIA" (APAC → SIA)</h2>
 *
 * Fonte: DATASUS/SIA, layout de exportação da APAC publicado em
 * https://sia.datasus.gov.br/versao/listar_ftp_apac.php (inclui {@code apa_semcpf}, válido a partir
 * de 07/2026). PDF não acessível deste ambiente; posições transcritas de implementação que segue o
 * layout interno SIA/APAC consultado em 08/07/2026 (Editor_APAC.py do projeto
 * ErikaVazCravo/Arquivos_APAC) — <b>conferência direta com o PDF original pendente</b>. Registros:
 * 01 cabeçalho (137), 14 corpo da APAC (537), 13 procedimentos (97). Partes variáveis (06 laudo
 * geral, 07 quimioterapia, 08 radioterapia, ...) não são geradas (o barramento não tem os dados do
 * laudo). Controle = (Σ nº das APAC + Σ (procedimento + quantidade) dos registros 13) mod 1111 +
 * 1111.
 *
 * <p>Campos obrigatórios que o barramento não conhece (validade, tipo de APAC, médico responsável,
 * autorizador, datas de solicitação/autorização, emissor, nacionalidade, motivo de saída, ...) saem
 * em branco e devem ser completados no APAC-Mag/SIA antes da transmissão.
 *
 * <h2>AIH (SISAIH01 → SIHD)</h2>
 *
 * Sem layout oficial implementado: o "Layout da interface texto do SISAIH01" (sihd.datasus.gov.br)
 * não foi acessível deste ambiente nem localizado em transcrição verificável — AIH segue em {@code
 * csv_ref_v1}.
 *
 * <h2>{@code csv_ref_v1} — CSV de referência (qualquer instrumento)</h2>
 *
 * UTF-8, separador {@code ;}, cabeçalho na primeira linha. Alternativa local, não é layout oficial.
 */
public final class ExportLayouts {

  public static final String BPA_MAG_V202412 = "bpa_mag_v202412";
  public static final String APAC_MAG_V202607 = "apac_mag_v202607";
  public static final String CSV_REF_V1 = "csv_ref_v1";

  static final int LINES_PER_SHEET = 20;
  static final int MAX_SHEETS = 999;
  static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;
  static final String CRLF = "\r\n";
  static final String SYSTEM_VERSION = "SUSNEXUS01";

  /** Campo de largura fixa (posições 1-based inclusivas; {@code numeric} = zeros à esquerda). */
  public record Field(String name, int start, int end, boolean numeric) {
    public int size() {
      return end - start + 1;
    }
  }

  /** Registro de largura fixa: campos contíguos a partir da posição 1 (sem o CR+LF). */
  public record RecordLayout(String type, List<Field> fields) {
    public RecordLayout {
      fields = List.copyOf(fields);
      int expected = 1;
      for (Field f : fields) {
        if (f.start() != expected || f.end() < f.start()) {
          throw new IllegalStateException(type + ": campo " + f.name() + " fora de sequência");
        }
        expected = f.end() + 1;
      }
    }

    public int length() {
      return fields.get(fields.size() - 1).end();
    }

    public Field field(String name) {
      return fields.stream()
          .filter(f -> f.name().equals(name))
          .findFirst()
          .orElseThrow(() -> new IllegalArgumentException(type + ": campo desconhecido " + name));
    }
  }

  // ---------------------------------------------------------------- BPA-Magnético (12/2024)

  public static final RecordLayout BPA_HEADER =
      layout(
          "bpa_01",
          n("cbc_hdr", 2),
          a("cbc_hdr_bpa", 5),
          n("cbc_mvm", 6),
          n("cbc_lin", 6),
          n("cbc_flh", 6),
          n("cbc_smt_vrf", 4),
          a("cbc_rsp", 30),
          a("cbc_sgl", 6),
          n("cbc_cgccpf", 14),
          a("cbc_dst", 40),
          a("cbc_dst_in", 1),
          a("cbc_versao", 10));

  public static final RecordLayout BPA_C =
      layout(
          "bpa_02",
          n("prd_ident", 2),
          n("prd_cnes", 7),
          n("prd_cmp", 6),
          a("prd_cbo", 6),
          n("prd_flh", 3),
          n("prd_seq", 2),
          n("prd_pa", 10),
          n("prd_idade", 3),
          n("prd_qt", 6),
          a("prd_org", 3));

  public static final RecordLayout BPA_I =
      layout(
          "bpa_03",
          n("prd_ident", 2),
          n("prd_cnes", 7),
          n("prd_cmp", 6),
          n("prd_cnsmed", 15),
          a("prd_cbo", 6),
          n("prd_dtaten", 8),
          n("prd_flh", 3),
          n("prd_seq", 2),
          n("prd_pa", 10),
          n("prd_cnspac", 15),
          a("prd_sexo", 1),
          n("prd_ibge", 6),
          a("prd_cid", 4),
          n("prd_idade", 3),
          n("prd_qt", 6),
          n("prd_caten", 2),
          n("prd_naut", 13),
          a("prd_org", 3),
          a("prd_nmpac", 30),
          n("prd_dtnasc", 8),
          n("prd_raca", 2),
          n("prd_etnia", 4),
          n("prd_nac", 3),
          n("prd_srv", 3),
          n("prd_clf", 3),
          n("prd_equipe_seq", 8),
          n("prd_equipe_area", 4),
          n("prd_cnpj", 14),
          n("prd_cep_pcnte", 8),
          n("prd_lograd_pcnte", 3),
          a("prd_end_pcnte", 30),
          a("prd_compl_pcnte", 10),
          a("prd_num_pcnte", 5),
          a("prd_bairro_pcnte", 30),
          n("prd_ddtel_pcnte", 11),
          a("prd_email_pcnte", 40),
          n("prd_ine", 10),
          n("prd_cpf_pcnte", 11),
          a("prd_situacao_rua", 1));

  // ---------------------------------------------------------------- APAC (07/2026)

  public static final RecordLayout APAC_HEADER =
      layout(
          "apac_01",
          n("cbc_hdr", 2),
          a("cbc_apac", 5),
          n("cbc_cmp", 6),
          n("cbc_lin", 6),
          n("cbc_smt_vrf", 4),
          a("cbc_rsp", 30),
          a("cbc_sgl", 6),
          n("cbc_cgccpf", 14),
          a("cbc_dst", 40),
          a("cbc_dst_in", 1),
          n("cbc_dtger", 8),
          a("cbc_versao", 15));

  public static final RecordLayout APAC_BODY =
      layout(
          "apac_14",
          n("apa_corpo", 2),
          n("apa_cmp", 6),
          n("apa_num", 13),
          n("apa_coduf", 2),
          n("apa_codcnes", 7),
          n("apa_pr", 8),
          n("apa_dtiinval", 8),
          n("apa_dtfimval", 8),
          n("apa_tipate", 2),
          n("apa_tipapac", 1),
          a("apa_nomepcnte", 30),
          a("apa_nomemae", 30),
          a("apa_logpcnte", 30),
          a("apa_numpcnte", 5),
          a("apa_cplpcnte", 10),
          n("apa_ceppcnte", 8),
          n("apa_munpcnte", 7),
          n("apa_datanascim", 8),
          a("apa_sexopcnte", 1),
          a("apa_nomeresp_med", 30),
          n("apa_codprinc", 10),
          n("apa_motsaida", 2),
          a("apa_dtobitoalta", 8),
          a("apa_nomediretor", 30),
          n("apa_cnspct", 15),
          n("apa_cnsres", 15),
          n("apa_cnsdir", 15),
          a("apa_cidca", 4),
          n("apa_npront", 10),
          n("apa_codsol", 7),
          n("apa_datsol", 8),
          n("apa_dataut", 8),
          a("apa_codemis", 10),
          n("apa_carate", 2),
          n("apa_apacant", 13),
          n("apa_raca", 2),
          a("apa_nomeresp_pac", 30),
          n("apa_nascpcnte", 3),
          n("apa_etnia", 4),
          n("apa_cdlogr", 3),
          a("apa_bairro", 30),
          n("apa_dddtelcontato", 2),
          n("apa_telcontato", 9),
          a("apa_email", 40),
          n("apa_cnsexec", 15),
          n("apa_cpfpcnte", 11),
          n("apa_ine", 10),
          a("apa_strua", 1),
          n("apa_fntorca", 2),
          a("apa_emenpar", 1),
          a("apa_semcpf", 1));

  public static final RecordLayout APAC_PROCEDURE =
      layout(
          "apac_13",
          n("pap_corpo", 2),
          n("pap_cmp", 6),
          n("pap_num", 13),
          n("pap_codproc", 10),
          n("pap_cbo", 6),
          n("pap_qtdprod", 7),
          n("pap_cgc", 14),
          a("pap_nf", 6),
          a("pap_cidp", 4),
          a("pap_cids", 4),
          n("pap_srv", 3),
          n("pap_clf", 3),
          n("pap_equipe_seq", 8),
          n("pap_equipe_area", 4),
          n("pap_cnes_terc", 7));

  private ExportLayouts() {}

  /** Linha a exportar (identificadores e dados nominais decifrados só para o arquivo). */
  public record Line(
      String recordId,
      String kind,
      String cnes,
      String competence,
      String professionalCns,
      String cbo,
      LocalDate attendanceDate,
      String procedureCode,
      String citizenCns,
      String citizenCpf,
      String sex,
      String cityIbge,
      String cid,
      Integer age,
      int quantity,
      String characterOfCare,
      String authorization,
      Patient patient) {}

  /** Dados nominais do paciente (cadastro do MPI); nunca logados. */
  public record Patient(
      String name,
      String motherName,
      LocalDate birthdate,
      String postalCode,
      String street,
      String number,
      String complement,
      String district) {
    static final Patient EMPTY = new Patient(null, null, null, null, null, null, null, null);
  }

  /** Arquivo gerado. */
  public record Rendered(byte[] content, int lines, int missingIdentifiers, String extension) {}

  /**
   * Cabeçalho: órgão de origem (nome, sigla, CNPJ/CPF), órgão de destino (nome e indicador {@code
   * M} municipal / {@code E} estadual), UF (IBGE, 2 dígitos — APAC) e data de geração.
   */
  public record Header(
      String originName,
      String originAcronym,
      String originDocument,
      String destinationName,
      String destinationIndicator,
      String ufIbge,
      LocalDate generatedOn) {}

  // ---------------------------------------------------------------- BPA

  public static Rendered bpaMag(String competence, List<Line> lines, Header h) {
    List<Line> consolidated = lines.stream().filter(l -> "bpa_c".equals(l.kind())).toList();
    List<Line> individual =
        lines.stream()
            .filter(l -> !"bpa_c".equals(l.kind()))
            .sorted(
                Comparator.comparing((Line l) -> nz(l.professionalCns()))
                    .thenComparing(l -> nz(l.cbo())))
            .toList();
    StringBuilder body = new StringBuilder();
    long sum = 0;
    int missing = 0;
    int sheets = 0;
    // BPA-C: folhas de 20 linhas
    for (int i = 0; i < consolidated.size(); i++) {
      Line l = consolidated.get(i);
      int sheet = i / LINES_PER_SHEET + 1;
      checkSheet(sheet);
      sum += procedureSum(l);
      Rec r = new Rec(BPA_C);
      r.set("prd_ident", "02");
      r.set("prd_cnes", l.cnes());
      r.set("prd_cmp", l.competence());
      r.set("prd_cbo", l.cbo());
      r.set("prd_flh", sheet);
      r.set("prd_seq", i % LINES_PER_SHEET + 1);
      r.set("prd_pa", l.procedureCode());
      r.set("prd_idade", l.age() == null ? 0 : l.age());
      r.set("prd_qt", l.quantity());
      r.set("prd_org", "BPA");
      body.append(r).append(CRLF);
    }
    sheets += (consolidated.size() + LINES_PER_SHEET - 1) / LINES_PER_SHEET;
    // BPA-I: folhas de 20 linhas por profissional (CNS + CBO)
    int sheet = 0;
    int seq = LINES_PER_SHEET;
    String currentProfessional = null;
    for (Line l : individual) {
      String professional = nz(l.professionalCns()) + "|" + nz(l.cbo());
      if (seq == LINES_PER_SHEET || !professional.equals(currentProfessional)) {
        sheet++;
        seq = 0;
        currentProfessional = professional;
        checkSheet(sheet);
      }
      seq++;
      if (blank(l.professionalCns()) || (blank(l.citizenCns()) && blank(l.citizenCpf()))) {
        missing++;
      }
      sum += procedureSum(l);
      Patient p = l.patient() == null ? Patient.EMPTY : l.patient();
      boolean useCns = !blank(l.citizenCns());
      Rec r = new Rec(BPA_I);
      r.set("prd_ident", "03");
      r.set("prd_cnes", l.cnes());
      r.set("prd_cmp", l.competence());
      r.set("prd_cnsmed", l.professionalCns());
      r.set("prd_cbo", l.cbo());
      r.set("prd_dtaten", l.attendanceDate() == null ? null : l.attendanceDate().format(DATE));
      r.set("prd_flh", sheet);
      r.set("prd_seq", seq);
      r.set("prd_pa", l.procedureCode());
      r.set("prd_cnspac", useCns ? l.citizenCns() : null);
      r.set("prd_sexo", l.sex());
      r.set("prd_ibge", ibge6(l.cityIbge()));
      r.set("prd_cid", l.cid() == null ? null : l.cid().replace(".", ""));
      r.set("prd_idade", l.age());
      r.set("prd_qt", l.quantity());
      r.set("prd_caten", characterCode(l.characterOfCare()));
      r.set("prd_naut", l.authorization());
      r.set("prd_org", "BPA");
      r.set("prd_nmpac", p.name());
      r.set("prd_dtnasc", p.birthdate() == null ? null : p.birthdate().format(DATE));
      r.set("prd_raca", RACE_NO_INFORMATION);
      r.set("prd_cep_pcnte", p.postalCode());
      r.set("prd_end_pcnte", p.street());
      r.set("prd_compl_pcnte", p.complement());
      r.set("prd_num_pcnte", p.number());
      r.set("prd_bairro_pcnte", p.district());
      r.set("prd_cpf_pcnte", useCns ? null : l.citizenCpf());
      body.append(r).append(CRLF);
    }
    sheets += sheet;
    Rec header = new Rec(BPA_HEADER);
    header.set("cbc_hdr", "01");
    header.set("cbc_hdr_bpa", "#BPA#");
    header.set("cbc_mvm", competence);
    header.set("cbc_lin", lines.size());
    header.set("cbc_flh", sheets);
    header.set("cbc_smt_vrf", controlField(sum));
    header.set("cbc_rsp", h.originName());
    header.set("cbc_sgl", h.originAcronym());
    header.set("cbc_cgccpf", h.originDocument());
    header.set("cbc_dst", h.destinationName());
    header.set("cbc_dst_in", h.destinationIndicator());
    header.set("cbc_versao", SYSTEM_VERSION);
    return new Rendered(
        (header + CRLF + body).getBytes(StandardCharsets.US_ASCII), lines.size(), missing, "txt");
  }

  /** Raça/cor "99 — sem informação" (o barramento não recebe raça/cor na produção). */
  static final String RACE_NO_INFORMATION = "99";

  // ---------------------------------------------------------------- APAC

  public static Rendered apacMag(String competence, List<Line> lines, Header h) {
    Map<String, List<Line>> byApac = new LinkedHashMap<>();
    for (Line l : lines) {
      byApac.computeIfAbsent(digitsOrEmpty(l.authorization()), k -> new ArrayList<>()).add(l);
    }
    if (byApac.containsKey("")) {
      throw new IllegalArgumentException("APAC sem número de autorização no lote");
    }
    StringBuilder body = new StringBuilder();
    long sum = 0;
    int missing = 0;
    Set<String> numbers = new LinkedHashSet<>();
    for (Map.Entry<String, List<Line>> e : byApac.entrySet()) {
      String number = e.getKey();
      numbers.add(number);
      Line principal = e.getValue().get(0);
      Patient p = principal.patient() == null ? Patient.EMPTY : principal.patient();
      boolean useCns = !blank(principal.citizenCns());
      Rec b = new Rec(APAC_BODY);
      b.set("apa_corpo", "14");
      b.set("apa_cmp", competence);
      b.set("apa_num", number);
      b.set("apa_coduf", h.ufIbge());
      b.set("apa_codcnes", principal.cnes());
      b.set("apa_nomepcnte", p.name());
      b.set("apa_nomemae", p.motherName());
      b.set("apa_logpcnte", p.street());
      b.set("apa_numpcnte", p.number());
      b.set("apa_cplpcnte", p.complement());
      b.set("apa_ceppcnte", p.postalCode());
      b.set("apa_munpcnte", digitsOrNull(principal.cityIbge()));
      b.set("apa_datanascim", p.birthdate() == null ? null : p.birthdate().format(DATE));
      b.set("apa_sexopcnte", principal.sex());
      b.set("apa_codprinc", principal.procedureCode());
      b.set("apa_cnspct", useCns ? principal.citizenCns() : null);
      b.set("apa_carate", characterCode(principal.characterOfCare()));
      b.set("apa_raca", RACE_NO_INFORMATION);
      b.set("apa_bairro", p.district());
      b.set("apa_cnsexec", principal.professionalCns());
      b.set("apa_cpfpcnte", useCns ? null : principal.citizenCpf());
      body.append(b).append(CRLF);
      for (Line l : e.getValue()) {
        if (blank(l.professionalCns()) || (blank(l.citizenCns()) && blank(l.citizenCpf()))) {
          missing++;
        }
        sum += procedureSum(l);
        Rec r = new Rec(APAC_PROCEDURE);
        r.set("pap_corpo", "13");
        r.set("pap_cmp", competence);
        r.set("pap_num", number);
        r.set("pap_codproc", l.procedureCode());
        r.set("pap_cbo", l.cbo());
        r.set("pap_qtdprod", l.quantity());
        r.set("pap_cidp", l.cid() == null ? null : l.cid().replace(".", ""));
        body.append(r).append(CRLF);
      }
    }
    for (String number : numbers) {
      sum += Long.parseLong(number);
    }
    Rec header = new Rec(APAC_HEADER);
    header.set("cbc_hdr", "01");
    header.set("cbc_apac", "#APAC");
    header.set("cbc_cmp", competence);
    header.set("cbc_lin", numbers.size());
    header.set("cbc_smt_vrf", controlField(sum));
    header.set("cbc_rsp", h.originName());
    header.set("cbc_sgl", h.originAcronym());
    header.set("cbc_cgccpf", h.originDocument());
    header.set("cbc_dst", h.destinationName());
    header.set("cbc_dst_in", h.destinationIndicator());
    header.set("cbc_dtger", h.generatedOn() == null ? null : h.generatedOn().format(DATE));
    header.set("cbc_versao", SYSTEM_VERSION);
    return new Rendered(
        (header + CRLF + body).getBytes(StandardCharsets.US_ASCII), lines.size(), missing, "txt");
  }

  // ---------------------------------------------------------------- CSV de referência

  public static Rendered csv(List<Line> lines) {
    StringBuilder sb =
        new StringBuilder(
            "line;kind;competence;cnes;procedure_code;cbo;attendance_date;quantity;"
                + "professional_cns;citizen_cns;sex;age;city_ibge;cid;character_of_care;"
                + "authorization;production_record_id\n");
    int missing = 0;
    for (int i = 0; i < lines.size(); i++) {
      Line l = lines.get(i);
      if (!"bpa_c".equals(l.kind()) && (l.professionalCns() == null || l.citizenCns() == null)) {
        missing++;
      }
      sb.append(i + 1)
          .append(';')
          .append(l.kind())
          .append(';')
          .append(l.competence())
          .append(';')
          .append(l.cnes())
          .append(';')
          .append(l.procedureCode())
          .append(';')
          .append(l.cbo())
          .append(';')
          .append(l.attendanceDate())
          .append(';')
          .append(l.quantity())
          .append(';')
          .append(nz(l.professionalCns()))
          .append(';')
          .append(nz(l.citizenCns()))
          .append(';')
          .append(nz(l.sex()))
          .append(';')
          .append(l.age() == null ? "" : l.age().toString())
          .append(';')
          .append(nz(l.cityIbge()))
          .append(';')
          .append(nz(l.cid()))
          .append(';')
          .append(nz(l.characterOfCare()))
          .append(';')
          .append(nz(l.authorization()))
          .append(';')
          .append(l.recordId())
          .append('\n');
    }
    return new Rendered(
        sb.toString().getBytes(StandardCharsets.UTF_8), lines.size(), missing, "csv");
  }

  // ---------------------------------------------------------------- regras comuns

  /**
   * Campo de controle ("domínio de verificação") do cabeçalho: resto da divisão da soma por 1111,
   * mais 1111 (resultado sempre entre 1111 e 2221).
   */
  public static int controlField(long sum) {
    return (int) (sum % 1111) + 1111;
  }

  private static long procedureSum(Line l) {
    String code = digitsOrEmpty(l.procedureCode());
    return (code.isEmpty() ? 0 : Long.parseLong(code)) + l.quantity();
  }

  private static void checkSheet(int sheet) {
    if (sheet > MAX_SHEETS) {
      throw new IllegalArgumentException("lote excede " + MAX_SHEETS + " folhas do BPA");
    }
  }

  /** Código IBGE do município sem o dígito verificador (6 primeiros dígitos). */
  static String ibge6(String ibge) {
    if (ibge == null) {
      return null;
    }
    String d = ibge.replaceAll("[^0-9]", "");
    return d.length() > 6 ? d.substring(0, 6) : d;
  }

  /**
   * Caráter de atendimento (tabela do SIA: 01 eletivo, 02 urgência, 03 acidente no local de
   * trabalho ou a serviço da empresa, 04–06 outros acidentes/lesões). O domínio do barramento só
   * distingue {@code other}, que não determina um código entre 04 e 06: sai em branco.
   */
  static String characterCode(String character) {
    return switch (character == null ? "" : character) {
      case "elective" -> "01";
      case "urgency" -> "02";
      case "work_accident" -> "03";
      default -> null;
    };
  }

  static String num(String value, int width) {
    String digits = digitsOrEmpty(value);
    if (digits.isEmpty()) {
      return " ".repeat(width);
    }
    if (digits.length() > width) {
      digits = digits.substring(digits.length() - width);
    }
    return "0".repeat(width - digits.length()) + digits;
  }

  static String alpha(String value, int width) {
    String v =
        value == null
            ? ""
            : java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replaceAll("[^\\x20-\\x7E]", "")
                .toUpperCase(Locale.ROOT);
    if (v.length() > width) {
      v = v.substring(0, width);
    }
    return v + " ".repeat(width - v.length());
  }

  private static RecordLayout layout(String type, Object... specs) {
    List<Field> fields = new ArrayList<>();
    int pos = 1;
    for (Object o : specs) {
      Spec s = (Spec) o;
      fields.add(new Field(s.name(), pos, pos + s.size() - 1, s.numeric()));
      pos += s.size();
    }
    return new RecordLayout(type, fields);
  }

  private record Spec(String name, int size, boolean numeric) {}

  private static Spec n(String name, int size) {
    return new Spec(name, size, true);
  }

  private static Spec a(String name, int size) {
    return new Spec(name, size, false);
  }

  /** Registro em montagem: começa todo em branco; cada campo é formatado pelo seu tipo. */
  static final class Rec {
    private final RecordLayout layout;
    private final char[] buf;

    Rec(RecordLayout layout) {
      this.layout = layout;
      this.buf = new char[layout.length()];
      Arrays.fill(buf, ' ');
    }

    void set(String name, Object value) {
      Field f = layout.field(name);
      String v = value == null ? null : value.toString();
      String formatted = f.numeric() ? num(v, f.size()) : alpha(v, f.size());
      formatted.getChars(0, f.size(), buf, f.start() - 1);
    }

    @Override
    public String toString() {
      return new String(buf);
    }
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }

  private static String digitsOrEmpty(String s) {
    return s == null ? "" : s.replaceAll("[^0-9]", "");
  }

  private static String digitsOrNull(String s) {
    String d = digitsOrEmpty(s);
    return d.isEmpty() ? null : d;
  }

  private static String nz(String s) {
    return s == null ? "" : s.replace(";", ",");
  }
}
