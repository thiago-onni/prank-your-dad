package br.gov.sus.nexus.core.audit.application;

import br.gov.sus.nexus.core.audit.api.AuditEntry;
import br.gov.sus.nexus.core.audit.api.AuditService;
import br.gov.sus.nexus.core.audit.infrastructure.AuditLogRepository;
import br.gov.sus.nexus.core.platform.correlation.CorrelationId;
import br.gov.sus.nexus.core.platform.ids.Ulid;
import br.gov.sus.nexus.core.platform.security.CurrentActor;
import br.gov.sus.nexus.core.platform.tenant.TenantContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Audit log encadeado: {@code hash = SHA-256(prev_hash + conteúdo canônico)}. O primeiro registro
 * de cada tenant encadeia em {@code GENESIS}. A verificação da cadeia pode ser feita recomputando
 * sequencialmente por {@code (tenant_id, seq)}.
 */
@ApplicationScoped
public class AuditServiceImpl implements AuditService {

  public static final String GENESIS = "GENESIS";

  @Inject AuditLogRepository repository;
  @Inject TenantContext tenantContext;
  @Inject CurrentActor currentActor;
  @Inject CorrelationId correlationId;
  @Inject ObjectMapper objectMapper;

  @Override
  public String record(AuditEntry entry) {
    String tenant = tenantContext.require();
    String id = Ulid.generate(Ulid.AUDIT);
    OffsetDateTime occurredAt =
        OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    String actor = entry.actorId() != null ? entry.actorId() : currentActor.actorId();
    Set<String> roles =
        entry.actorRoles() != null
            ? new TreeSet<>(entry.actorRoles())
            : new TreeSet<>(currentActor.roles());
    String rolesCsv = String.join(",", roles);
    String detailsJson = canonicalJson(entry.details());
    String corr = correlationId.get();

    repository.lockTenantChain(tenant);
    String prevHash = repository.lastHash(tenant).orElse(GENESIS);
    String canonical =
        canonicalContent(
            id,
            tenant,
            occurredAt,
            actor,
            rolesCsv,
            entry.action(),
            entry.resourceType(),
            entry.resourceId(),
            entry.citizenId(),
            entry.reason(),
            detailsJson,
            corr);
    String hash = sha256(prevHash + "\n" + canonical);

    repository.insert(
        id,
        tenant,
        occurredAt,
        actor,
        rolesCsv,
        entry.action(),
        entry.resourceType(),
        entry.resourceId(),
        entry.citizenId(),
        entry.reason(),
        detailsJson,
        corr,
        prevHash,
        hash);
    return id;
  }

  /** Conteúdo canônico (campos separados por \n, nulos como vazio). */
  public static String canonicalContent(
      String id,
      String tenant,
      OffsetDateTime occurredAt,
      String actor,
      String rolesCsv,
      String action,
      String resourceType,
      String resourceId,
      String citizenId,
      String reason,
      String detailsJson,
      String correlationId) {
    return String.join(
        "\n",
        id,
        tenant,
        occurredAt.toInstant().toString(),
        nz(actor),
        nz(rolesCsv),
        nz(action),
        nz(resourceType),
        nz(resourceId),
        nz(citizenId),
        nz(reason),
        nz(detailsJson),
        nz(correlationId));
  }

  public static String sha256(String content) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(md.digest(content.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** JSON canônico de {@code details}: chaves ordenadas recursivamente, sem espaços. */
  public static String canonicalJson(ObjectMapper mapper, Map<String, Object> details) {
    try {
      return mapper.writeValueAsString(canonicalize(details == null ? Map.of() : details));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("falha ao serializar details da auditoria", e);
    }
  }

  @SuppressWarnings("unchecked")
  static Object canonicalize(Object value) {
    if (value instanceof Map<?, ?> map) {
      TreeMap<String, Object> sorted = new TreeMap<>();
      ((Map<String, Object>) map).forEach((k, v) -> sorted.put(k, canonicalize(v)));
      return sorted;
    }
    if (value instanceof java.util.List<?> list) {
      return list.stream().map(AuditServiceImpl::canonicalize).toList();
    }
    return value;
  }

  private String canonicalJson(Map<String, Object> details) {
    return canonicalJson(objectMapper, details);
  }

  private static String nz(String s) {
    return s == null ? "" : s;
  }
}
