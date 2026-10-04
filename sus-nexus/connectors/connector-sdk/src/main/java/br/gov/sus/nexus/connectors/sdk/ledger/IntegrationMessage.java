package br.gov.sus.nexus.connectors.sdk.ledger;

import br.gov.sus.nexus.connectors.sdk.api.ErrorDetails;
import br.gov.sus.nexus.connectors.sdk.util.Ids;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Registro de rastreio de uma mensagem no pipeline (id {@code msg_<ULID>}). Transições válidas:
 * received → transformed → validated → published → processed; qualquer estado → failed →
 * (reprocessing | dead_lettered).
 */
public final class IntegrationMessage {

  private static final Map<IntegrationMessageStatus, Set<IntegrationMessageStatus>> TRANSITIONS =
      Map.of(
          IntegrationMessageStatus.RECEIVED,
          EnumSet.of(IntegrationMessageStatus.TRANSFORMED, IntegrationMessageStatus.FAILED),
          IntegrationMessageStatus.TRANSFORMED,
          EnumSet.of(IntegrationMessageStatus.VALIDATED, IntegrationMessageStatus.FAILED),
          IntegrationMessageStatus.VALIDATED,
          EnumSet.of(IntegrationMessageStatus.PUBLISHED, IntegrationMessageStatus.FAILED),
          IntegrationMessageStatus.PUBLISHED,
          EnumSet.of(IntegrationMessageStatus.PROCESSED, IntegrationMessageStatus.FAILED),
          IntegrationMessageStatus.PROCESSED,
          EnumSet.noneOf(IntegrationMessageStatus.class),
          IntegrationMessageStatus.FAILED,
          EnumSet.of(
              IntegrationMessageStatus.REPROCESSING,
              IntegrationMessageStatus.DEAD_LETTERED,
              IntegrationMessageStatus.FAILED),
          IntegrationMessageStatus.DEAD_LETTERED,
          EnumSet.of(IntegrationMessageStatus.REPROCESSING),
          IntegrationMessageStatus.REPROCESSING,
          EnumSet.of(
              IntegrationMessageStatus.TRANSFORMED,
              IntegrationMessageStatus.VALIDATED,
              IntegrationMessageStatus.PUBLISHED,
              IntegrationMessageStatus.FAILED));

  private final String id;
  private final String connectorId;
  private final String sourceSystem;
  private final String sourceRecordId;
  private final String sourceRecordVersion;
  private final String entityType;
  private IntegrationMessageStatus status;
  private String rawRef;
  private String rawSha256;
  private final String correlationId;
  private final Instant receivedAt;
  private Instant processedAt;
  private int attempts;
  private ErrorDetails lastError;

  public IntegrationMessage(
      String id,
      String connectorId,
      String sourceSystem,
      String sourceRecordId,
      String sourceRecordVersion,
      String entityType,
      IntegrationMessageStatus status,
      String rawRef,
      String rawSha256,
      String correlationId,
      Instant receivedAt,
      Instant processedAt,
      int attempts,
      ErrorDetails lastError) {
    this.id = id;
    this.connectorId = connectorId;
    this.sourceSystem = sourceSystem;
    this.sourceRecordId = sourceRecordId;
    this.sourceRecordVersion = sourceRecordVersion;
    this.entityType = entityType;
    this.status = status;
    this.rawRef = rawRef;
    this.rawSha256 = rawSha256;
    this.correlationId = correlationId;
    this.receivedAt = receivedAt;
    this.processedAt = processedAt;
    this.attempts = attempts;
    this.lastError = lastError;
  }

  public static IntegrationMessage received(
      String connectorId,
      String sourceSystem,
      String sourceRecordId,
      String sourceRecordVersion,
      String entityType,
      String rawRef,
      String rawSha256,
      String correlationId) {
    return new IntegrationMessage(
        Ids.message(),
        connectorId,
        sourceSystem,
        sourceRecordId,
        sourceRecordVersion,
        entityType,
        IntegrationMessageStatus.RECEIVED,
        rawRef,
        rawSha256,
        correlationId == null ? Ids.correlation() : correlationId,
        Instant.now(),
        null,
        0,
        null);
  }

  public void transitionTo(IntegrationMessageStatus next) {
    Set<IntegrationMessageStatus> allowed = TRANSITIONS.getOrDefault(status, Set.of());
    if (!allowed.contains(next)) {
      throw new IllegalStateException(
          "transição inválida de integration_message " + id + ": " + status + " -> " + next);
    }
    this.status = next;
    if (next == IntegrationMessageStatus.PROCESSED || next == IntegrationMessageStatus.PUBLISHED) {
      this.processedAt = Instant.now();
    }
  }

  public void fail(String stage, String code, String message) {
    this.attempts++;
    this.lastError = new ErrorDetails(id, code, message, stage, Instant.now(), attempts);
    if (status != IntegrationMessageStatus.FAILED) {
      transitionTo(IntegrationMessageStatus.FAILED);
    }
  }

  public void markAttempt() {
    this.attempts++;
  }

  public String id() {
    return id;
  }

  public String connectorId() {
    return connectorId;
  }

  public String sourceSystem() {
    return sourceSystem;
  }

  public String sourceRecordId() {
    return sourceRecordId;
  }

  public String sourceRecordVersion() {
    return sourceRecordVersion;
  }

  public String entityType() {
    return entityType;
  }

  public IntegrationMessageStatus status() {
    return status;
  }

  public String rawRef() {
    return rawRef;
  }

  public String rawSha256() {
    return rawSha256;
  }

  public String correlationId() {
    return correlationId;
  }

  public Instant receivedAt() {
    return receivedAt;
  }

  public Instant processedAt() {
    return processedAt;
  }

  public int attempts() {
    return attempts;
  }

  public ErrorDetails lastError() {
    return lastError;
  }

  public void rawRef(String rawRef, String sha256) {
    this.rawRef = rawRef;
    this.rawSha256 = sha256;
  }

  @Override
  public String toString() {
    return "IntegrationMessage["
        + id
        + ", "
        + entityType
        + ", "
        + status
        + ", attempts="
        + attempts
        + "]";
  }
}
