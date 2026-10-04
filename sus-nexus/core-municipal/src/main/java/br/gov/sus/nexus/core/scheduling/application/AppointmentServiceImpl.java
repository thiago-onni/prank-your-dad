package br.gov.sus.nexus.core.scheduling.application;

import br.gov.sus.nexus.core.identity.api.CitizenDetail;
import br.gov.sus.nexus.core.identity.api.CitizenService;
import br.gov.sus.nexus.core.identity.api.CitizenSummary;
import br.gov.sus.nexus.core.platform.errors.DomainValidationException;
import br.gov.sus.nexus.core.platform.errors.NotFoundException;
import br.gov.sus.nexus.core.platform.ids.Ulid;
import br.gov.sus.nexus.core.platform.pagination.Cursor;
import br.gov.sus.nexus.core.platform.pagination.Page;
import br.gov.sus.nexus.core.platform.security.CurrentActor;
import br.gov.sus.nexus.core.platform.tenant.TenantContext;
import br.gov.sus.nexus.core.platform.tenant.TenantTransactional;
import br.gov.sus.nexus.core.scheduling.api.AppointmentDto;
import br.gov.sus.nexus.core.scheduling.api.AppointmentDuplicateDto;
import br.gov.sus.nexus.core.scheduling.api.AppointmentKind;
import br.gov.sus.nexus.core.scheduling.api.AppointmentRegistration;
import br.gov.sus.nexus.core.scheduling.api.AppointmentResult;
import br.gov.sus.nexus.core.scheduling.api.AppointmentService;
import br.gov.sus.nexus.core.scheduling.api.AppointmentStatus;
import br.gov.sus.nexus.core.scheduling.domain.Appointment;
import br.gov.sus.nexus.core.scheduling.domain.AppointmentDuplicate;
import br.gov.sus.nexus.core.scheduling.domain.AppointmentSourceLink;
import br.gov.sus.nexus.core.scheduling.domain.AppointmentStatusHistory;
import br.gov.sus.nexus.core.scheduling.infrastructure.SchedulingRepositories;
import br.gov.sus.nexus.core.sharedkernel.Cnes;
import br.gov.sus.nexus.core.tasks.api.Assignee;
import br.gov.sus.nexus.core.tasks.api.TaskCommands;
import br.gov.sus.nexus.core.tasks.api.TaskCreate;
import br.gov.sus.nexus.core.tasks.api.TaskOrigin;
import br.gov.sus.nexus.core.tasks.api.TaskPriority;
import br.gov.sus.nexus.core.tasks.api.TaskQueries;
import br.gov.sus.nexus.core.tasks.api.TaskType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Agenda consolidada: registro por vínculo de origem, histórico, eventos, duplicidade e no-show.
 */
@ApplicationScoped
public class AppointmentServiceImpl implements AppointmentService {

  private static final Logger LOG = Logger.getLogger(AppointmentServiceImpl.class);
  public static final String NO_SHOW_RULE = "appointment.no_show";
  static final String NO_SHOW_RULE_VERSION = NO_SHOW_RULE + "/1.0";
  static final Set<String> CODE_SYSTEMS = Set.of("SIGTAP", "LOCAL");

  @Inject SchedulingRepositories.Appointments appointments;
  @Inject SchedulingRepositories.History history;
  @Inject SchedulingRepositories.SourceLinks links;
  @Inject SchedulingRepositories.Duplicates duplicates;
  @Inject CitizenService citizens;
  @Inject TaskCommands taskCommands;
  @Inject TaskQueries taskQueries;
  @Inject AppointmentEvents events;
  @Inject TenantContext tenantContext;
  @Inject CurrentActor currentActor;

  @ConfigProperty(name = "sus.scheduling.duplicate-window-hours", defaultValue = "72")
  int duplicateWindowHours;

  @Override
  @TenantTransactional
  public AppointmentResult register(AppointmentRegistration reg) {
    validate(reg);
    String tenant = tenantContext.require();
    CitizenDetail citizen = resolveCitizen(reg.citizenRef());
    Instant now = Instant.now();
    OffsetDateTime occurredAt =
        reg.occurredAt() == null ? OffsetDateTime.now(ZoneOffset.UTC) : reg.occurredAt();

    Optional<AppointmentSourceLink> link =
        links.findBySource(tenant, reg.source().system(), reg.source().sourceRecordId());
    if (link.isEmpty()) {
      Appointment a = new Appointment();
      a.id = Ulid.generate(Ulid.APPOINTMENT);
      a.tenantId = tenant;
      a.citizenId = citizen.id();
      a.sourceSystem = reg.source().system();
      a.sourceRecordId = reg.source().sourceRecordId();
      a.sourceRecordVersion = reg.source().sourceRecordVersion();
      copy(reg, a);
      a.createdAt = now;
      a.updatedAt = now;
      appointments.persist(a);

      AppointmentSourceLink l = new AppointmentSourceLink();
      l.id = Ulid.generate(Ulid.SOURCE_LINK);
      l.tenantId = tenant;
      l.appointmentId = a.id;
      l.sourceSystem = reg.source().system();
      l.connector = reg.source().connector();
      l.sourceRecordId = reg.source().sourceRecordId();
      l.sourceRecordVersion = reg.source().sourceRecordVersion();
      links.persist(l);

      recordHistory(
          a, null, reg.cancellationReason(), occurredAt.toInstant(), reg.source().system());
      String createdId = events.publish("created", a, null, reg.source(), occurredAt, null);
      String causation = createdId;
      String terminal = terminalAction(AppointmentStatus.fromWire(a.status));
      if (terminal != null) {
        causation = events.publish(terminal, a, null, reg.source(), occurredAt, createdId);
      }
      afterStatus(a, null, citizen, reg, occurredAt, causation);
      return new AppointmentResult(toDto(a, true), true);
    }

    Appointment a = appointments.findById(link.get().appointmentId);
    if (a == null) {
      throw new NotFoundException("agendamento", link.get().appointmentId);
    }
    String previousStatus = a.status;
    Instant previousStart = a.scheduledStart;
    boolean changed = copy(reg, a);
    if (!Objects.equals(a.citizenId, citizen.id())) {
      a.citizenId = citizen.id();
      changed = true;
    }
    if (!changed) {
      return new AppointmentResult(toDto(a, true), false);
    }
    a.sourceRecordVersion = reg.source().sourceRecordVersion();
    a.updatedAt = now;
    link.get().sourceRecordVersion = reg.source().sourceRecordVersion();
    link.get().updatedAt = now;

    boolean statusChanged = !previousStatus.equals(a.status);
    boolean rescheduled = !previousStart.equals(a.scheduledStart);
    if (statusChanged) {
      recordHistory(
          a,
          previousStatus,
          reg.cancellationReason(),
          occurredAt.toInstant(),
          reg.source().system());
    }
    String action = rescheduled ? "rescheduled" : statusChanged ? actionFor(a.status) : null;
    String eventId = null;
    if (action != null) {
      eventId = events.publish(action, a, previousStatus, reg.source(), occurredAt, null);
      if (rescheduled
          && statusChanged
          && terminalAction(AppointmentStatus.fromWire(a.status)) != null) {
        eventId =
            events.publish(
                actionFor(a.status), a, previousStatus, reg.source(), occurredAt, eventId);
      }
    }
    afterStatus(a, previousStatus, citizen, reg, occurredAt, eventId);
    return new AppointmentResult(toDto(a, true), false);
  }

  /** Efeitos derivados do status: duplicidade (AGE-004) e recuperação de falta (AGE-006). */
  private void afterStatus(
      Appointment a,
      String previousStatus,
      CitizenDetail citizen,
      AppointmentRegistration reg,
      OffsetDateTime occurredAt,
      String causationId) {
    AppointmentStatus status = AppointmentStatus.fromWire(a.status);
    if (status.isActive() && a.serviceCode != null) {
      detectDuplicates(a, reg, occurredAt);
    }
    if (status == AppointmentStatus.NOSHOW
        && !AppointmentStatus.NOSHOW.wire().equals(previousStatus)) {
      openNoShowRecovery(a, citizen, causationId);
    }
  }

  private void detectDuplicates(Appointment a, AppointmentRegistration reg, OffsetDateTime at) {
    List<Appointment> others =
        appointments.duplicatesOf(
            a.citizenId, a.serviceCode, a.scheduledStart, duplicateWindowHours, a.id);
    if (others.isEmpty()) {
      return;
    }
    Set<String> ids = new LinkedHashSet<>();
    ids.add(a.id);
    others.forEach(o -> ids.add(o.id));
    boolean known =
        duplicates.openByCitizen(a.citizenId).stream()
            .anyMatch(d -> d.appointmentIds.containsAll(ids));
    if (known) {
      return;
    }
    AppointmentDuplicate d = new AppointmentDuplicate();
    d.id = Ulid.generate(Ulid.APPOINTMENT_DUPLICATE);
    d.tenantId = a.tenantId;
    d.citizenId = a.citizenId;
    d.serviceCode = a.serviceCode;
    d.appointmentIds = List.copyOf(ids);
    d.windowHours = duplicateWindowHours;
    duplicates.persist(d);
    LOG.infof(
        "AGE-004 duplicidade: %d agendamento(s) do mesmo serviço em %dh (%s)",
        ids.size(), duplicateWindowHours, d.id);
    events.publish("duplicate_detected", a, null, reg.source(), at, null);
  }

  private void openNoShowRecovery(Appointment a, CitizenDetail citizen, String causationId) {
    if (taskQueries.findOpenByOrigin("rule", a.id).isPresent()) {
      return;
    }
    Assignee assignee;
    if (citizen.teamIne() != null && !citizen.teamIne().isBlank()) {
      assignee = Assignee.team(citizen.teamIne());
    } else if (Cnes.isValid(citizen.healthUnitCnes())) {
      assignee = Assignee.healthUnit(citizen.healthUnitCnes());
    } else if (Cnes.isValid(a.healthUnitCnes)) {
      assignee = Assignee.healthUnit(a.healthUnitCnes);
    } else {
      assignee = Assignee.queue("busca_ativa");
    }
    taskCommands.create(
        new TaskCreate(
            TaskType.NO_SHOW_RECOVERY,
            TaskPriority.HIGH,
            "Falta em agendamento — contatar e reagendar",
            "Cidadão faltou ao agendamento "
                + a.id
                + (a.serviceCode == null ? "" : " (" + a.serviceCode + ")")
                + " em "
                + a.scheduledStart.atOffset(ZoneOffset.UTC)
                + ". Realizar busca ativa e reagendar.",
            a.citizenId,
            assignee,
            null,
            null,
            new TaskOrigin("rule", a.id, NO_SHOW_RULE_VERSION)),
        causationId);
  }

  // ---------------------------------------------------------------------

  @Override
  @TenantTransactional
  public AppointmentDto get(String appointmentId) {
    Appointment a = appointments.findById(appointmentId);
    if (a == null) {
      throw new NotFoundException("agendamento", appointmentId);
    }
    return toDto(a, true);
  }

  @Override
  @TenantTransactional
  public Page<AppointmentDto> list(
      String citizenId,
      String cnes,
      AppointmentStatus status,
      OffsetDateTime from,
      OffsetDateTime to,
      String cursor,
      Integer limit) {
    int size = Cursor.limit(limit);
    List<AppointmentDto> rows =
        appointments
            .list(
                blank(citizenId),
                blank(cnes),
                status == null ? null : status.wire(),
                from == null ? null : from.toInstant(),
                to == null ? null : to.toInstant(),
                Cursor.decode(cursor).orElse(null),
                size + 1)
            .stream()
            .map(a -> toDto(a, false))
            .toList();
    return Page.of(rows, size, AppointmentDto::id);
  }

  @Override
  @TenantTransactional
  public Page<AppointmentDuplicateDto> listDuplicates(
      Integer windowHours, String cursor, Integer limit) {
    int size = Cursor.limit(limit);
    List<AppointmentDuplicateDto> rows =
        duplicates.list(windowHours, Cursor.decode(cursor).orElse(null), size + 1).stream()
            .map(this::toDto)
            .toList();
    return Page.of(rows, size, AppointmentDuplicateDto::id);
  }

  @Override
  @TenantTransactional
  public Optional<OffsetDateTime> nextAppointmentAt(String citizenId) {
    return appointments.nextActive(citizenId, Instant.now()).map(i -> i.atOffset(ZoneOffset.UTC));
  }

  @Override
  @TenantTransactional
  public Optional<AppointmentDto> findBySourceRecord(String sourceSystem, String sourceRecordId) {
    if (sourceRecordId == null || sourceRecordId.isBlank()) {
      return Optional.empty();
    }
    String tenant = tenantContext.require();
    Optional<AppointmentSourceLink> link =
        sourceSystem == null
            ? Optional.empty()
            : links.findBySource(tenant, sourceSystem, sourceRecordId.trim());
    if (link.isEmpty()) {
      link = links.findAnySystem(tenant, sourceRecordId.trim());
    }
    return link.map(l -> appointments.findById(l.appointmentId))
        .filter(Objects::nonNull)
        .map(a -> toDto(a, false));
  }

  // ---------------------------------------------------------------------

  private CitizenDetail resolveCitizen(AppointmentRegistration.CitizenRef ref) {
    if (ref.municipalCitizenId() != null && !ref.municipalCitizenId().isBlank()) {
      CitizenDetail c;
      try {
        c = citizens.get(ref.municipalCitizenId().trim());
      } catch (NotFoundException e) {
        throw DomainValidationException.field(
            "citizen_ref.municipal_citizen_id", "cidadão não encontrado");
      }
      int hops = 0;
      while (c.mergedIntoId() != null && hops++ < 10) {
        c = citizens.get(c.mergedIntoId());
      }
      return c;
    }
    if (ref.identifierSystem() == null
        || ref.identifierValue() == null
        || ref.identifierValue().isBlank()) {
      throw DomainValidationException.field(
          "citizen_ref", "informe municipal_citizen_id ou identifier_system + identifier_value");
    }
    Page<CitizenSummary> found =
        citizens.search(
            null, ref.identifierSystem().name() + "|" + ref.identifierValue(), null, null, null, 1);
    if (found.items().isEmpty()) {
      throw DomainValidationException.field(
          "citizen_ref", "cidadão não localizado pelo identificador informado");
    }
    return resolveCitizen(
        new AppointmentRegistration.CitizenRef(found.items().get(0).id(), null, null));
  }

  private static void validate(AppointmentRegistration reg) {
    if (reg.healthUnitCnes() != null
        && !reg.healthUnitCnes().isBlank()
        && !Cnes.isValid(reg.healthUnitCnes())) {
      throw DomainValidationException.field("health_unit_cnes", "CNES deve ter 7 dígitos");
    }
    if (reg.codeSystem() != null && !CODE_SYSTEMS.contains(reg.codeSystem().toUpperCase())) {
      throw DomainValidationException.field("code_system", "valores: SIGTAP|LOCAL");
    }
    if (reg.scheduledEnd() != null && reg.scheduledEnd().isBefore(reg.scheduledStart())) {
      throw DomainValidationException.field("scheduled_end", "anterior a scheduled_start");
    }
  }

  /** Copia os campos do registro para a entidade; retorna se algo mudou. */
  private static boolean copy(AppointmentRegistration reg, Appointment a) {
    boolean changed = false;
    changed |= set(a.status, reg.status().wire(), v -> a.status = v);
    changed |= set(a.kind, reg.kind().wire(), v -> a.kind = v);
    changed |= set(a.serviceCode, blank(reg.serviceCode()), v -> a.serviceCode = v);
    changed |=
        set(
            a.codeSystem,
            reg.codeSystem() == null ? null : reg.codeSystem().toUpperCase(),
            v -> a.codeSystem = v);
    changed |= set(a.healthUnitCnes, blank(reg.healthUnitCnes()), v -> a.healthUnitCnes = v);
    changed |= set(a.professionalId, blank(reg.professionalId()), v -> a.professionalId = v);
    // PostgreSQL guarda microssegundos: truncar para comparar com o valor persistido
    changed |= set(a.scheduledStart, micros(reg.scheduledStart()), v -> a.scheduledStart = v);
    changed |= set(a.scheduledEnd, micros(reg.scheduledEnd()), v -> a.scheduledEnd = v);
    changed |=
        set(
            a.regulationRequestId,
            blank(reg.regulationRequestId()),
            v -> a.regulationRequestId = v);
    changed |= set(a.examOrderId, blank(reg.examOrderId()), v -> a.examOrderId = v);
    changed |= set(a.careLine, blank(reg.careLine()), v -> a.careLine = v);
    changed |=
        set(a.cancellationReason, blank(reg.cancellationReason()), v -> a.cancellationReason = v);
    return changed;
  }

  private static Instant micros(OffsetDateTime t) {
    return t == null ? null : t.toInstant().truncatedTo(ChronoUnit.MICROS);
  }

  private static <T> boolean set(T current, T value, java.util.function.Consumer<T> setter) {
    if (Objects.equals(current, value)) {
      return false;
    }
    setter.accept(value);
    return true;
  }

  private void recordHistory(
      Appointment a, String previous, String reason, Instant occurredAt, String sourceSystem) {
    AppointmentStatusHistory h = new AppointmentStatusHistory();
    h.id = Ulid.generate(Ulid.APPOINTMENT_HISTORY);
    h.tenantId = a.tenantId;
    h.appointmentId = a.id;
    h.status = a.status;
    h.previousStatus = previous;
    h.reason = reason;
    h.occurredAt = occurredAt;
    h.sourceSystem = sourceSystem;
    h.actorId = currentActor.actorId();
    history.persist(h);
  }

  static String actionFor(String status) {
    return switch (AppointmentStatus.fromWire(status)) {
      case CONFIRMED -> "confirmed";
      case CANCELLED -> "cancelled";
      case ARRIVED, FULFILLED -> "attended";
      case NOSHOW -> "no_show";
      case PROPOSED, BOOKED, WAITLIST -> "rescheduled";
    };
  }

  /** Ação adicional publicada quando o registro já nasce em estado terminal. */
  static String terminalAction(AppointmentStatus status) {
    return switch (status) {
      case CANCELLED -> "cancelled";
      case FULFILLED -> "attended";
      case NOSHOW -> "no_show";
      default -> null;
    };
  }

  AppointmentDto toDto(Appointment a, boolean withHistory) {
    List<AppointmentDto.StatusEntry> entries = null;
    if (withHistory) {
      entries =
          history.byAppointment(a.id).stream()
              .map(
                  h ->
                      new AppointmentDto.StatusEntry(
                          AppointmentStatus.fromWire(h.status),
                          h.occurredAt.atOffset(ZoneOffset.UTC),
                          h.reason))
              .toList();
    }
    return new AppointmentDto(
        a.id,
        a.citizenId,
        AppointmentStatus.fromWire(a.status),
        AppointmentKind.fromWire(a.kind),
        a.serviceCode,
        a.codeSystem,
        null,
        a.healthUnitCnes,
        a.professionalId,
        a.scheduledStart.atOffset(ZoneOffset.UTC),
        a.scheduledEnd == null ? null : a.scheduledEnd.atOffset(ZoneOffset.UTC),
        a.regulationRequestId,
        a.examOrderId,
        a.careLine,
        a.cancellationReason,
        a.sourceSystem,
        a.sourceRecordId,
        entries,
        a.version);
  }

  private AppointmentDuplicateDto toDto(AppointmentDuplicate d) {
    List<AppointmentDto> apts = new ArrayList<>();
    for (String id : d.appointmentIds) {
      Appointment a = appointments.findById(id);
      if (a != null) {
        apts.add(toDto(a, false));
      }
    }
    return new AppointmentDuplicateDto(
        d.id,
        d.citizenId,
        d.serviceCode,
        apts,
        d.windowHours,
        d.detectedAt.atOffset(ZoneOffset.UTC));
  }

  private static String blank(String s) {
    return s == null || s.isBlank() ? null : s.trim();
  }
}
