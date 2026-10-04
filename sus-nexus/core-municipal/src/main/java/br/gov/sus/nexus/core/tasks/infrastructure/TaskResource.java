package br.gov.sus.nexus.core.tasks.infrastructure;

import br.gov.sus.nexus.core.platform.pagination.Page;
import br.gov.sus.nexus.core.platform.security.Roles;
import br.gov.sus.nexus.core.tasks.api.TaskCommands;
import br.gov.sus.nexus.core.tasks.api.TaskCreate;
import br.gov.sus.nexus.core.tasks.api.TaskDto;
import br.gov.sus.nexus.core.tasks.api.TaskQueries;
import br.gov.sus.nexus.core.tasks.api.TaskStatus;
import br.gov.sus.nexus.core.tasks.api.TaskTransition;
import br.gov.sus.nexus.core.tasks.api.TaskType;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/** {@code /api/v1/tasks} — listagem, criação, detalhe e transição de estado. */
@Path("/api/v1/tasks")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({
  Roles.PROFISSIONAL_APS,
  Roles.ACS,
  Roles.REGULADOR,
  Roles.AGENDADOR,
  Roles.PROFISSIONAL_HOSPITALAR,
  Roles.GESTOR,
  Roles.CADASTRO_MESTRE,
  Roles.AGENTE_IA,
  Roles.ADMIN_MUNICIPAL
})
public class TaskResource {

  @Inject TaskCommands commands;
  @Inject TaskQueries queries;

  @GET
  public Page<TaskDto> list(
      @QueryParam("status") String status,
      @QueryParam("task_type") String taskType,
      @QueryParam("assignee_kind") String assigneeKind,
      @QueryParam("assignee_id") String assigneeId,
      @QueryParam("citizen_id") String citizenId,
      @QueryParam("overdue") Boolean overdue,
      @QueryParam("cursor") String cursor,
      @QueryParam("limit") Integer limit) {
    TaskStatus st = status == null || status.isBlank() ? null : TaskStatus.fromWire(status);
    TaskType tt = taskType == null || taskType.isBlank() ? null : TaskType.fromWire(taskType);
    return queries.list(st, tt, assigneeKind, assigneeId, citizenId, overdue, cursor, limit);
  }

  @POST
  public Response create(@Valid TaskCreate create) {
    return Response.status(201).entity(commands.create(create, null)).build();
  }

  @GET
  @Path("/{taskId}")
  public TaskDto get(@PathParam("taskId") String taskId) {
    return queries.get(taskId);
  }

  @POST
  @Path("/{taskId}/transition")
  public TaskDto transition(@PathParam("taskId") String taskId, @Valid TaskTransition transition) {
    return commands.transition(taskId, transition);
  }
}
