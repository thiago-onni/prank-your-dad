package br.gov.sus.nexus.core.tasks.api;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Responsável pela tarefa (OpenAPI {@code Assignee}). */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record Assignee(
    @NotBlank @Pattern(regexp = "user|team|health_unit|queue") String kind, @NotBlank String id) {

  public static Assignee team(String ine) {
    return new Assignee("team", ine);
  }

  public static Assignee healthUnit(String cnes) {
    return new Assignee("health_unit", cnes);
  }

  public static Assignee queue(String name) {
    return new Assignee("queue", name);
  }

  public static Assignee user(String id) {
    return new Assignee("user", id);
  }
}
