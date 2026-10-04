package br.gov.sus.nexus.connectors.sia;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Gera arquivos SINTÉTICOS no formato dos arquivos de disseminação do DATASUS para os testes:
 * tabela dBase III ({@code .dbf}) e o mesmo conteúdo como {@code .dbc} (cabeçalho DBF + 4 bytes de
 * CRC + fluxo PKWare DCL implode só com literais não codificados — subconjunto válido do formato,
 * lido pelo decodificador de referência {@code blast.c}). Nenhum dado real.
 */
final class DatasusFiles {

  /** Campo caractere ({@code C}) com o tamanho físico do arquivo oficial. */
  record Col(String name, int length) {}

  private DatasusFiles() {}

  static byte[] dbf(List<Col> cols, List<List<String>> rows) {
    return dbf(cols, rows, List.of());
  }

  /** {@code deleted}: índices de linhas gravadas com marcador de exclusão ({@code '*'}). */
  static byte[] dbf(List<Col> cols, List<List<String>> rows, List<Integer> deleted) {
    int headerLen = 32 + 32 * cols.size() + 1;
    int recordLen = 1 + cols.stream().mapToInt(Col::length).sum();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] h = new byte[32];
    h[0] = 0x03;
    h[1] = 126; // 2026
    h[2] = 9;
    h[3] = 30;
    le32(h, 4, rows.size());
    le16(h, 8, headerLen);
    le16(h, 10, recordLen);
    out.writeBytes(h);
    for (Col c : cols) {
      byte[] d = new byte[32];
      byte[] name = c.name().getBytes(StandardCharsets.US_ASCII);
      System.arraycopy(name, 0, d, 0, Math.min(10, name.length));
      d[11] = 'C';
      d[16] = (byte) c.length();
      out.writeBytes(d);
    }
    out.write(0x0D);
    for (int i = 0; i < rows.size(); i++) {
      out.write(deleted.contains(i) ? '*' : ' ');
      List<String> row = rows.get(i);
      for (int j = 0; j < cols.size(); j++) {
        String v = j < row.size() && row.get(j) != null ? row.get(j) : "";
        byte[] b = v.getBytes(StandardCharsets.ISO_8859_1);
        byte[] cell = new byte[cols.get(j).length()];
        java.util.Arrays.fill(cell, (byte) ' ');
        System.arraycopy(b, 0, cell, 0, Math.min(b.length, cell.length));
        out.writeBytes(cell);
      }
    }
    out.write(0x1A);
    return out.toByteArray();
  }

  /** Converte um {@code .dbf} em {@code .dbc} (implode com literais não codificados). */
  static byte[] dbc(byte[] dbf) {
    int headerLen = (dbf[8] & 0xff) | (dbf[9] & 0xff) << 8;
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(dbf, 0, headerLen);
    out.writeBytes(new byte[4]); // CRC (não conferido pelo leitor)
    BitWriter w = new BitWriter(out);
    w.write(0, 8); // literais não codificados
    w.write(6, 8); // dicionário de 4 KB
    for (int i = headerLen; i < dbf.length; i++) {
      w.write(0, 1); // literal
      w.write(dbf[i] & 0xff, 8);
    }
    // fim de fluxo: comprimento 519 = símbolo 15 (código canônico 1111111, 7 bits, emitido
    // invertido) + 8 bits extras 255
    w.write(1, 1);
    w.write(0, 7);
    w.write(255, 8);
    w.flush();
    return out.toByteArray();
  }

  private static void le16(byte[] b, int off, int v) {
    b[off] = (byte) v;
    b[off + 1] = (byte) (v >>> 8);
  }

  private static void le32(byte[] b, int off, int v) {
    le16(b, off, v);
    le16(b, off + 2, v >>> 16);
  }

  /** Escrita de bits LSB primeiro (como o leitor do implode). */
  private static final class BitWriter {
    private final ByteArrayOutputStream out;
    private int buf;
    private int cnt;

    BitWriter(ByteArrayOutputStream out) {
      this.out = out;
    }

    void write(int value, int bits) {
      for (int i = 0; i < bits; i++) {
        buf |= ((value >>> i) & 1) << cnt;
        if (++cnt == 8) {
          out.write(buf);
          buf = 0;
          cnt = 0;
        }
      }
    }

    void flush() {
      if (cnt > 0) out.write(buf);
      buf = 0;
      cnt = 0;
    }
  }
}
