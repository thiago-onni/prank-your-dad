package br.gov.sus.nexus.connectors.sdk.retry;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * DLQ em arquivos JSON ({@code <dir>/<dlq_id>.json}); a referência ao bruto aponta para a raw zone.
 */
public class FileDeadLetterSink implements DeadLetterSink {

  private final Path dir;
  private final ObjectMapper mapper;

  public FileDeadLetterSink(Path dir, ObjectMapper mapper) {
    this.dir = dir;
    this.mapper = mapper;
  }

  @Override
  public void accept(DeadLetter dl) {
    try {
      Files.createDirectories(dir);
      mapper
          .writerWithDefaultPrettyPrinter()
          .writeValue(dir.resolve(dl.id() + ".json").toFile(), dl);
    } catch (IOException e) {
      throw new UncheckedIOException("falha ao gravar dead letter " + dl.id(), e);
    }
  }

  @Override
  public List<DeadLetter> open(int limit) {
    if (!Files.isDirectory(dir)) return List.of();
    try (Stream<Path> files = Files.list(dir)) {
      List<DeadLetter> out = new ArrayList<>();
      for (Path p :
          files
              .filter(f -> f.toString().endsWith(".json"))
              .sorted(Comparator.naturalOrder())
              .limit(limit)
              .toList()) {
        out.add(mapper.readValue(p.toFile(), DeadLetter.class));
      }
      return out;
    } catch (IOException e) {
      throw new UncheckedIOException("falha ao listar DLQ em " + dir, e);
    }
  }

  public Path dir() {
    return dir;
  }
}
