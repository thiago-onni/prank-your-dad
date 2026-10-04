package br.gov.sus.nexus.connectors.sisreg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Layout das exportações do SISREG: cada {@link Kind} (solicitações, agendamentos, devoluções,
 * oferta) declara o padrão de nome de arquivo, a entidade canônica produzida, a coluna que
 * identifica o registro e, para cada coluna canônica, os cabeçalhos aceitos (aliases). Cabeçalhos
 * são comparados normalizados (minúsculas, sem acento, espaços e pontuação → {@code _}), o que
 * tolera variações entre versões de relatório. Aliases funcionam como "coalesce": o primeiro com
 * valor preenchido vence.
 *
 * <pre>
 * version: "1.0.0"
 * kinds:
 *   - name: solicitacoes
 *     entity: regulation_request
 *     file_pattern: "(?i).*solicita.*\\.(csv|xlsx)"
 *     id_column: codigo_solicitacao
 *     version_column: data_atualizacao
 *     defaults: { situacao: PENDENTE }
 *     columns:
 *       codigo_solicitacao: [codigo_solicitacao, cod_solicitacao, chave]
 * </pre>
 */
public final class SisregLayout {

  private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

  private final String version;
  private final List<Kind> kinds;

  public SisregLayout(String version, List<Kind> kinds) {
    this.version = version;
    this.kinds = List.copyOf(kinds);
  }

  /** Um tipo de arquivo exportado. */
  public record Kind(
      String name,
      String entity,
      Pattern filePattern,
      String idColumn,
      String versionColumn,
      Map<String, String> defaults,
      Map<String, List<String>> columns) {

    public Kind {
      defaults = defaults == null ? Map.of() : Map.copyOf(defaults);
      columns = columns == null ? Map.of() : Map.copyOf(columns);
    }

    public boolean matches(String fileName) {
      return filePattern.matcher(fileName).matches();
    }

    /** Converte uma linha com cabeçalhos originais em mapa de colunas canônicas. */
    public Map<String, String> canonicalize(Map<String, String> row) {
      Map<String, String> normalized = new LinkedHashMap<>();
      row.forEach((k, v) -> normalized.put(normalize(k), v == null ? "" : v.trim()));
      Map<String, String> out = new LinkedHashMap<>();
      for (Map.Entry<String, List<String>> e : columns.entrySet()) {
        String value = "";
        for (String alias : e.getValue()) {
          String v = normalized.get(normalize(alias));
          if (v != null && !v.isBlank()) {
            value = v;
            break;
          }
        }
        if (value.isBlank() && defaults.containsKey(e.getKey())) value = defaults.get(e.getKey());
        out.put(e.getKey(), value);
      }
      defaults.forEach((k, v) -> out.putIfAbsent(k, v));
      return out;
    }
  }

  public static SisregLayout load(String location) {
    try {
      if (location.startsWith("classpath:")) {
        String res = location.substring("classpath:".length());
        try (InputStream in = SisregLayout.class.getClassLoader().getResourceAsStream(res)) {
          if (in == null) throw new IllegalArgumentException("layout não encontrado: " + res);
          return parse(YAML.readTree(in));
        }
      }
      return parse(YAML.readTree(Files.readString(Path.of(location))));
    } catch (IOException e) {
      throw new UncheckedIOException("falha ao ler layout SISREG em " + location, e);
    }
  }

  static SisregLayout parse(JsonNode root) {
    List<Kind> kinds = new ArrayList<>();
    for (JsonNode k : root.withArray("kinds")) {
      Map<String, String> defaults = new LinkedHashMap<>();
      k.path("defaults")
          .fields()
          .forEachRemaining(e -> defaults.put(e.getKey(), e.getValue().asText()));
      Map<String, List<String>> columns = new LinkedHashMap<>();
      k.path("columns")
          .fields()
          .forEachRemaining(
              e -> {
                List<String> aliases = new ArrayList<>();
                if (e.getValue().isArray()) e.getValue().forEach(a -> aliases.add(a.asText()));
                else aliases.add(e.getValue().asText());
                columns.put(e.getKey(), aliases);
              });
      kinds.add(
          new Kind(
              k.get("name").asText(),
              k.get("entity").asText(),
              Pattern.compile(k.get("file_pattern").asText()),
              k.path("id_column").asText(null),
              k.path("version_column").asText(null),
              defaults,
              columns));
    }
    return new SisregLayout(root.path("version").asText("1.0.0"), kinds);
  }

  public String version() {
    return version;
  }

  public List<Kind> kinds() {
    return kinds;
  }

  public Optional<Kind> forFile(String fileName) {
    return kinds.stream().filter(k -> k.matches(fileName)).findFirst();
  }

  public Kind require(String name) {
    return kinds.stream()
        .filter(k -> k.name().equals(name))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("kind de layout desconhecido: " + name));
  }

  /** Normaliza um cabeçalho: minúsculas, sem acentos, não alfanuméricos → {@code _}. */
  public static String normalize(String header) {
    if (header == null) return "";
    String n = Normalizer.normalize(header, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
    n = n.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
    return n.replaceAll("^_+|_+$", "");
  }
}
