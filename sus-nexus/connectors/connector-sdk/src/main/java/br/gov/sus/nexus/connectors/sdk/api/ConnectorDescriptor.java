package br.gov.sus.nexus.connectors.sdk.api;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Metadados obrigatórios de um conector. Todos os campos são exigidos pelo catálogo de conectores
 * (spec §5.6); {@link #validate()} falha rápido quando algo está ausente.
 */
public record ConnectorDescriptor(
    String connectorId,
    String connectorVersion,
    String sourceSystem,
    List<String> supportedSourceVersions,
    List<String> supportedProtocols,
    List<String> supportedEntities,
    AuthenticationMethod authenticationMethod,
    List<String> requiredNetworkAccess,
    DataClassification dataClassification,
    IngestionMode pollingOrEventMode,
    RetryPolicySpec retryPolicy,
    RateLimitPolicy rateLimitPolicy,
    String fieldMappingVersion,
    String testSuiteVersion,
    String owner,
    SupportSla supportSla) {

  public ConnectorDescriptor {
    supportedSourceVersions = List.copyOf(supportedSourceVersions);
    supportedProtocols = List.copyOf(supportedProtocols);
    supportedEntities = List.copyOf(supportedEntities);
    requiredNetworkAccess = List.copyOf(requiredNetworkAccess);
  }

  public enum AuthenticationMethod {
    NONE,
    BASIC,
    OIDC_CLIENT_CREDENTIALS,
    API_KEY,
    MTLS,
    DATABASE_CREDENTIALS,
    FILE_SYSTEM
  }

  public enum DataClassification {
    PUBLIC,
    INTERNAL,
    RESTRICTED,
    HIGHLY_RESTRICTED
  }

  public enum IngestionMode {
    POLLING,
    EVENT,
    FILE_DROP,
    HYBRID
  }

  /** Política de retry declarada (espelha {@code connector.retry.*}). */
  public record RetryPolicySpec(
      int maxAttempts, Duration initialBackoff, double multiplier, Duration maxBackoff) {}

  /** Limite de taxa declarado contra a fonte. */
  public record RateLimitPolicy(int maxRequestsPerMinute, int maxConcurrent) {}

  /** SLA de suporte do conector. */
  public record SupportSla(String tier, Duration responseTime, Duration resolutionTime) {}

  /** Lista os campos ausentes; vazia quando o descriptor é válido. */
  public List<String> missingFields() {
    List<String> missing = new ArrayList<>();
    check(missing, "connector_id", connectorId);
    check(missing, "connector_version", connectorVersion);
    check(missing, "source_system", sourceSystem);
    checkList(missing, "supported_source_versions", supportedSourceVersions);
    checkList(missing, "supported_protocols", supportedProtocols);
    checkList(missing, "supported_entities", supportedEntities);
    if (authenticationMethod == null) missing.add("authentication_method");
    if (requiredNetworkAccess == null) missing.add("required_network_access");
    if (dataClassification == null) missing.add("data_classification");
    if (pollingOrEventMode == null) missing.add("polling_or_event_mode");
    if (retryPolicy == null) missing.add("retry_policy");
    if (rateLimitPolicy == null) missing.add("rate_limit_policy");
    check(missing, "field_mapping_version", fieldMappingVersion);
    check(missing, "test_suite_version", testSuiteVersion);
    check(missing, "owner", owner);
    if (supportSla == null) missing.add("support_sla");
    return missing;
  }

  /** Lança {@link IllegalStateException} se algum metadado obrigatório estiver ausente. */
  public ConnectorDescriptor validate() {
    List<String> missing = missingFields();
    if (!missing.isEmpty()) {
      throw new IllegalStateException("ConnectorDescriptor incompleto; faltam: " + missing);
    }
    return this;
  }

  private static void check(List<String> missing, String name, String value) {
    if (value == null || value.isBlank()) missing.add(name);
  }

  private static void checkList(List<String> missing, String name, List<String> value) {
    if (value == null || value.isEmpty()) missing.add(name);
  }

  public static Builder builder() {
    return new Builder();
  }

  /** Builder fluente (sem Lombok). */
  public static final class Builder {
    private String connectorId;
    private String connectorVersion;
    private String sourceSystem;
    private List<String> supportedSourceVersions = List.of();
    private List<String> supportedProtocols = List.of();
    private List<String> supportedEntities = List.of();
    private AuthenticationMethod authenticationMethod;
    private List<String> requiredNetworkAccess = List.of();
    private DataClassification dataClassification;
    private IngestionMode pollingOrEventMode;
    private RetryPolicySpec retryPolicy =
        new RetryPolicySpec(5, Duration.ofSeconds(1), 2.0, Duration.ofMinutes(5));
    private RateLimitPolicy rateLimitPolicy = new RateLimitPolicy(600, 4);
    private String fieldMappingVersion;
    private String testSuiteVersion;
    private String owner;
    private SupportSla supportSla;

    public Builder connectorId(String v) {
      this.connectorId = v;
      return this;
    }

    public Builder connectorVersion(String v) {
      this.connectorVersion = v;
      return this;
    }

    public Builder sourceSystem(String v) {
      this.sourceSystem = v;
      return this;
    }

    public Builder supportedSourceVersions(List<String> v) {
      this.supportedSourceVersions = v;
      return this;
    }

    public Builder supportedProtocols(List<String> v) {
      this.supportedProtocols = v;
      return this;
    }

    public Builder supportedEntities(List<String> v) {
      this.supportedEntities = v;
      return this;
    }

    public Builder authenticationMethod(AuthenticationMethod v) {
      this.authenticationMethod = v;
      return this;
    }

    public Builder requiredNetworkAccess(List<String> v) {
      this.requiredNetworkAccess = v;
      return this;
    }

    public Builder dataClassification(DataClassification v) {
      this.dataClassification = v;
      return this;
    }

    public Builder pollingOrEventMode(IngestionMode v) {
      this.pollingOrEventMode = v;
      return this;
    }

    public Builder retryPolicy(RetryPolicySpec v) {
      this.retryPolicy = v;
      return this;
    }

    public Builder rateLimitPolicy(RateLimitPolicy v) {
      this.rateLimitPolicy = v;
      return this;
    }

    public Builder fieldMappingVersion(String v) {
      this.fieldMappingVersion = v;
      return this;
    }

    public Builder testSuiteVersion(String v) {
      this.testSuiteVersion = v;
      return this;
    }

    public Builder owner(String v) {
      this.owner = v;
      return this;
    }

    public Builder supportSla(SupportSla v) {
      this.supportSla = v;
      return this;
    }

    public ConnectorDescriptor build() {
      return new ConnectorDescriptor(
              connectorId,
              connectorVersion,
              sourceSystem,
              Objects.requireNonNullElse(supportedSourceVersions, List.of()),
              Objects.requireNonNullElse(supportedProtocols, List.of()),
              Objects.requireNonNullElse(supportedEntities, List.of()),
              authenticationMethod,
              Objects.requireNonNullElse(requiredNetworkAccess, List.of()),
              dataClassification,
              pollingOrEventMode,
              retryPolicy,
              rateLimitPolicy,
              fieldMappingVersion,
              testSuiteVersion,
              owner,
              supportSla)
          .validate();
    }
  }
}
