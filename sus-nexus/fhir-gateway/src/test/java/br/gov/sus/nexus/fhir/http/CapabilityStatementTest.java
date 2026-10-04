package br.gov.sus.nexus.fhir.http;

import static br.gov.sus.nexus.fhir.FhirTestSupport.FHIR;
import static br.gov.sus.nexus.fhir.FhirTestSupport.clinician;
import static org.assertj.core.api.Assertions.assertThat;

import br.gov.sus.nexus.fhir.capability.CapabilityRegistry;
import br.gov.sus.nexus.fhir.capability.Interaction;
import br.gov.sus.nexus.fhir.capability.ResourceCapability;
import br.gov.sus.nexus.fhir.capability.SearchParamDef;
import br.gov.sus.nexus.fhir.codec.FhirCodec;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.hl7.fhir.r4.model.CapabilityStatement;
import org.hl7.fhir.r4.model.CapabilityStatement.CapabilityStatementRestResourceComponent;
import org.junit.jupiter.api.Test;

@QuarkusTest
class CapabilityStatementTest {

  @Inject CapabilityRegistry registry;
  @Inject FhirCodec codec;

  @Test
  void metadataReflectsRegistryExactly() {
    String body =
        RestAssured.given()
            .accept("application/fhir+json")
            .when()
            .get(FHIR + "/metadata")
            .then()
            .statusCode(200)
            .contentType("application/fhir+json")
            .extract()
            .asString();
    CapabilityStatement cs = (CapabilityStatement) codec.parse(body);
    assertThat(cs.getFhirVersion().toCode()).isEqualTo("4.0.1");
    List<CapabilityStatementRestResourceComponent> resources = cs.getRestFirstRep().getResource();

    Set<String> announced =
        resources.stream()
            .map(CapabilityStatementRestResourceComponent::getType)
            .collect(Collectors.toSet());
    Set<String> registered =
        registry.all().stream().map(ResourceCapability::type).collect(Collectors.toSet());
    assertThat(announced).isEqualTo(registered);

    for (CapabilityStatementRestResourceComponent res : resources) {
      ResourceCapability cap = registry.resource(res.getType()).orElseThrow();
      Set<String> codes =
          res.getInteraction().stream().map(i -> i.getCode().toCode()).collect(Collectors.toSet());
      Set<String> expected =
          cap.interactions().stream().map(Interaction::code).collect(Collectors.toSet());
      assertThat(codes).as(res.getType()).isEqualTo(expected);

      Set<String> params =
          res.getSearchParam().stream().map(p -> p.getName()).collect(Collectors.toSet());
      Set<String> expectedParams = new java.util.HashSet<>(cap.searchParams().keySet());
      if (cap.supports(Interaction.SEARCH_TYPE)) {
        expectedParams.addAll(registry.commonParams().stream().map(SearchParamDef::name).toList());
      }
      assertThat(params).as(res.getType()).isEqualTo(expectedParams);
      cap.profile().ifPresent(p -> assertThat(res.getProfile()).isEqualTo(p));
      assertThat(res.getSearchInclude().stream().map(i -> i.getValue()).toList())
          .as(res.getType() + " searchInclude")
          .containsExactlyElementsOf(
              cap.includes().stream().map(i -> res.getType() + ":" + i).toList());
    }
    assertThat(cs.getRestFirstRep().getOperation().stream().map(o -> o.getName()).toList())
        .containsExactlyElementsOf(CapabilityRegistry.SYSTEM_OPERATIONS);
  }

  @Test
  void unregisteredTypeAndInteractionAreRejected() {
    clinician()
        .get(FHIR + "/Observation/123")
        .then()
        .statusCode(404)
        .body("issue[0].code", org.hamcrest.Matchers.equalTo("not-supported"));
    clinician()
        .body("{\"resourceType\":\"AuditEvent\"}")
        .post(FHIR + "/AuditEvent")
        .then()
        .statusCode(405)
        .body("issue[0].code", org.hamcrest.Matchers.equalTo("not-supported"));
    clinician()
        .get(FHIR + "/nope/path/here")
        .then()
        .statusCode(404)
        .body("resourceType", org.hamcrest.Matchers.equalTo("OperationOutcome"));
  }
}
