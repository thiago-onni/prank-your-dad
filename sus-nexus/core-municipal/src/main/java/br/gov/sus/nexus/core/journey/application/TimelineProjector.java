package br.gov.sus.nexus.core.journey.application;

import br.gov.sus.nexus.core.journey.domain.TimelineEvent;
import br.gov.sus.nexus.core.journey.infrastructure.TimelineEventRepository;
import br.gov.sus.nexus.core.platform.events.EventEnvelope;
import br.gov.sus.nexus.core.platform.events.InboundEventProcessor.Inbound;
import br.gov.sus.nexus.core.platform.ids.Ulid;
import br.gov.sus.nexus.core.platform.tenant.TenantContext;
import br.gov.sus.nexus.core.reference.api.HealthUnitDto;
import br.gov.sus.nexus.core.reference.api.HealthUnitService;
import br.gov.sus.nexus.core.sharedkernel.Cnes;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * Projeta eventos de domínio no read model {@code journey.timeline_event}. Idempotente por {@code
 * (tenant, event_id)}. {@code summary} é texto mínimo, sem conteúdo clínico (JOR-*). {@code
 * correlation_chain} = [correlation_id, causation_id, event_id] (grafo leve causa→efeito, JOR-003).
 */
@ApplicationScoped
public class TimelineProjector {

  private static final Logger LOG = Logger.getLogger(TimelineProjector.class);

  /** Sensibilidade padrão por domínio ({@code policies/data/domains.json}). */
  static final Map<String, String> DEFAULT_SENSITIVITY =
      Map.ofEntries(
          Map.entry("identity", "restricted"),
          Map.entry("aps", "restricted"),
          Map.entry("schedule", "internal"),
          Map.entry("regulation", "restricted"),
          Map.entry("exam", "restricted"),
          Map.entry("hospital", "restricted"),
          Map.entry("careplan", "restricted"),
          Map.entry("task", "internal"),
          Map.entry("production", "internal"),
          Map.entry("communication", "restricted"));

  static final Set<String> RESTRICTED = Set.of("restricted", "highly_restricted");

  @Inject TimelineEventRepository repository;
  @Inject HealthUnitService healthUnits;
  @Inject TenantContext tenantContext;

  /** {@code sus.identity.citizen.*} */
  public boolean projectCitizen(Inbound in) {
    String citizenId = in.data().path("municipal_citizen_id").asText(in.citizenId());
    if (citizenId == null) {
      return false;
    }
    JsonNode d = in.data();
    String state = d.path("registration_state").asText("pending");
    String method = d.path("match").path("method").asText(null);
    String classification = d.path("match").path("classification").asText("new");
    TimelineEvent t = base(in, citizenId, "identity");
    t.status = state;
    t.confidence =
        switch (state) {
          case "divergent" -> "divergent";
          case "pending" -> "pending";
          default -> "probable".equals(classification) ? "pending" : "confirmed";
        };
    t.cnes = text(d.path("territory").path("health_unit_cnes"));
    t.summary =
        "Cadastro "
            + describe(in.action())
            + " via "
            + in.envelope().source().system()
            + (method == null || "none".equals(method) ? "" : " (" + method + ")");
    t.detailRef = "/api/v1/citizens/" + citizenId;
    return save(t);
  }

  /** {@code sus.identity.merge.merged|unmerged}: reatribuição; demais ações só registram. */
  public boolean projectMerge(Inbound in) {
    JsonNode d = in.data();
    String surviving = d.path("surviving_citizen_id").asText(null);
    List<String> merged = new ArrayList<>();
    d.path("merged_citizen_ids").forEach(n -> merged.add(n.asText()));
    if (surviving == null) {
      return false;
    }
    switch (in.action()) {
      case "merged" -> {
        int n = repository.reassign(merged, surviving);
        LOG.infof(
            "merge %s: %d evento(s) reatribuídos a %s", d.path("case_id").asText(), n, surviving);
      }
      case "unmerged" -> {
        int n = repository.revert(merged);
        LOG.infof("unmerge %s: %d evento(s) revertidos", d.path("case_id").asText(), n);
      }
      default -> {
        return true; // case_opened/rejected: sem efeito na timeline
      }
    }
    TimelineEvent t = base(in, surviving, "identity");
    t.status = in.action();
    t.summary =
        "merged".equals(in.action())
            ? "Cadastros unificados (" + merged.size() + " registro(s) fundido(s))"
            : "Unificação de cadastros revertida";
    t.detailRef = "/api/v1/mpi/cases/" + d.path("case_id").asText();
    return save(t);
  }

  /** {@code sus.schedule.appointment.*} */
  public boolean projectAppointment(Inbound in) {
    String citizenId = in.citizenId();
    if (citizenId == null) {
      return false;
    }
    JsonNode d = in.data();
    TimelineEvent t = base(in, citizenId, "schedule");
    t.status = d.path("status").asText("booked");
    t.cnes = text(d.path("health_unit_cnes"));
    t.professionalRef = text(d.path("professional_id"));
    t.careLine = text(d.path("care_line"));
    String service = text(d.path("service_code"));
    String start = text(d.path("scheduled_start"));
    t.summary =
        "Agendamento "
            + describe(in.action())
            + (service == null ? "" : " — " + service)
            + (start == null ? "" : " em " + start);
    t.detailRef = "/api/v1/appointments/" + d.path("appointment_id").asText();
    return save(t);
  }

  /** {@code sus.task.*} */
  public boolean projectTask(Inbound in) {
    String citizenId = in.citizenId();
    if (citizenId == null) {
      return false; // tarefa sem cidadão não entra na timeline
    }
    JsonNode d = in.data();
    TimelineEvent t = base(in, citizenId, "task");
    t.status = d.path("status").asText("open");
    JsonNode assignee = d.path("assignee");
    if (assignee.isObject() && "health_unit".equals(assignee.path("kind").asText())) {
      t.cnes = text(assignee.path("id"));
    }
    t.summary =
        "Tarefa "
            + d.path("task_type").asText("generic")
            + " "
            + describe(in.action())
            + " (prioridade "
            + d.path("priority").asText("medium")
            + ")";
    t.detailRef = "/api/v1/tasks/" + d.path("task_id").asText();
    return save(t);
  }

  /**
   * {@code sus.regulation.request.*} e {@code sus.regulation.status.changed} — sem
   * CID/justificativa.
   */
  public boolean projectRegulation(Inbound in) {
    String citizenId = in.citizenId();
    if (citizenId == null) {
      return false;
    }
    JsonNode d = in.data();
    TimelineEvent t = base(in, citizenId, "regulation");
    t.status = d.path("status").asText("requested");
    t.cnes = text(d.path("requesting_cnes"));
    t.professionalRef = text(d.path("requesting_professional_id"));
    String service = text(d.path("requested_service_code"));
    String priority = text(d.path("priority"));
    boolean statusEvent = in.eventType().startsWith("sus.regulation.status.");
    if (statusEvent) {
      t.summary =
          "Regulação: status "
              + t.status
              + (d.path("sla_breached").asBoolean(false) ? " (SLA vencido)" : "");
    } else {
      t.summary =
          "Solicitação regulatória "
              + describe(in.action())
              + (service == null ? "" : " — " + service)
              + (priority == null ? "" : " (" + priority + ")");
    }
    t.detailRef = "/api/v1/regulation/requests/" + d.path("regulation_request_id").asText();
    return save(t);
  }

  /** {@code sus.exam.order.*} (internal) e {@code sus.exam.result.*} (restricted; sem valores). */
  public boolean projectExam(Inbound in) {
    String citizenId = in.citizenId();
    if (citizenId == null) {
      return false;
    }
    JsonNode d = in.data();
    TimelineEvent t = base(in, citizenId, "exam");
    t.cnes = text(d.path("requesting_cnes"));
    t.careLine = text(d.path("care_line"));
    boolean result = in.eventType().startsWith("sus.exam.result.");
    if (result) {
      t.status = d.path("result_status").asText("final");
      t.summary =
          "critical_flagged".equals(in.action())
              ? "Resultado crítico de exame sinalizado"
              : "Laudo de exame disponível";
      if (!"restricted".equals(t.sensitivity) && !"highly_restricted".equals(t.sensitivity)) {
        t.sensitivity = "restricted";
      }
    } else {
      t.status = d.path("status").asText("requested");
      String code = text(d.path("exam_code"));
      t.summary =
          "Pedido de exame "
              + ("created".equals(in.action()) ? "criado" : "com status " + t.status)
              + (code == null ? "" : " — " + code);
    }
    t.detailRef = "/api/v1/exams/orders/" + d.path("exam_order_id").asText();
    return save(t);
  }

  /**
   * {@code sus.hospital.adt.*} e {@code sus.hospital.discharge.*}: classificação do envelope
   * ({@code highly_restricted} quando o CID principal é sensível); o resumo NUNCA contém o CID.
   */
  public boolean projectHospital(Inbound in) {
    String citizenId = in.citizenId();
    if (citizenId == null) {
      return false;
    }
    JsonNode d = in.data();
    TimelineEvent t = base(in, citizenId, "hospital");
    t.cnes = text(d.path("hospital_cnes"));
    t.professionalRef = text(d.path("attending_professional_id"));
    boolean discharge = in.eventType().startsWith("sus.hospital.discharge.");
    if (discharge) {
      t.status = d.path("disposition").asText("home");
      JsonNode lines = d.path("care_lines");
      if (lines.isArray() && !lines.isEmpty()) {
        t.careLine = lines.get(0).asText();
      }
      String risk = text(d.path("risk_level"));
      t.summary =
          "counter_referral_received".equals(in.action())
              ? "Contrarreferência hospitalar recebida"
              : "Alta hospitalar ("
                  + t.status
                  + (d.hasNonNull("length_of_stay_days")
                      ? ", " + d.get("length_of_stay_days").asInt() + " dia(s)"
                      : "")
                  + (risk == null ? "" : ", risco " + risk)
                  + ")";
    } else {
      t.status = d.path("status").asText("admitted");
      String ward = text(d.path("ward"));
      t.summary =
          "Episódio hospitalar "
              + describe(in.action())
              + " — "
              + d.path("episode_class").asText("inpatient")
              + (ward == null ? "" : " (" + ward + ")");
    }
    if (!RESTRICTED.contains(t.sensitivity)) {
      t.sensitivity = "restricted";
    }
    t.detailRef = "/api/v1/hospital/episodes/" + d.path("hospital_episode_id").asText();
    return save(t);
  }

  /** {@code sus.careplan.*} e {@code sus.caregap.*} (restricted; só linha, status e contagens). */
  public boolean projectCarePlan(Inbound in) {
    String citizenId = in.citizenId();
    if (citizenId == null) {
      return false;
    }
    JsonNode d = in.data();
    TimelineEvent t = base(in, citizenId, "careplan");
    t.cnes = text(d.path("health_unit_cnes"));
    t.careLine = text(d.path("care_line"));
    boolean gap = in.eventType().startsWith("sus.caregap.");
    if (gap) {
      t.status = "resolved".equals(in.action()) ? "resolved" : "open";
      t.summary =
          "Lacuna de cuidado "
              + ("resolved".equals(in.action()) ? "resolvida" : "detectada")
              + " — "
              + d.path("gap_kind").asText("")
              + (t.careLine == null ? "" : " (" + t.careLine + ")");
      t.detailRef = "/api/v1/caregaps/" + d.path("care_gap_id").asText();
    } else {
      t.status = d.path("status").asText("active");
      t.summary =
          "Plano de cuidado "
              + describe(in.action())
              + (t.careLine == null ? "" : " — " + t.careLine)
              + " (protocolo v"
              + d.path("protocol_version").asText("?")
              + ")";
      t.detailRef = "/api/v1/careplans/" + d.path("care_plan_id").asText();
    }
    if (!RESTRICTED.contains(t.sensitivity)) {
      t.sensitivity = "restricted";
    }
    return save(t);
  }

  // ---------------------------------------------------------------------

  private TimelineEvent base(Inbound in, String citizenId, String domain) {
    EventEnvelope env = in.envelope();
    TimelineEvent t = new TimelineEvent();
    t.id = Ulid.generate(Ulid.TIMELINE_EVENT);
    t.tenantId = tenantContext.require();
    t.citizenId = citizenId;
    t.originalCitizenId = citizenId;
    t.domain = domain;
    t.eventType = env.eventType();
    t.eventId = env.eventId();
    t.aggregateId = env.source() == null ? null : env.source().sourceRecordId();
    OffsetDateTime occurred = env.occurredAt() == null ? env.publishedAt() : env.occurredAt();
    t.occurredAt = occurred == null ? Instant.now() : occurred.toInstant();
    t.recordedAt = Instant.now();
    t.sourceSystem = env.source() == null ? "core-municipal" : env.source().system();
    t.sensitivity =
        env.privacy() != null && env.privacy().classification() != null
            ? env.privacy().classification()
            : DEFAULT_SENSITIVITY.getOrDefault(domain, "internal");
    Set<String> chain = new LinkedHashSet<>();
    if (env.trace() != null) {
      if (env.trace().correlationId() != null) {
        chain.add(env.trace().correlationId());
      }
      if (env.trace().causationId() != null) {
        chain.add(env.trace().causationId());
      }
    }
    chain.add(env.eventId());
    t.correlationChain = chain.toArray(String[]::new);
    return t;
  }

  private boolean save(TimelineEvent t) {
    if (repository.findByEventId(t.eventId).isPresent()) {
      return false;
    }
    if (Cnes.isValid(t.cnes)) {
      t.healthUnitName = healthUnits.findByCnes(t.cnes).map(HealthUnitDto::name).orElse(null);
    }
    repository.persist(t);
    return true;
  }

  static String describe(String action) {
    return switch (action) {
      case "created" -> "criado";
      case "linked" -> "vinculado";
      case "updated" -> "atualizado";
      case "registration_state_changed" -> "com situação cadastral alterada";
      case "confirmed" -> "confirmado";
      case "cancelled" -> "cancelado";
      case "rescheduled" -> "reagendado";
      case "attended" -> "realizado";
      case "no_show" -> "com falta";
      case "duplicate_detected" -> "com duplicidade detectada";
      case "assigned" -> "atribuída";
      case "completed" -> "concluída";
      case "escalated" -> "escalonada";
      case "sla_breached" -> "com SLA estourado";
      case "admitted" -> "admitido";
      case "transferred" -> "transferido";
      case "bed_changed" -> "com troca de leito";
      case "discharged" -> "com alta";
      case "deceased" -> "com óbito";
      case "closed" -> "encerrado";
      default -> action.replace('_', ' ');
    };
  }

  private static String text(JsonNode n) {
    return n == null || n.isMissingNode() || n.isNull() ? null : n.asText();
  }
}
