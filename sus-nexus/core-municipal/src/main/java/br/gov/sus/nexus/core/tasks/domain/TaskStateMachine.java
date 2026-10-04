package br.gov.sus.nexus.core.tasks.domain;

import br.gov.sus.nexus.core.tasks.api.TaskStatus;
import br.gov.sus.nexus.core.tasks.api.TaskTransition;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Máquina de estados: open → assigned → in_progress → completed | cancelled; escalated a partir de
 * qualquer estado aberto; de escalated pode-se atribuir, iniciar, concluir ou cancelar.
 */
public final class TaskStateMachine {

  private static final Map<TaskTransition.Action, Set<TaskStatus>> FROM =
      Map.of(
          TaskTransition.Action.ASSIGN,
          EnumSet.of(TaskStatus.OPEN, TaskStatus.ASSIGNED, TaskStatus.ESCALATED),
          TaskTransition.Action.START,
          EnumSet.of(TaskStatus.OPEN, TaskStatus.ASSIGNED, TaskStatus.ESCALATED),
          TaskTransition.Action.COMPLETE,
          EnumSet.of(
              TaskStatus.OPEN, TaskStatus.ASSIGNED, TaskStatus.IN_PROGRESS, TaskStatus.ESCALATED),
          TaskTransition.Action.CANCEL,
          EnumSet.of(
              TaskStatus.OPEN, TaskStatus.ASSIGNED, TaskStatus.IN_PROGRESS, TaskStatus.ESCALATED),
          TaskTransition.Action.ESCALATE,
          EnumSet.of(TaskStatus.OPEN, TaskStatus.ASSIGNED, TaskStatus.IN_PROGRESS));

  private TaskStateMachine() {}

  public static boolean canApply(TaskStatus current, TaskTransition.Action action) {
    return FROM.getOrDefault(action, Set.of()).contains(current);
  }

  public static TaskStatus next(TaskTransition.Action action) {
    return switch (action) {
      case ASSIGN -> TaskStatus.ASSIGNED;
      case START -> TaskStatus.IN_PROGRESS;
      case COMPLETE -> TaskStatus.COMPLETED;
      case CANCEL -> TaskStatus.CANCELLED;
      case ESCALATE -> TaskStatus.ESCALATED;
    };
  }

  /** Nome do evento {@code sus.task.<ação>} para a transição (START não publica evento). */
  public static String eventAction(TaskTransition.Action action) {
    return switch (action) {
      case ASSIGN -> "assigned";
      case START -> null;
      case COMPLETE -> "completed";
      case CANCEL -> "cancelled";
      case ESCALATE -> "escalated";
    };
  }
}
