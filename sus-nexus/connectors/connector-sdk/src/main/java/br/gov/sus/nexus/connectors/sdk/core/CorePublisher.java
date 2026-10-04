package br.gov.sus.nexus.connectors.sdk.core;

import br.gov.sus.nexus.connectors.sdk.api.CanonicalBatch;
import br.gov.sus.nexus.connectors.sdk.api.CanonicalRecord;
import br.gov.sus.nexus.connectors.sdk.api.ConnectorDescriptor;
import br.gov.sus.nexus.connectors.sdk.api.ConnectorException;
import br.gov.sus.nexus.connectors.sdk.api.PublishResult;
import br.gov.sus.nexus.connectors.sdk.config.ConnectorConfig;
import br.gov.sus.nexus.connectors.sdk.core.dto.AppointmentRegistration;
import br.gov.sus.nexus.connectors.sdk.core.dto.AppointmentResponse;
import br.gov.sus.nexus.connectors.sdk.core.dto.CitizenRegistration;
import br.gov.sus.nexus.connectors.sdk.core.dto.CodeUpsert;
import br.gov.sus.nexus.connectors.sdk.core.dto.CodeUpsertBatch;
import br.gov.sus.nexus.connectors.sdk.core.dto.ExamOrderRegistration;
import br.gov.sus.nexus.connectors.sdk.core.dto.ExamOrderResponse;
import br.gov.sus.nexus.connectors.sdk.core.dto.ExamResultRegistration;
import br.gov.sus.nexus.connectors.sdk.core.dto.HealthUnitUpsert;
import br.gov.sus.nexus.connectors.sdk.core.dto.HealthUnitUpsertBatch;
import br.gov.sus.nexus.connectors.sdk.core.dto.IdentityResolution;
import br.gov.sus.nexus.connectors.sdk.core.dto.ProviderCapacity;
import br.gov.sus.nexus.connectors.sdk.core.dto.ProviderCapacityBatch;
import br.gov.sus.nexus.connectors.sdk.core.dto.RegulationRequestRegistration;
import br.gov.sus.nexus.connectors.sdk.core.dto.RegulationRequestResponse;
import br.gov.sus.nexus.connectors.sdk.core.dto.RegulationStatusChange;
import br.gov.sus.nexus.connectors.sdk.core.dto.SourceRef;
import br.gov.sus.nexus.connectors.sdk.core.dto.UpsertResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Despacha um {@link CanonicalBatch} para o endpoint correto do core conforme {@code entityType}:
 * cidadão e agendamento são publicados registro a registro (chave de idempotência por registro);
 * unidades e códigos são publicados em lotes de {@code connector.core.batch-size}.
 *
 * <p>Fase 2 (regulação e laboratório): {@link CanonicalBatch#REGULATION_REQUEST} → {@code POST
 * /regulation/requests} (upsert por source_record_id); {@link CanonicalBatch#REGULATION_STATUS} →
 * {@code POST /regulation/requests/by-source/{system}/{id}/status}; {@link
 * CanonicalBatch#PROVIDER_CAPACITY} → {@code POST /regulation/capacity} (lotes); {@link
 * CanonicalBatch#EXAM_ORDER} → {@code POST /exams/orders} (upsert); {@link
 * CanonicalBatch#EXAM_RESULT} → {@code POST /exams/orders/by-source/{system}/{id}/results}.
 *
 * <p>Nos tipos "by-source" o registro alvo (pedido) é lido do payload em {@value #TARGET_REF}
 * ({@code {system, source_record_id}}); se ausente, usa-se {@code source} do próprio payload. A
 * chave {@value #TARGET_REF} é removida antes do envio.
 */
@ApplicationScoped
public class CorePublisher {

  private final CoreClient client;
  private final ObjectMapper mapper;
  private final ConnectorConfig config;

  @Inject
  public CorePublisher(CoreClient client, ObjectMapper mapper, ConnectorConfig config) {
    this.client = client;
    this.mapper = mapper;
    this.config = config;
  }

  public PublishResult publish(
      ConnectorDescriptor descriptor, CanonicalBatch batch, String correlationId) {
    return switch (batch.entityType()) {
      case CanonicalBatch.CITIZEN -> publishCitizens(batch, correlationId);
      case CanonicalBatch.APPOINTMENT -> publishAppointments(batch, correlationId);
      case CanonicalBatch.HEALTH_UNIT -> publishHealthUnits(descriptor, batch, correlationId);
      case CanonicalBatch.CODE -> publishCodes(descriptor, batch, correlationId);
      case CanonicalBatch.REGULATION_REQUEST -> publishRegulationRequests(batch, correlationId);
      case CanonicalBatch.REGULATION_STATUS -> publishRegulationStatus(batch, correlationId);
      case CanonicalBatch.PROVIDER_CAPACITY -> publishProviderCapacity(batch, correlationId);
      case CanonicalBatch.EXAM_ORDER -> publishExamOrders(batch, correlationId);
      case CanonicalBatch.EXAM_RESULT -> publishExamResults(batch, correlationId);
      default ->
          throw ConnectorException.permanent(
              "publish", "entity_type sem rota de publicação: " + batch.entityType(), null);
    };
  }

  private PublishResult publishCitizens(CanonicalBatch batch, String correlationId) {
    List<String> ids = new ArrayList<>();
    for (CanonicalRecord r : batch.records()) {
      CitizenRegistration body = mapper.convertValue(r.payload(), CitizenRegistration.class);
      IdentityResolution res = client.registerCitizen(IdempotencyKeys.of(r), correlationId, body);
      if (res != null && res.municipalCitizenId() != null) ids.add(res.municipalCitizenId());
    }
    return new PublishResult(batch.size(), 0, ids, null);
  }

  private PublishResult publishAppointments(CanonicalBatch batch, String correlationId) {
    List<String> ids = new ArrayList<>();
    for (CanonicalRecord r : batch.records()) {
      AppointmentRegistration body =
          mapper.convertValue(r.payload(), AppointmentRegistration.class);
      AppointmentResponse res =
          client.registerAppointment(IdempotencyKeys.of(r), correlationId, body);
      if (res != null && res.id() != null) ids.add(res.id());
    }
    return new PublishResult(batch.size(), 0, ids, null);
  }

  private PublishResult publishHealthUnits(
      ConnectorDescriptor d, CanonicalBatch batch, String correlationId) {
    int published = 0;
    for (List<CanonicalRecord> chunk : chunks(batch.records())) {
      List<HealthUnitUpsert> items =
          chunk.stream()
              .map(r -> mapper.convertValue(r.payload(), HealthUnitUpsert.class))
              .toList();
      HealthUnitUpsertBatch body =
          new HealthUnitUpsertBatch(
              sourceRef(d, batch), batch.attributes().get("competence"), items);
      UpsertResult res =
          client.upsertHealthUnits(IdempotencyKeys.ofBatch(chunk), correlationId, body);
      published += res == null ? items.size() : Math.max(res.total(), items.size());
    }
    return new PublishResult(published, 0, List.of(), null);
  }

  private PublishResult publishCodes(
      ConnectorDescriptor d, CanonicalBatch batch, String correlationId) {
    String system = batch.attributes().get("system");
    if (system == null) {
      throw ConnectorException.permanent("publish", "lote de códigos sem attributes.system", null);
    }
    int published = 0;
    for (List<CanonicalRecord> chunk : chunks(batch.records())) {
      List<CodeUpsert> items =
          chunk.stream().map(r -> mapper.convertValue(r.payload(), CodeUpsert.class)).toList();
      CodeUpsertBatch body =
          new CodeUpsertBatch(
              sourceRef(d, batch),
              batch.attributes().get("competence"),
              batch.attributes().get("version"),
              items);
      UpsertResult res =
          client.upsertCodes(system, IdempotencyKeys.ofBatch(chunk), correlationId, body);
      published += res == null ? items.size() : Math.max(res.total(), items.size());
    }
    return new PublishResult(published, 0, List.of(), null);
  }

  private PublishResult publishRegulationRequests(CanonicalBatch batch, String correlationId) {
    List<String> ids = new ArrayList<>();
    for (CanonicalRecord r : batch.records()) {
      RegulationRequestRegistration body =
          mapper.convertValue(r.payload(), RegulationRequestRegistration.class);
      RegulationRequestResponse res =
          client.registerRegulationRequest(IdempotencyKeys.of(r), correlationId, body);
      if (res != null && res.id() != null) ids.add(res.id());
    }
    return new PublishResult(batch.size(), 0, ids, null);
  }

  private PublishResult publishRegulationStatus(CanonicalBatch batch, String correlationId) {
    List<String> ids = new ArrayList<>();
    for (CanonicalRecord r : batch.records()) {
      Map<String, Object> payload = new LinkedHashMap<>(r.payload());
      TargetRef target = targetRef(payload);
      RegulationStatusChange body = mapper.convertValue(payload, RegulationStatusChange.class);
      RegulationRequestResponse res =
          client.registerRegulationStatusBySource(
              target.system(), target.sourceRecordId(), IdempotencyKeys.of(r), correlationId, body);
      if (res != null && res.id() != null) ids.add(res.id());
    }
    return new PublishResult(batch.size(), 0, ids, null);
  }

  private PublishResult publishProviderCapacity(CanonicalBatch batch, String correlationId) {
    int published = 0;
    for (List<CanonicalRecord> chunk : chunks(batch.records())) {
      List<ProviderCapacity> items =
          chunk.stream()
              .map(r -> mapper.convertValue(r.payload(), ProviderCapacity.class))
              .toList();
      UpsertResult res =
          client.upsertProviderCapacity(
              IdempotencyKeys.ofBatch(chunk), correlationId, new ProviderCapacityBatch(items));
      published += res == null ? items.size() : Math.max(res.total(), items.size());
    }
    return new PublishResult(published, 0, List.of(), null);
  }

  private PublishResult publishExamOrders(CanonicalBatch batch, String correlationId) {
    List<String> ids = new ArrayList<>();
    for (CanonicalRecord r : batch.records()) {
      ExamOrderRegistration body = mapper.convertValue(r.payload(), ExamOrderRegistration.class);
      ExamOrderResponse res = client.registerExamOrder(IdempotencyKeys.of(r), correlationId, body);
      if (res != null && res.id() != null) ids.add(res.id());
    }
    return new PublishResult(batch.size(), 0, ids, null);
  }

  private PublishResult publishExamResults(CanonicalBatch batch, String correlationId) {
    List<String> ids = new ArrayList<>();
    for (CanonicalRecord r : batch.records()) {
      Map<String, Object> payload = new LinkedHashMap<>(r.payload());
      TargetRef target = targetRef(payload);
      ExamResultRegistration body = mapper.convertValue(payload, ExamResultRegistration.class);
      ExamOrderResponse res =
          client.registerExamResultBySource(
              target.system(), target.sourceRecordId(), IdempotencyKeys.of(r), correlationId, body);
      if (res != null && res.id() != null) ids.add(res.id());
    }
    return new PublishResult(batch.size(), 0, ids, null);
  }

  /** Chave opcional do payload que aponta o registro alvo de um endpoint "by-source". */
  public static final String TARGET_REF = "target_ref";

  record TargetRef(String system, String sourceRecordId) {}

  @SuppressWarnings("unchecked")
  private static TargetRef targetRef(Map<String, Object> payload) {
    Object ref = payload.remove(TARGET_REF);
    Map<String, Object> m =
        ref instanceof Map<?, ?> rm
            ? (Map<String, Object>) rm
            : (Map<String, Object>) payload.get("source");
    String system = m == null ? null : str(m.get("system"));
    String id = m == null ? null : str(m.get("source_record_id"));
    if (system == null || id == null) {
      throw ConnectorException.permanent(
          "publish", "payload sem target_ref/source para endpoint by-source", null);
    }
    return new TargetRef(system, id);
  }

  private static String str(Object o) {
    return o == null || o.toString().isBlank() ? null : o.toString();
  }

  private static SourceRef sourceRef(ConnectorDescriptor d, CanonicalBatch batch) {
    return new SourceRef(
        d.sourceSystem(),
        d.connectorId(),
        batch.attributes().getOrDefault("source_record_id", "batch"),
        batch.attributes().get("source_record_version"),
        null);
  }

  private List<List<CanonicalRecord>> chunks(List<CanonicalRecord> records) {
    int size = Math.max(1, config.core().batchSize());
    List<List<CanonicalRecord>> out = new ArrayList<>();
    for (int i = 0; i < records.size(); i += size) {
      out.add(records.subList(i, Math.min(records.size(), i + size)));
    }
    return out;
  }
}
