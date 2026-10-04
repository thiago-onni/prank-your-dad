package br.gov.sus.nexus.connectors.rnds;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Carrega {@link ModelMapping} do classpath (padrão) ou de um arquivo ({@code file:...}). */
public final class ModelMappingLoader {

  private static final ObjectMapper YAML =
      new ObjectMapper(new YAMLFactory())
          .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
          .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

  private ModelMappingLoader() {}

  public static ModelMapping load(String location) {
    try (InputStream in = open(location)) {
      return YAML.readValue(in, ModelMapping.class).validate();
    } catch (IOException e) {
      throw new UncheckedIOException("mapeamento RNDS inválido: " + location, e);
    }
  }

  private static InputStream open(String location) throws IOException {
    if (location.startsWith("file:")) {
      return Files.newInputStream(Path.of(location.substring("file:".length())));
    }
    InputStream in =
        Thread.currentThread()
            .getContextClassLoader()
            .getResourceAsStream(location.startsWith("/") ? location.substring(1) : location);
    if (in == null) throw new IOException("mapeamento não encontrado no classpath: " + location);
    return in;
  }
}
