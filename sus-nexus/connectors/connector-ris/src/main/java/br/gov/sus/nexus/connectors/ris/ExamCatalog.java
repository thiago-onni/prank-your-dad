package br.gov.sus.nexus.connectors.ris;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Catálogo de exames de imagem: código local do RIS (OBR-4-1) → código SIGTAP. YAML:
 *
 * <pre>
 * catalog: ris-exam-catalog
 * version: "1.0.0"
 * codes:
 *   RX-TORAX: "0204030153"
 * </pre>
 *
 * Carregado do classpath ou de {@code file:/caminho.yaml} (catálogo do hospital/serviço). Chaves
 * são comparadas sem distinguir maiúsculas e sem espaços nas pontas.
 */
public final class ExamCatalog {

  private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

  private final String name;
  private final String version;
  private final Map<String, String> codes;

  ExamCatalog(String name, String version, Map<String, String> codes) {
    this.name = name;
    this.version = version;
    Map<String, String> m = new LinkedHashMap<>();
    codes.forEach((k, v) -> m.put(normalize(k), v.trim()));
    this.codes = Collections.unmodifiableMap(m);
  }

  public static ExamCatalog empty() {
    return new ExamCatalog("empty", "0", Map.of());
  }

  public static ExamCatalog load(String path) {
    try {
      JsonNode root;
      if (path.startsWith("file:")) {
        root = YAML.readTree(Files.readString(Path.of(path.substring("file:".length()))));
      } else {
        try (InputStream in = ExamCatalog.class.getClassLoader().getResourceAsStream(path)) {
          if (in == null) throw new IllegalArgumentException("catálogo não encontrado: " + path);
          root = YAML.readTree(in);
        }
      }
      Map<String, String> codes = new LinkedHashMap<>();
      JsonNode c = root.get("codes");
      if (c != null) c.fields().forEachRemaining(e -> codes.put(e.getKey(), e.getValue().asText()));
      return new ExamCatalog(
          root.path("catalog").asText("ris-exam-catalog"),
          root.path("version").asText("1.0.0"),
          codes);
    } catch (IOException e) {
      throw new UncheckedIOException("falha ao ler catálogo " + path, e);
    }
  }

  public Optional<String> sigtap(String localCode) {
    if (localCode == null || localCode.isBlank()) return Optional.empty();
    return Optional.ofNullable(codes.get(normalize(localCode)));
  }

  public int size() {
    return codes.size();
  }

  public String name() {
    return name;
  }

  public String version() {
    return version;
  }

  private static String normalize(String k) {
    return k.trim().toUpperCase(Locale.ROOT);
  }
}
