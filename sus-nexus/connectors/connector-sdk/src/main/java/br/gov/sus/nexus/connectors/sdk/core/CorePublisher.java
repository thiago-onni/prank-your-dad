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
import br.gov.sus.nexus.connectors.sdk.core.dto.HealthUnitUpsert;
import br.gov.sus.nexus.connectors.sdk.core.dto.HealthUnitUpsertBatch;
import br.gov.sus.nexus.connectors.sdk.core.dto.IdentityResolution;
import br.gov.sus.nexus.connectors.sdk.core.dto.SourceRef;
import br.gov.sus.nexus.connectors.sdk.core.dto.UpsertResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;

/**
 * Despacha um {@link CanonicalBatch} para o endpoint correto do core conforme {@code entityType}:
 * cidadão e agendamento são publicados registro a registro (chave de idempotência por registro);
 * unidades e códigos são publicados em lotes de {@code connector.core.batch-size}.
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
