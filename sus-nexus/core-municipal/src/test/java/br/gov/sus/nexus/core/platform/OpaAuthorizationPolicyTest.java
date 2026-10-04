package br.gov.sus.nexus.core.platform;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import br.gov.sus.nexus.core.platform.security.AuthorizationPolicy;
import br.gov.sus.nexus.core.platform.security.OpaAuthorizationPolicy;
import br.gov.sus.nexus.core.platform.security.Purpose;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Cliente OPA (sem Quarkus): input conforme policies/README.md, obrigações e fail-closed. */
class OpaAuthorizationPolicyTest {

  static WireMockServer opa;
  static OpaAuthorizationPolicy policy;

  @BeforeAll
  static void start() {
    opa = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    opa.start();
    policy = new OpaAuthorizationPolicy(opa.baseUrl(), Duration.ofSeconds(2), new ObjectMapper());
  }

  @AfterAll
  static void stop() {
    opa.stop();
  }

  @BeforeEach
  void reset() {
    opa.resetAll();
  }

  static AuthorizationPolicy.Input input(String action, Set<String> roles) {
    return new AuthorizationPolicy.Input(
        "ibge_3143302",
        "u1",
        roles,
        action,
        "timeline_event",
        "tle_1",
        Purpose.CARE_COORDINATION,
        Map.of("domain", "aps", "sensitivity", "restricted", "citizen_team", "ine_0001"));
  }

  @Test
  void allowWithObligationsAndContractShapedInput() {
    opa.stubFor(
        post(urlEqualTo("/v1/data/sus/authz/decision"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"result\":{\"allow\":true,\"reasons\":[\"acs.read_microarea\"],"
                            + "\"obligations\":{\"mask_identifiers\":true,\"redact_fields\":[\"diagnoses\",\"results\"],"
                            + "\"log_access\":true,\"require_justification\":false,\"alert_dpo\":false},"
                            + "\"policy_version\":\"1.0.0\"}}")));
    AuthorizationPolicy.Decision d = policy.evaluate(input("timeline:read", Set.of("acs")));
    assertThat(d.allowed()).isTrue();
    assertThat(d.reasons()).containsExactly("acs.read_microarea");
    assertThat(d.obligations().maskIdentifiers()).isTrue();
    assertThat(d.obligations().redactFields()).containsExactly("diagnoses", "results");
    assertThat(d.policyVersion()).isEqualTo("1.0.0");

    opa.verify(
        postRequestedFor(urlEqualTo("/v1/data/sus/authz/decision"))
            .withHeader("Content-Type", equalTo("application/json"))
            .withRequestBody(matchingJsonPath("$.input.subject.id", equalTo("u1")))
            .withRequestBody(matchingJsonPath("$.input.subject.roles[0]", equalTo("acs")))
            .withRequestBody(matchingJsonPath("$.input.subject.tenant", equalTo("ibge_3143302")))
            .withRequestBody(matchingJsonPath("$.input.subject.client_type", equalTo("user")))
            .withRequestBody(matchingJsonPath("$.input.action", equalTo("read")))
            .withRequestBody(matchingJsonPath("$.input.resource.type", equalTo("timeline_event")))
            .withRequestBody(matchingJsonPath("$.input.resource.tenant", equalTo("ibge_3143302")))
            .withRequestBody(matchingJsonPath("$.input.resource.domain", equalTo("aps")))
            .withRequestBody(
                matchingJsonPath("$.input.resource.sensitivity", equalTo("restricted")))
            .withRequestBody(matchingJsonPath("$.input.resource.citizen_team", equalTo("ine_0001")))
            .withRequestBody(
                matchingJsonPath("$.input.context.purpose", equalTo("care_coordination")))
            .withRequestBody(matchingJsonPath("$.input.context.break_glass", equalTo("false")))
            .withRequestBody(matchingJsonPath("$.input.context.channel", equalTo("web"))));
  }

  @Test
  void denyCarriesReasonCodes() {
    opa.stubFor(
        post(urlEqualTo("/v1/data/sus/authz/decision"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"result\":{\"allow\":false,\"reasons\":[\"purpose_not_allowed_for_roles\"],\"obligations\":{}}}")));
    AuthorizationPolicy.Decision d =
        policy.evaluate(input("citizen:reveal_identifier", Set.of("gestor")));
    assertThat(d.allowed()).isFalse();
    assertThat(d.reason()).isEqualTo("purpose_not_allowed_for_roles");
    opa.verify(
        postRequestedFor(urlEqualTo("/v1/data/sus/authz/decision"))
            .withRequestBody(matchingJsonPath("$.input.action", equalTo("reveal_identifier"))));
  }

  @Test
  void failsClosedWhenOpaIsUnavailableOrInvalid() {
    opa.stubFor(
        post(urlEqualTo("/v1/data/sus/authz/decision")).willReturn(aResponse().withStatus(500)));
    assertThat(policy.evaluate(input("timeline:read", Set.of("acs"))).allowed()).isFalse();

    opa.stubFor(
        post(urlEqualTo("/v1/data/sus/authz/decision"))
            .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("{}")));
    AuthorizationPolicy.Decision noResult = policy.evaluate(input("timeline:read", Set.of("acs")));
    assertThat(noResult.allowed()).isFalse();
    assertThat(noResult.reason()).isEqualTo("opa_no_result");

    opa.stubFor(
        post(urlEqualTo("/v1/data/sus/authz/decision"))
            .willReturn(
                aResponse().withHeader("Content-Type", "application/json").withBody("not json")));
    assertThat(policy.evaluate(input("timeline:read", Set.of("acs"))).allowed()).isFalse();

    OpaAuthorizationPolicy down =
        new OpaAuthorizationPolicy(
            "http://127.0.0.1:1", Duration.ofMillis(500), new ObjectMapper());
    AuthorizationPolicy.Decision d = down.evaluate(input("timeline:read", Set.of("acs")));
    assertThat(d.allowed()).isFalse();
    assertThat(d.reason()).isEqualTo("opa_unavailable");
  }
}
