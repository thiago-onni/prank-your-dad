package br.gov.sus.nexus.core.scheduling.infrastructure;

import br.gov.sus.nexus.core.scheduling.domain.Appointment;
import br.gov.sus.nexus.core.scheduling.domain.AppointmentDuplicate;
import br.gov.sus.nexus.core.scheduling.domain.AppointmentSourceLink;
import br.gov.sus.nexus.core.scheduling.domain.AppointmentStatusHistory;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Repositórios Panache do módulo scheduling (RLS garante o tenant). */
public final class SchedulingRepositories {

  static final String ACTIVE = "('proposed','booked','confirmed','arrived','waitlist')";

  private SchedulingRepositories() {}

  @ApplicationScoped
  public static class Appointments implements PanacheRepositoryBase<Appointment, String> {

    /** Outros agendamentos ativos do cidadão para o mesmo serviço dentro da janela. */
    public List<Appointment> duplicatesOf(
        String citizenId, String serviceCode, Instant start, int windowHours, String excludeId) {
      Instant from = start.minusSeconds(windowHours * 3600L);
      Instant to = start.plusSeconds(windowHours * 3600L);
      return list(
          "citizenId = ?1 and serviceCode = ?2 and id <> ?3 and status in "
              + ACTIVE
              + " and scheduledStart between ?4 and ?5 order by scheduledStart",
          citizenId,
          serviceCode,
          excludeId,
          from,
          to);
    }

    public Optional<Instant> nextActive(String citizenId, Instant now) {
      return find(
              "citizenId = ?1 and status in "
                  + ACTIVE
                  + " and scheduledStart >= ?2 order by scheduledStart",
              citizenId,
              now)
          .firstResultOptional()
          .map(a -> a.scheduledStart);
    }

    public List<Appointment> list(
        String citizenId,
        String cnes,
        String status,
        Instant from,
        Instant to,
        String beforeId,
        int limitPlusOne) {
      StringBuilder q = new StringBuilder("1 = 1");
      List<Object> params = new ArrayList<>();
      if (citizenId != null) {
        params.add(citizenId);
        q.append(" and citizenId = ?").append(params.size());
      }
      if (cnes != null) {
        params.add(cnes);
        q.append(" and healthUnitCnes = ?").append(params.size());
      }
      if (status != null) {
        params.add(status);
        q.append(" and status = ?").append(params.size());
      }
      if (from != null) {
        params.add(from);
        q.append(" and scheduledStart >= ?").append(params.size());
      }
      if (to != null) {
        params.add(to);
        q.append(" and scheduledStart <= ?").append(params.size());
      }
      if (beforeId != null) {
        params.add(beforeId);
        q.append(" and id < ?").append(params.size());
      }
      q.append(" order by id desc");
      return find(q.toString(), params.toArray()).page(0, limitPlusOne).list();
    }
  }

  @ApplicationScoped
  public static class History implements PanacheRepositoryBase<AppointmentStatusHistory, String> {
    public List<AppointmentStatusHistory> byAppointment(String appointmentId) {
      return list("appointmentId = ?1 order by occurredAt, recordedAt", appointmentId);
    }
  }

  @ApplicationScoped
  public static class SourceLinks implements PanacheRepositoryBase<AppointmentSourceLink, String> {
    public Optional<AppointmentSourceLink> findBySource(
        String tenantId, String sourceSystem, String sourceRecordId) {
      return find(
              "tenantId = ?1 and sourceSystem = ?2 and sourceRecordId = ?3",
              tenantId,
              sourceSystem,
              sourceRecordId)
          .firstResultOptional();
    }

    public Optional<AppointmentSourceLink> findAnySystem(String tenantId, String sourceRecordId) {
      return find(
              "tenantId = ?1 and sourceRecordId = ?2 order by createdAt", tenantId, sourceRecordId)
          .firstResultOptional();
    }
  }

  @ApplicationScoped
  public static class Duplicates implements PanacheRepositoryBase<AppointmentDuplicate, String> {
    public List<AppointmentDuplicate> list(Integer windowHours, String beforeId, int limitPlusOne) {
      StringBuilder q = new StringBuilder("resolvedAt is null");
      List<Object> params = new ArrayList<>();
      if (windowHours != null) {
        params.add(windowHours);
        q.append(" and windowHours <= ?").append(params.size());
      }
      if (beforeId != null) {
        params.add(beforeId);
        q.append(" and id < ?").append(params.size());
      }
      q.append(" order by id desc");
      return find(q.toString(), params.toArray()).page(0, limitPlusOne).list();
    }

    public List<AppointmentDuplicate> openByCitizen(String citizenId) {
      return list("citizenId = ?1 and resolvedAt is null", citizenId);
    }
  }
}
