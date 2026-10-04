package br.gov.sus.nexus.connectors.sia;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Leitor de tabelas dBase III ({@code .dbf}) — o formato dos arquivos de disseminação do DATASUS
 * (SIH: RD/RJ/SP/ER; SIA: PA/BI/AP*) depois de expandidos do {@code .dbc} pelo TabWin ou pelo
 * {@link DbcDecompressor} (aceita os dois: um {@code .dbc} é descomprimido antes da leitura).
 *
 * <p>Estrutura (dBase III): bytes 4–7 = nº de registros, 8–9 = tamanho do cabeçalho, 10–11 =
 * tamanho do registro; a partir do byte 32, descritores de campo de 32 bytes (nome em 11 bytes
 * ASCII terminados por NUL, tipo no byte 11, tamanho no byte 16) até o terminador {@code 0x0D}.
 * Cada registro começa com o marcador de exclusão ({@code '*'} = excluído, ignorado). Os valores
 * são devolvidos como texto sem espaços nas pontas, chaveados pelo nome do campo tal como no
 * arquivo.
 */
public final class DbfReader {

  /** Descritor de campo. */
  public record Field(String name, char type, int length, int decimals) {}

  /** Tabela lida. */
  public record Table(List<Field> fields, List<Map<String, String>> rows) {}

  private DbfReader() {}

  /** Lê um {@code .dbf} (ou {@code .dbc}, descomprimido antes). */
  public static Table read(byte[] content, Charset charset) {
    byte[] dbf = DbcDecompressor.looksLikeDbc(content) ? DbcDecompressor.toDbf(content) : content;
    if (dbf.length < 33) throw new IllegalArgumentException("DBF truncado");
    long records = DbcDecompressor.u32(dbf, 4);
    int headerLen = DbcDecompressor.u16(dbf, 8);
    int recordLen = DbcDecompressor.u16(dbf, 10);
    if (headerLen < 33 || headerLen > dbf.length || recordLen < 1) {
      throw new IllegalArgumentException("DBF com cabeçalho inválido");
    }
    List<Field> fields = new ArrayList<>();
    int offset = 32;
    while (offset + 32 <= headerLen && (dbf[offset] & 0xff) != 0x0D) {
      int nameEnd = offset;
      while (nameEnd < offset + 11 && dbf[nameEnd] != 0) nameEnd++;
      String name =
          new String(dbf, offset, nameEnd - offset, java.nio.charset.StandardCharsets.US_ASCII)
              .trim();
      fields.add(
          new Field(
              name,
              (char) (dbf[offset + 11] & 0xff),
              dbf[offset + 16] & 0xff,
              dbf[offset + 17] & 0xff));
      offset += 32;
    }
    int declared = 1;
    for (Field f : fields) declared += f.length();
    if (declared != recordLen) {
      throw new IllegalArgumentException(
          "DBF inconsistente: registro de " + recordLen + " bytes, campos somam " + declared);
    }
    List<Map<String, String>> rows = new ArrayList<>();
    for (long i = 0; i < records; i++) {
      long start = headerLen + i * recordLen;
      if (start + recordLen > dbf.length) {
        throw new IllegalArgumentException(
            "DBF truncado: " + records + " registros declarados, " + i + " presentes");
      }
      int pos = (int) start;
      if (dbf[pos] == '*') continue; // registro excluído
      pos++;
      Map<String, String> row = new LinkedHashMap<>();
      for (Field f : fields) {
        row.put(f.name(), new String(dbf, pos, f.length(), charset).trim());
        pos += f.length();
      }
      rows.add(row);
    }
    return new Table(List.copyOf(fields), rows);
  }
}
