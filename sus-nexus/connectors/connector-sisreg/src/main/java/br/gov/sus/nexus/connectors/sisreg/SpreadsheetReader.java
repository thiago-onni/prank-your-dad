package br.gov.sus.nexus.connectors.sisreg;

import br.gov.sus.nexus.connectors.sdk.parse.DelimitedParser;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * Lê uma exportação do SISREG (CSV ou XLSX) como lista de linhas {@code cabeçalho → valor} com os
 * cabeçalhos originais. No XLSX a primeira linha não vazia é o cabeçalho; células de data viram
 * {@code dd/MM/yyyy HH:mm:ss}; numéricas inteiras perdem o {@code .0}.
 */
public final class SpreadsheetReader {

  public static final String DATE_PATTERN = "dd/MM/yyyy HH:mm:ss";

  private final Charset charset;
  private final char delimiter;
  private final int sheetIndex;

  public SpreadsheetReader(Charset charset, char delimiter, int sheetIndex) {
    this.charset = charset;
    this.delimiter = delimiter;
    this.sheetIndex = sheetIndex;
  }

  public static boolean isXlsx(String fileName) {
    return fileName != null && fileName.toLowerCase(Locale.ROOT).endsWith(".xlsx");
  }

  public List<Map<String, String>> read(String fileName, byte[] content) {
    return isXlsx(fileName) ? readXlsx(content) : readCsv(content);
  }

  public List<Map<String, String>> readCsv(byte[] content) {
    return new DelimitedParser(delimiter, true, List.of()).parse(content, charset);
  }

  public List<Map<String, String>> readXlsx(byte[] content) {
    List<Map<String, String>> rows = new ArrayList<>();
    DataFormatter formatter = new DataFormatter(new Locale("pt", "BR"));
    try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(content))) {
      if (wb.getNumberOfSheets() <= sheetIndex) return rows;
      Sheet sheet = wb.getSheetAt(sheetIndex);
      List<String> header = null;
      for (Row row : sheet) {
        List<String> values = new ArrayList<>();
        short last = row.getLastCellNum();
        for (int c = 0; c < last; c++) {
          values.add(cellText(row.getCell(c), formatter));
        }
        if (values.stream().allMatch(String::isBlank)) continue;
        if (header == null) {
          header = values.stream().map(String::trim).toList();
          continue;
        }
        Map<String, String> r = new LinkedHashMap<>();
        for (int i = 0; i < header.size(); i++) {
          String name = header.get(i).isBlank() ? "col" + i : header.get(i);
          r.put(name, i < values.size() ? values.get(i) : "");
        }
        rows.add(r);
      }
    } catch (IOException e) {
      throw new UncheckedIOException("XLSX inválido", e);
    }
    return rows;
  }

  static String cellText(Cell cell, DataFormatter formatter) {
    if (cell == null) return "";
    CellType type =
        cell.getCellType() == CellType.FORMULA
            ? cell.getCachedFormulaResultType()
            : cell.getCellType();
    switch (type) {
      case NUMERIC -> {
        if (DateUtil.isCellDateFormatted(cell)) {
          return cell.getLocalDateTimeCellValue()
              .format(java.time.format.DateTimeFormatter.ofPattern(DATE_PATTERN));
        }
        double d = cell.getNumericCellValue();
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return String.valueOf((long) d);
        return java.math.BigDecimal.valueOf(d).toPlainString();
      }
      case BOOLEAN -> {
        return cell.getBooleanCellValue() ? "1" : "0";
      }
      case STRING -> {
        return cell.getStringCellValue().trim();
      }
      case BLANK, ERROR, _NONE -> {
        return "";
      }
      default -> {
        return formatter.formatCellValue(cell).trim();
      }
    }
  }
}
