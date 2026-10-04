package br.gov.sus.nexus.core.tasks.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Transição de estado (OpenAPI {@code POST /tasks/{id}/transition}). */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record TaskTransition(
    @NotNull Action action,
    @Valid Assignee assignee,
    @Size(max = 500) String outcome,
    @Size(max = 500) String reason) {

  /** Ações da máquina de estados. */
  public enum Action {
    ASSIGN,
    START,
    COMPLETE,
    CANCEL,
    ESCALATE;

    @JsonValue
    public String wire() {
      return name().toLowerCase();
    }

    @JsonCreator
    public static Action fromWire(String v) {
      return valueOf(v.trim().toUpperCase());
    }
  }

  public static TaskTransition complete(String outcome, String reason) {
    return new TaskTransition(Action.COMPLETE, null, outcome, reason);
  }

  public static TaskTransition cancel(String reason) {
    return new TaskTransition(Action.CANCEL, null, null, reason);
  }

  public static TaskTransition escalate(Assignee to, String reason) {
    return new TaskTransition(Action.ESCALATE, to, null, reason);
  }
}
