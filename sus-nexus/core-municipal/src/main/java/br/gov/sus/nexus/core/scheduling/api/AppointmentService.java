package br.gov.sus.nexus.core.scheduling.api;

import br.gov.sus.nexus.core.platform.pagination.Page;
import java.time.OffsetDateTime;
import java.util.Optional;

/** API pública do módulo scheduling (porta única: REST e consumidor de ingestão). */
public interface AppointmentService {

  /**
   * Cria ou atualiza por vínculo de origem (tenant, source.system, source_record_id); resolve o
   * cidadão por {@code municipal_citizen_id} ou identificador (via MPI); grava histórico de status;
   * publica {@code sus.schedule.appointment.*}; detecta duplicidade (AGE-004) e, em {@code noshow},
   * abre tarefa {@code no_show_recovery} (AGE-006).
   */
  AppointmentResult register(AppointmentRegistration registration);

  AppointmentDto get(String appointmentId);

  Page<AppointmentDto> list(
      String citizenId,
      String cnes,
      AppointmentStatus status,
      OffsetDateTime from,
      OffsetDateTime to,
      String cursor,
      Integer limit);

  Page<AppointmentDuplicateDto> listDuplicates(Integer windowHours, String cursor, Integer limit);

  /** Próximo agendamento ativo do cidadão (JOR-008). */
  Optional<OffsetDateTime> nextAppointmentAt(String citizenId);

  /**
   * Agendamento vinculado ao registro de origem ({@code source_record_id}); quando {@code
   * sourceSystem} é informado, prefere o vínculo daquele sistema e cai para qualquer sistema.
   */
  Optional<AppointmentDto> findBySourceRecord(String sourceSystem, String sourceRecordId);
}
