package br.gov.sus.nexus.core.platform.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * {@link AuthorizationPolicy} que delega ao OPA ({@code POST
 * {sus.authz.opa-url}/v1/data/sus/authz/decision}) montando o input exatamente conforme {@code
 * policies/README.md}: {@code subject} (id, roles, tenant, cnes, teams, microareas, client_type),
 * {@code action}, {@code resource} (type, tenant, domain, sensitivity, citizen_*, assignee) e
 * {@code context} (purpose, break_glass, justification, channel). Devolve as obrigações da
 * política.
 *
 * <p><b>Fail-closed</b>: OPA indisponível, timeout, resposta inválida ou sem {@code result} ⇒ deny.
 * Selecionada com {@code sus.authz.mode=opa}; o padrão em dev/test é {@link
 * RoleBasedAuthorizationPolicy}.
 */
@ApplicationScoped
@IfBuildProperty(name = "sus.authz.mode", stringValue = "opa")
public class OpaAuthorizationPolicy implements AuthorizationPolicy {

  private static final Logger LOG = Logger.getLogger(OpaAuthorizationPolicy.class);
  static final String DECISION_PATH = "/v1/data/sus/authz/decision";

  /** Mapeia o sufixo da ação interna ({@code citizen:read}) para a ação do contrato OPA. */
  static final Map<String, String> ACTIONS =
      Map.ofEntries(
          Map.entry("read", "read"),
          Map.entry("search", "read"),
          Map.entry("list", "read"),
          Map.entry("review", "read"),
          Map.entry("register", "write"),
          Map.entry("write", "write"),
          Map.entry("create", "write"),
          Map.entry("reveal_identifier", "reveal_identifier"),
          Map.entry("merge", "merge"),
          Map.entry("unmerge", "unmerge"),
          Map.entry("reprocess", "reprocess"),
          Map.entry("export", "export"),
          Map.entry("transition", "transition_task"));

  /** Atributos de entrada copiados para {@code resource} quando presentes. */
  static final List<String> RESOURCE_ATTRIBUTES =
      List.of(
          "domain",
          "sensitivity",
          "citizen_team",
          "citizen_cnes",
          "citizen_microarea",
          "assignee",
          "citizen_id",
          "created_by");

  @Inject ObjectMapper objectMapper;
  @Inject Instance<CurrentActor> currentActor;

  @ConfigProperty(name = "sus.authz.opa-url", defaultValue = "http://localhost:8181")
  String opaUrl;

  @ConfigProperty(name = "sus.authz.opa-timeout", defaultValue = "PT2S")
  Duration timeout;

  private HttpClient http;

  public OpaAuthorizationPolicy() {}

  /** Construtor para testes (sem CDI). */
  public OpaAuthorizationPolicy(String opaUrl, Duration timeout, ObjectMapper objectMapper) {
    this.opaUrl = opaUrl;
    this.timeout = timeout;
    this.objectMapper = objectMapper;
    this.currentActor = null;
    init();
  }

  @PostConstruct
  void init() {
    this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
  }

  @Override
  public Decision evaluate(Input input) {
    Map<String, Object> body = Map.of("input", buildInput(input, actor()));
    try {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(stripSlash(opaUrl) + DECISION_PATH))
              .timeout(timeout)
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
              .build();
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        LOG.warnf("OPA respondeu HTTP %d — negando (fail-closed)", response.statusCode());
        return Decision.deny("opa_unavailable");
      }
      return parse(objectMapper.readTree(response.body()));
    } catch (IOException e) {
      LOG.warnf("OPA indisponível (%s) — negando (fail-closed)", e.getClass().getSimpleName());
      return Decision.deny("opa_unavailable");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Decision.deny("opa_unavailable");
    } catch (RuntimeException e) {
      LOG.warnf("resposta do OPA inválida (%s) — negando", e.getClass().getSimpleName());
      return Decision.deny("opa_invalid_response");
    }
  }

  Decision parse(JsonNode root) {
    JsonNode result = root == null ? null : root.get("result");
    if (result == null || result.isNull() || !result.has("allow")) {
      return Decision.deny("opa_no_result");
    }
    boolean allow = result.get("allow").asBoolean(false);
    List<String> reasons = new ArrayList<>();
    if (result.has("reasons")) {
      result.get("reasons").forEach(n -> reasons.add(n.asText()));
    }
    Obligations obligations = Obligations.NONE;
    JsonNode o = result.get("obligations");
    if (o != null && o.isObject()) {
      List<String> redact = new ArrayList<>();
      if (o.has("redact_fields")) {
        o.get("redact_fields").forEach(n -> redact.add(n.asText()));
      }
      obligations =
          new Obligations(
              o.path("mask_identifiers").asBoolean(false),
              redact,
              o.path("log_access").asBoolean(true),
              o.path("require_justification").asBoolean(false),
              o.path("alert_dpo").asBoolean(false));
    }
    String version =
        result.hasNonNull("policy_version") ? result.get("policy_version").asText() : null;
    String reason = reasons.isEmpty() ? (allow ? "allow" : "denied_by_policy") : reasons.get(0);
    return new Decision(allow, reason, reasons, obligations, version);
  }

  /** Monta o input do contrato OPA. */
  Map<String, Object> buildInput(Input input, CurrentActor actor) {
    Map<String, Object> subject = new LinkedHashMap<>();
    subject.put("id", input.actorId());
    subject.put("roles", new ArrayList<>(input.roles()).stream().sorted().toList());
    subject.put("tenant", input.tenantId());
    subject.put("cnes", actor == null ? List.of() : actor.cnes());
    subject.put("teams", actor == null ? List.of() : actor.teams());
    subject.put("microareas", actor == null ? List.of() : actor.microareas());
    CurrentActor.ClientType clientType =
        actor == null
            ? CurrentActor.inferClientType(input.actorId(), input.roles())
            : actor.clientType();
    subject.put("client_type", clientType.wire());

    Map<String, Object> resource = new LinkedHashMap<>();
    resource.put("type", input.resourceType());
    resource.put("tenant", input.tenantId());
    if (input.resourceId() != null) {
      resource.put("id", input.resourceId());
    }
    for (String key : RESOURCE_ATTRIBUTES) {
      Object v = input.attributes().get(key);
      if (v != null) {
        resource.put(key, v);
      }
    }

    Map<String, Object> context = new LinkedHashMap<>();
    context.put("purpose", input.purpose() == null ? null : input.purpose().wire());
    context.put("break_glass", actor != null && actor.breakGlass());
    context.put(
        "break_glass_justification",
        actor == null || actor.breakGlassJustification() == null
            ? ""
            : actor.breakGlassJustification());
    context.put(
        "channel",
        switch (clientType) {
          case AGENT -> "agent";
          case SERVICE -> "api";
          case USER -> "web";
        });

    Map<String, Object> in = new LinkedHashMap<>();
    in.put("subject", subject);
    in.put("action", opaAction(input.action()));
    in.put("resource", resource);
    in.put("context", context);
    return in;
  }

  static String opaAction(String action) {
    if (action == null) {
      return "read";
    }
    String suffix = action.contains(":") ? action.substring(action.indexOf(':') + 1) : action;
    return ACTIONS.getOrDefault(suffix, suffix);
  }

  private CurrentActor actor() {
    if (currentActor == null || currentActor.isUnsatisfied()) {
      return null;
    }
    try {
      CurrentActor a = currentActor.get();
      a.actorId(); // força resolução do proxy request-scoped
      return a;
    } catch (ContextNotActiveException e) {
      return null;
    }
  }

  private static String stripSlash(String url) {
    return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
  }
}
