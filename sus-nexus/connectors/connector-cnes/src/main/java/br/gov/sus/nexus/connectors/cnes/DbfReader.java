package br.gov.sus.nexus.connectors.cnes;

import com.linuxense.javadbf.DBFField;
import com.linuxense.javadbf.DBFReader;
import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Lê um DBF (dBase) em memória para linhas {@code coluna → texto}. */
public final class DbfReader {

  private DbfReader() {}

  public static List<Map<String, String>> read(byte[] content, Charset charset) {
    List<Map<String, String>> rows = new ArrayList<>();
    try (DBFReader reader = new DBFReader(new ByteArrayInputStream(content), charset)) {
      int n = reader.getFieldCount();
      String[] names = new String[n];
      for (int i = 0; i < n; i++) {
        DBFField f = reader.getField(i);
        names[i] = f.getName().toUpperCase();
      }
      Object[] record;
      while ((record = reader.nextRecord()) != null) {
        Map<String, String> row = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
          row.put(names[i], toText(record[i]));
        }
        rows.add(row);
      }
    }
    return rows;
  }

  private static String toText(Object v) {
    if (v == null) return "";
    if (v instanceof Date d) return String.format("%1$tY%1$tm%1$td", d);
    if (v instanceof Number num) {
      double d = num.doubleValue();
      return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }
    return v.toString().trim();
  }
}
