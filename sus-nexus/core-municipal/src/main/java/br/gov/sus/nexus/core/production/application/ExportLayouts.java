package br.gov.sus.nexus.core.production.application;

import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Layouts de exportação de lote. <b>Layouts de referência a homologar</b> com o validador oficial
 * (BPA Magnético/SIA) antes do uso em produção — o barramento NÃO transmite; o arquivo é entregue
 * ao sistema oficial pelo faturamento municipal.
 *
 * <h2>{@code bpa_mag_ref_v1} — BPA-Mag simplificado (BPA-C e BPA-I)</h2>
 *
 * Registros de largura fixa, ASCII, terminados por CRLF; numéricos alinhados à direita com zeros,
 * alfanuméricos à esquerda com espaços. Até 20 linhas por folha.
 *
 * <pre>
 * 01 cabeçalho : "01" "#BPA#" competência(6) linhas(6) folhas(6) controle(4) origem(30) sigla(6)
 *                cnpj_cpf(14) destino(40) indicador_destino(1: M) versão(10)
 *                controle = ((Σ códigos de procedimento + Σ quantidades) mod 1111) + 1111
 * 02 BPA-C     : "02" cnes(7) competência(6) cbo(6) folha(3) sequência(2) procedimento(10)
 *                idade(3) quantidade(6) origem(3: "BPA")
 * 03 BPA-I     : "03" cnes(7) competência(6) cns_profissional(15) cbo(6) data_atendimento(8:
 *                AAAAMMDD) folha(3) sequência(2) procedimento(10) cns_paciente(15) sexo(1: M|F)
 *                ibge(6) cid(4) idade(3) quantidade(6) caráter(2) autorização(13) origem(3: "BPA")
 * </pre>
 *
 * Campos nominais (nome, nascimento, raça/cor, endereço) não trafegam pelo barramento e são
 * completados no sistema oficial. CNS ausente (sem cifra disponível) sai com zeros e é contado em
 * {@code lines_missing_identifiers}.
 *
 * <h2>{@code csv_ref_v1} — CSV de referência (qualquer instrumento)</h2>
 *
 * UTF-8, separador {@code ;}, cabeçalho na primeira linha.
 */
public final class ExportLayouts {

  public static final String BPA_MAG_REF_V1 = "bpa_mag_ref_v1";
  public static final String CSV_REF_V1 = "csv_ref_v1";
  static final int LINES_PER_SHEET = 20;
  static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;

  private ExportLayouts() {}

  /** Linha a exportar (identificadores já decifrados para o arquivo; nunca logados). */
  public record Line(
      String recordId,
      String kind,
      String cnes,
      String competence,
      String professionalCns,
      String cbo,
      java.time.LocalDate attendanceDate,
      String procedureCode,
      String citizenCns,
      String sex,
      String cityIbge,
      String cid,
      Integer age,
      int quantity,
      String characterOfCare,
      String authorization) {}

  /** Arquivo gerado. */
  public record Rendered(byte[] content, int lines, int missingIdentifiers, String extension) {}

  /** Cabeçalho do órgão de origem/destino. */
  public record Header(String originName, String originAcronym, String originDocument) {}

  public static Rendered bpaMag(String competence, List<Line> lines, Header h) {
    StringBuilder body = new StringBuilder();
    long control = 0;
    int missing = 0;
    for (int i = 0; i < lines.size(); i++) {
      Line l = lines.get(i);
      int sheet = i / LINES_PER_SHEET + 1;
      int seq = i % LINES_PER_SHEET + 1;
      control += Long.parseLong(l.procedureCode()) + l.quantity();
      if ("bpa_c".equals(l.kind())) {
        body.append("02")
            .append(num(l.cnes(), 7))
            .append(num(l.competence(), 6))
            .append(num(l.cbo(), 6))
            .append(num(sheet, 3))
            .append(num(seq, 2))
            .append(num(l.procedureCode(), 10))
            .append(num(l.age() == null ? 0 : l.age(), 3))
            .append(num(l.quantity(), 6))
            .append("BPA");
      } else {
        if (l.professionalCns() == null || l.citizenCns() == null) {
          missing++;
        }
        body.append("03")
            .append(num(l.cnes(), 7))
            .append(num(l.competence(), 6))
            .append(num(l.professionalCns(), 15))
            .append(num(l.cbo(), 6))
            .append(l.attendanceDate().format(DATE))
            .append(num(sheet, 3))
            .append(num(seq, 2))
            .append(num(l.procedureCode(), 10))
            .append(num(l.citizenCns(), 15))
            .append(alpha(l.sex(), 1))
            .append(num(ibge6(l.cityIbge()), 6))
            .append(alpha(l.cid() == null ? null : l.cid().replace(".", ""), 4))
            .append(num(l.age() == null ? 0 : l.age(), 3))
            .append(num(l.quantity(), 6))
            .append(num(characterCode(l.characterOfCare()), 2))
            .append(alpha(l.authorization(), 13))
            .append("BPA");
      }
      body.append("\r\n");
    }
    int sheets = Math.max(1, (lines.size() + LINES_PER_SHEET - 1) / LINES_PER_SHEET);
    String header =
        "01"
            + "#BPA#"
            + num(competence, 6)
            + num(lines.size(), 6)
            + num(sheets, 6)
            + num((control % 1111) + 1111, 4)
            + alpha(h.originName(), 30)
            + alpha(h.originAcronym(), 6)
            + num(h.originDocument(), 14)
            + alpha("SECRETARIA MUNICIPAL DE SAUDE", 40)
            + "M"
            + alpha("SUSNEXUS01", 10)
            + "\r\n";
    return new Rendered(
        (header + body).getBytes(StandardCharsets.US_ASCII), lines.size(), missing, "txt");
  }

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

  /** Código IBGE do município sem o dígito verificador (6 primeiros dígitos). */
  static String ibge6(String ibge) {
    if (ibge == null) {
      return null;
    }
    String d = ibge.replaceAll("[^0-9]", "");
    return d.length() > 6 ? d.substring(0, 6) : d;
  }

  static String characterCode(String character) {
    return switch (character == null ? "" : character) {
      case "elective" -> "01";
      case "urgency" -> "02";
      case "work_accident" -> "03";
      case "other" -> "04";
      default -> "01";
    };
  }

  static String num(long value, int width) {
    return num(Long.toString(value), width);
  }

  static String num(String value, int width) {
    String digits = value == null ? "" : value.replaceAll("[^0-9]", "");
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

  private static String nz(String s) {
    return s == null ? "" : s.replace(";", ",");
  }
}
