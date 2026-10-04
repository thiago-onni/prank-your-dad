package br.gov.sus.nexus.core.tasks.api;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.Pattern;

/** Origem da tarefa (OpenAPI {@code Task.origin}). */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record TaskOrigin(
    @Pattern(regexp = "workflow|agent|user|rule|connector") String kind,
    String id,
    String version) {

  public static TaskOrigin rule(String id, String version) {
    return new TaskOrigin("rule", id, version);
  }

  public static TaskOrigin workflow(String id, String version) {
    return new TaskOrigin("workflow", id, version);
  }
}
