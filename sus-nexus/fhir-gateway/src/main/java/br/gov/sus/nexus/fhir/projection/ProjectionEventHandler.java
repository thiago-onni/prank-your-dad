package br.gov.sus.nexus.fhir.projection;

import br.gov.sus.nexus.fhir.audit.ProvenanceFactory.ProjectionSource;
import br.gov.sus.nexus.fhir.mapping.CanonicalCareGap;
import br.gov.sus.nexus.fhir.mapping.CanonicalEncounter;
import br.gov.sus.nexus.fhir.mapping.CanonicalExamOrder;
import br.gov.sus.nexus.fhir.mapping.CanonicalExamResult;
import br.gov.sus.nexus.fhir.mapping.ProjectionService;
import br.gov.sus.nexus.fhir.mapping.ProjectionService.ProjectionResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

/**
 * Trata um envelope de evento: valida, verifica o inbox, busca o canônico completo no core (exceto
 * atendimento APS e lacuna de cuidado, projetados a partir do próprio evento) e projeta com {@code
 * Provenance}.
 *
 * <p>FHIR-3: {@code sus.exam.result.v1} (busca o pedido com {@code results[]}; se o core não
 * devolver o resultado, usa os metadados do evento), {@code sus.hospital.adt.v1} e {@code
 * sus.hospital.discharge.v1} (busca o episódio), {@code sus.careplan.v1} (busca o plano) e {@code
 * sus.caregap.v1} (payload do evento + {@code subject.municipal_citizen_id}).
 */
@ApplicationScoped
public class ProjectionEventHandler {

  private static final Logger LOG = Logger.getLogger(ProjectionEventHandler.class);

  public static final String TOPIC_APPOINTMENT = "sus.schedule.appointment.v1";
  public static final String TOPIC_TASK = "sus.task.v1";
  public static final String TOPIC_REGULATION_REQUEST = "sus.regulation.request.v1";
  public static final String TOPIC_REGULATION_STATUS = "sus.regulation.status.v1";
  public static final String TOPIC_EXAM_ORDER = "sus.exam.order.v1";
  public static final String TOPIC_APS_ENCOUNTER = "sus.aps.encounter.v1";
  public static final String TOPIC_EXAM_RESULT = "sus.exam.result.v1";
  public static final String TOPIC_HOSPITAL_ADT = "sus.hospital.adt.v1";
  public static final String TOPIC_HOSPITAL_DISCHARGE = "sus.hospital.discharge.v1";
  public static final String TOPIC_CAREPLAN = "sus.careplan.v1";
  public static final String TOPIC_CAREGAP = "sus.caregap.v1";

  @Inject ObjectMapper json;
  @Inject ProjectionService projections;
  @Inject ProjectionInboxRepository inbox;
  @Inject @RestClient CoreMunicipalClient core;

  /** Resultado do tratamento de um evento. */
  public record Outcome(String eventId, boolean skipped, String targetType, String targetId) {}

  public Outcome handle(String topic, String payload) {
    EventEnvelope env;
    try {
      env = json.readValue(payload, EventEnvelope.class);
    } catch (IOException e) {
      throw new ProjectionException("Envelope de evento inválido em " + topic, e);
    }
    if (env.eventId() == null || env.tenant() == null || env.tenant().municipalityId() == null) {
      throw new ProjectionException("Envelope sem event_id ou tenant em " + topic);
    }
    String tenant = env.tenant().municipalityId();
    if (inbox.alreadyProcessed(tenant, env.eventId())) {
      LOG.debugf("Evento já processado (topic=%s event=%s)", topic, env.eventId());
      return new Outcome(env.eventId(), true, null, null);
    }
    Optional<ProjectionResult> result = project(topic, env, tenant);
    String targetType = result.map(r -> r.resource().fhirType()).orElse(null);
    String targetId = result.map(r -> r.stored().id()).orElse(null);
    inbox.markProcessed(tenant, env.eventId(), topic, env.eventType(), targetType, targetId);
    LOG.infof(
        "Projeção por evento (topic=%s type=%s target=%s changed=%s correlation=%s)",
        topic,
        env.eventType(),
        targetType,
        result.map(ProjectionResult::changed).orElse(false),
        env.trace() == null ? null : env.trace().correlationId());
    return new Outcome(env.eventId(), false, targetType, targetId);
  }

  private Optional<ProjectionResult> project(String topic, EventEnvelope env, String tenant) {
    String correlation = env.trace() == null ? null : env.trace().correlationId();
    ProjectionSource source =
        new ProjectionSource(
            env.source() == null ? "core-municipal" : env.source().system(),
            env.source() == null ? null : env.source().sourceRecordId(),
            env.occurredAt(),
            correlation,
            env.eventId());
    return switch (topic) {
      case TOPIC_APPOINTMENT -> {
        String id = require(env, "appointment_id");
        yield Optional.of(
            projections.projectAppointment(
                tenant, core.appointment(id, tenant, correlation), source));
      }
      case TOPIC_TASK -> {
        String id = require(env, "task_id");
        yield Optional.of(
            projections.projectTask(tenant, core.task(id, tenant, correlation), source));
      }
      case TOPIC_REGULATION_REQUEST, TOPIC_REGULATION_STATUS -> {
        String id = require(env, "regulation_request_id");
        yield Optional.of(
            projections.projectRegulationRequest(
                tenant, core.regulationRequest(id, tenant, correlation), source));
      }
      case TOPIC_EXAM_ORDER -> {
        String id = require(env, "exam_order_id");
        yield Optional.of(
            projections.projectExamOrder(tenant, core.examOrder(id, tenant, correlation), source));
      }
      case TOPIC_APS_ENCOUNTER -> {
        CanonicalEncounter data;
        try {
          data = json.treeToValue(env.data(), CanonicalEncounter.class);
        } catch (IOException e) {
          throw new ProjectionException("data de atendimento inválido", e);
        }
        if (env.subject() == null || env.subject().municipalCitizenId() == null) {
          throw new ProjectionException("Evento de atendimento sem subject.municipal_citizen_id");
        }
        CanonicalEncounter enriched =
            new CanonicalEncounter(
                data.action(),
                data.encounterId(),
                env.subject().municipalCitizenId(),
                data.encounterClass(),
                data.status(),
                data.healthUnitCnes() != null
                    ? data.healthUnitCnes()
                    : env.source() == null ? null : env.source().cnes(),
                data.teamIne(),
                data.professionalCbo(),
                data.professionalId(),
                data.start(),
                data.end(),
                data.conditionCodes(),
                data.referralsCount(),
                data.examOrdersCount(),
                data.careLines(),
                env.source() == null ? null : env.source().system(),
                env.source() == null ? null : env.source().sourceRecordId(),
                env.occurredAt(),
                env.privacy() == null ? null : env.privacy().classification());
        yield Optional.of(projections.projectEncounter(tenant, enriched, source));
      }
      case TOPIC_EXAM_RESULT -> {
        String orderId = require(env, "exam_order_id");
        String resultId = require(env, "exam_result_id");
        CanonicalExamOrder order = core.examOrder(orderId, tenant, correlation);
        CanonicalExamResult result =
            Optional.ofNullable(order.results()).orElse(List.of()).stream()
                .filter(r -> resultId.equals(r.id()))
                .findFirst()
                .orElseGet(() -> resultFromEvent(env, resultId));
        yield Optional.of(projections.projectExamResult(tenant, order, result, source).report());
      }
      case TOPIC_HOSPITAL_ADT, TOPIC_HOSPITAL_DISCHARGE -> {
        String id = require(env, "hospital_episode_id");
        yield Optional.of(
            projections.projectHospitalEpisode(
                tenant, core.hospitalEpisode(id, tenant, correlation), source));
      }
      case TOPIC_CAREPLAN -> {
        String id = require(env, "care_plan_id");
        yield Optional.of(
            projections.projectCarePlan(tenant, core.carePlan(id, tenant, correlation), source));
      }
      case TOPIC_CAREGAP -> {
        String id = require(env, "care_gap_id");
        if (env.subject() == null || env.subject().municipalCitizenId() == null) {
          throw new ProjectionException("Evento de lacuna sem subject.municipal_citizen_id");
        }
        CanonicalCareGap data;
        try {
          data = json.treeToValue(env.data(), CanonicalCareGap.class);
        } catch (IOException e) {
          throw new ProjectionException("data de lacuna de cuidado inválido", e);
        }
        String status =
            data.status() != null
                ? data.status()
                : "resolved".equals(env.dataText("action")) ? "resolved" : "open";
        CanonicalCareGap gap =
            new CanonicalCareGap(
                id,
                env.subject().municipalCitizenId(),
                data.carePlanId(),
                data.careLine(),
                data.gapKind(),
                status,
                data.expectedBy(),
                data.daysOverdue(),
                data.protocolId(),
                data.protocolVersion(),
                data.healthUnitCnes() != null
                    ? data.healthUnitCnes()
                    : env.source() == null ? null : env.source().cnes(),
                data.teamIne(),
                data.microarea(),
                data.taskId(),
                data.detectedAt(),
                data.resolvedAt(),
                data.resolution(),
                data.contactValid());
        yield Optional.of(projections.projectCareGap(tenant, gap, source));
      }
      default -> throw new ProjectionException("Tópico sem projeção: " + topic);
    };
  }

  /** Resultado mínimo a partir dos metadados do evento (sem observações nem documento). */
  private static CanonicalExamResult resultFromEvent(EventEnvelope env, String resultId) {
    String status = env.dataText("result_status");
    if (status == null) {
      throw new ProjectionException("Evento de resultado sem data.result_status");
    }
    String reported = env.dataText("reported_at");
    String critical = env.dataText("critical");
    String hasDocument = env.dataText("has_document");
    return new CanonicalExamResult(
        resultId,
        reported == null ? env.occurredAt() : java.time.Instant.parse(reported),
        status,
        critical == null ? null : Boolean.valueOf(critical),
        env.dataText("performer_cnes"),
        hasDocument == null ? null : Boolean.valueOf(hasDocument),
        null,
        null,
        env.source() == null ? null : env.source().system(),
        env.source() == null ? null : env.source().sourceRecordId(),
        null,
        null,
        null,
        null,
        null);
  }

  private static String require(EventEnvelope env, String field) {
    String v = env.dataText(field);
    if (v == null || v.isBlank()) {
      throw new ProjectionException("Evento " + env.eventType() + " sem data." + field);
    }
    return v;
  }
}
