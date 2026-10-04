package br.gov.sus.nexus.fhir.http;

import static br.gov.sus.nexus.fhir.FhirTestSupport.FHIR;
import static br.gov.sus.nexus.fhir.FhirTestSupport.TENANT_A;
import static br.gov.sus.nexus.fhir.FhirTestSupport.as;
import static br.gov.sus.nexus.fhir.FhirTestSupport.patientJson;
import static br.gov.sus.nexus.fhir.FhirTestSupport.randomCns;
import static org.assertj.core.api.Assertions.assertThat;

import br.gov.sus.nexus.fhir.FhirConstants;
import br.gov.sus.nexus.fhir.codec.FhirCodec;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.hl7.fhir.r4.model.AuditEvent;
import org.hl7.fhir.r4.model.Bundle;
import org.junit.jupiter.api.Test;

@QuarkusTest
class AuditEventTest {

  @Inject FhirCodec codec;

  @Test
  void everyInteractionProducesAnAuditEvent() {
    String id =
        as("dr-house", "user/*.read user/*.write", TENANT_A)
            .header(FhirConstants.HEADER_PURPOSE_OF_USE, "care_coordination")
            .body(patientJson(randomCns(), "Audit", "Um", "1960-06-06"))
            .post(FHIR + "/Patient")
            .then()
            .statusCode(201)
            .extract()
            .jsonPath()
            .getString("id");
    as("dr-house", "user/*.read", TENANT_A)
        .header(FhirConstants.HEADER_PURPOSE_OF_USE, "care_coordination")
        .get(FHIR + "/Patient/" + id)
        .then()
        .statusCode(200);
    // acesso negado também é auditado
    as("intruder", "user/Organization.read", TENANT_A)
        .get(FHIR + "/Patient/" + id)
        .then()
        .statusCode(403);

    String body =
        as("auditor", "user/AuditEvent.read", TENANT_A)
            .queryParam("entity", "Patient/" + id)
            .get(FHIR + "/AuditEvent")
            .then()
            .statusCode(200)
            .extract()
            .asString();
    Bundle bundle = (Bundle) codec.parse(body);
    assertThat(bundle.getEntry()).hasSizeGreaterThanOrEqualTo(3);

    var events = bundle.getEntry().stream().map(e -> (AuditEvent) e.getResource()).toList();
    assertThat(events)
        .anySatisfy(
            ev -> {
              assertThat(ev.getSubtypeFirstRep().getCode()).isEqualTo("create");
              assertThat(ev.getAction().toCode()).isEqualTo("C");
              assertThat(ev.getOutcome().toCode()).isEqualTo("0");
              assertThat(ev.getPurposeOfEventFirstRep().getCodingFirstRep().getCode())
                  .isEqualTo("care_coordination");
              assertThat(ev.getAgentFirstRep().getWho().getIdentifier().getValue())
                  .isEqualTo("dr-house");
              assertThat(ev.getSource().getSite()).isEqualTo(TENANT_A);
              assertThat(ev.getEntityFirstRep().getWhat().getReference())
                  .startsWith("Patient/" + id);
            });
    assertThat(events)
        .anySatisfy(
            ev -> {
              assertThat(ev.getSubtypeFirstRep().getCode()).isEqualTo("read");
              assertThat(ev.getAgentFirstRep().getWho().getIdentifier().getValue())
                  .isEqualTo("intruder");
              assertThat(ev.getOutcome().toCode()).isEqualTo("4");
              assertThat(ev.getOutcomeDesc()).contains("scope-missing");
            });
  }
}
