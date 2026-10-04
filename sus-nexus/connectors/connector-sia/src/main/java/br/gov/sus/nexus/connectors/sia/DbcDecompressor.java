package br.gov.sus.nexus.connectors.sia;

import java.io.ByteArrayOutputStream;

/**
 * Descompactação dos arquivos {@code .dbc} que o DATASUS publica no FTP de disseminação ({@code
 * ftp://ftp.datasus.gov.br/dissemin/publicos/...}, ex.: {@code PARR2401.dbc}, {@code RDRR2401.dbc})
 * e que o TabWin expande para {@code .dbf} (menu "Arquivo → Comprime/Expande DBC").
 *
 * <p>Formato (o mesmo implementado pelo {@code dbc2dbf.c} do pacote R {@code read.dbc} e pelo
 * PySUS/omnisus): os {@code n} primeiros bytes são o cabeçalho DBF original ({@code n} = bytes 8–9,
 * little-endian), seguem 4 bytes de CRC e, depois, os registros DBF comprimidos com o algoritmo
 * PKWare DCL "implode". Esta classe é um porte do {@code blast.c} de Mark Adler (zlib, licença
 * zlib), o decodificador de referência desse algoritmo. O resultado é o {@code .dbf} completo.
 */
public final class DbcDecompressor {

  private static final int MAXBITS = 13;

  // Tabelas de comprimentos de código (blast.c), em representação compacta.
  private static final int[] LITLEN = {
    11, 124, 8, 7, 28, 7, 188, 13, 76, 4, 10, 8, 12, 10, 12, 10, 8, 23, 8, 9, 7, 6, 7, 8, 7, 6, 55,
    8, 23, 24, 12, 11, 7, 9, 11, 12, 6, 7, 22, 5, 7, 24, 6, 11, 9, 6, 7, 22, 7, 11, 38, 7, 9, 8, 25,
    11, 8, 11, 9, 12, 8, 12, 5, 38, 5, 38, 5, 11, 7, 5, 6, 21, 6, 10, 53, 8, 7, 24, 10, 27, 44, 253,
    253, 253, 252, 252, 252, 13, 12, 45, 12, 45, 12, 61, 12, 45, 44, 173
  };
  private static final int[] LENLEN = {2, 35, 36, 53, 38, 23};
  private static final int[] DISTLEN = {2, 20, 53, 230, 247, 151, 248};
  private static final int[] BASE = {3, 2, 4, 5, 6, 7, 8, 9, 10, 12, 16, 24, 40, 72, 136, 264};
  private static final int[] EXTRA = {0, 0, 0, 0, 0, 0, 0, 0, 1, 2, 3, 4, 5, 6, 7, 8};

  private static final Huffman LITCODE = Huffman.of(LITLEN, 256);
  private static final Huffman LENCODE = Huffman.of(LENLEN, 16);
  private static final Huffman DISTCODE = Huffman.of(DISTLEN, 64);

  private DbcDecompressor() {}

  /** {@code true} quando o conteúdo tem a estrutura de um DBC (cabeçalho DBF + CRC + implode). */
  public static boolean looksLikeDbc(byte[] content) {
    if (content == null || content.length < 40) return false;
    int headerLen = u16(content, 8);
    int recordLen = u16(content, 10);
    long records = u32(content, 4);
    if (headerLen < 33 || headerLen + 6 > content.length) return false;
    long plainDbf = headerLen + records * recordLen;
    if (content.length == plainDbf || content.length == plainDbf + 1) return false; // DBF puro
    int lit = content[headerLen + 4] & 0xff;
    int dict = content[headerLen + 5] & 0xff;
    return lit <= 1 && dict >= 4 && dict <= 6;
  }

  /** Converte o {@code .dbc} em {@code .dbf} (cabeçalho + registros descomprimidos). */
  public static byte[] toDbf(byte[] dbc) {
    if (dbc.length < 12) throw new IllegalArgumentException("DBC truncado");
    int headerLen = u16(dbc, 8);
    if (headerLen < 33 || headerLen + 4 > dbc.length) {
      throw new IllegalArgumentException("DBC com cabeçalho inválido (" + headerLen + " bytes)");
    }
    ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(1024, dbc.length * 8));
    out.write(dbc, 0, headerLen);
    byte[] body = blast(dbc, headerLen + 4);
    out.write(body, 0, body.length);
    return out.toByteArray();
  }

  /** Descomprime um fluxo PKWare DCL implode iniciado em {@code offset}. */
  static byte[] blast(byte[] in, int offset) {
    Bits s = new Bits(in, offset);
    int lit = s.bits(8);
    if (lit > 1) throw new IllegalArgumentException("DBC: cabeçalho implode inválido (literal)");
    int dict = s.bits(8);
    if (dict < 4 || dict > 6) {
      throw new IllegalArgumentException("DBC: tamanho de dicionário inválido (" + dict + ")");
    }
    Output out = new Output(in.length * 8);
    while (true) {
      if (s.bits(1) == 1) {
        int symbol = LENCODE.decode(s);
        int len = BASE[symbol] + s.bits(EXTRA[symbol]);
        if (len == 519) break; // fim do fluxo
        int shift = len == 2 ? 2 : dict;
        int dist = (DISTCODE.decode(s) << shift) + s.bits(shift) + 1;
        if (dist > out.size) throw new IllegalArgumentException("DBC: distância fora da janela");
        out.copy(dist, len);
      } else {
        out.put(lit == 1 ? LITCODE.decode(s) : s.bits(8));
      }
    }
    return out.toArray();
  }

  static int u16(byte[] b, int off) {
    return (b[off] & 0xff) | (b[off + 1] & 0xff) << 8;
  }

  static long u32(byte[] b, int off) {
    return (b[off] & 0xffL)
        | (b[off + 1] & 0xffL) << 8
        | (b[off + 2] & 0xffL) << 16
        | (b[off + 3] & 0xffL) << 24;
  }

  /** Leitor de bits (LSB primeiro), como em {@code blast.c}. */
  private static final class Bits {
    private final byte[] in;
    private int pos;
    private int buf;
    private int cnt;

    Bits(byte[] in, int pos) {
      this.in = in;
      this.pos = pos;
    }

    int bits(int need) {
      int val = buf;
      while (cnt < need) {
        if (pos >= in.length) throw new IllegalArgumentException("DBC: fluxo comprimido truncado");
        val |= (in[pos++] & 0xff) << cnt;
        cnt += 8;
      }
      buf = val >>> need;
      cnt -= need;
      return val & ((1 << need) - 1);
    }
  }

  /** Código de Huffman canônico (bits do código invertidos, como no implode da PKWare). */
  private record Huffman(int[] count, int[] symbol) {

    static Huffman of(int[] rep, int n) {
      int[] length = new int[n];
      int sym = 0;
      for (int r : rep) {
        int left = (r >> 4) + 1;
        int len = r & 15;
        while (left-- > 0) length[sym++] = len;
      }
      int[] count = new int[MAXBITS + 1];
      for (int i = 0; i < sym; i++) count[length[i]]++;
      int[] offs = new int[MAXBITS + 1];
      for (int len = 1; len < MAXBITS; len++) offs[len + 1] = offs[len] + count[len];
      int[] symbol = new int[sym];
      for (int i = 0; i < sym; i++) {
        if (length[i] != 0) symbol[offs[length[i]]++] = i;
      }
      return new Huffman(count, symbol);
    }

    int decode(Bits s) {
      int code = 0;
      int first = 0;
      int index = 0;
      for (int len = 1; len <= MAXBITS; len++) {
        code |= s.bits(1) ^ 1;
        int c = count[len];
        if (code - c < first) return symbol[index + (code - first)];
        index += c;
        first += c;
        first <<= 1;
        code <<= 1;
      }
      throw new IllegalArgumentException("DBC: código de Huffman inválido");
    }
  }

  /** Saída com acesso para trás (janela = todo o conteúdo já produzido). */
  private static final class Output {
    private byte[] data;
    private int size;

    Output(int initial) {
      data = new byte[Math.max(4096, initial)];
    }

    void put(int b) {
      ensure(1);
      data[size++] = (byte) b;
    }

    void copy(int dist, int len) {
      ensure(len);
      int from = size - dist;
      for (int i = 0; i < len; i++) data[size++] = data[from + i];
    }

    private void ensure(int extra) {
      if (size + extra > data.length) {
        data = java.util.Arrays.copyOf(data, Math.max(data.length * 2, size + extra));
      }
    }

    byte[] toArray() {
      return java.util.Arrays.copyOf(data, size);
    }
  }
}
