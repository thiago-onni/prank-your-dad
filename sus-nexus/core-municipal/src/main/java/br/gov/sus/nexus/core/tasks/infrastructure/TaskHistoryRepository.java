package br.gov.sus.nexus.core.tasks.infrastructure;

import br.gov.sus.nexus.core.tasks.domain.TaskHistory;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

/** Repositório do histórico de tarefas (append-only). */
@ApplicationScoped
public class TaskHistoryRepository implements PanacheRepositoryBase<TaskHistory, String> {}
