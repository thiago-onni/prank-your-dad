package br.gov.sus.nexus.fhir.validation;

import static org.assertj.core.api.Assertions.assertThat;

import br.gov.sus.nexus.fhir.codec.HapiR4JsonCodec;
import org.hl7.fhir.r4.model.Resource;
import org.junit.jupiter.api.Test;

class CardinalityCheckerTest {

  private final HapiR4JsonCodec codec = new HapiR4JsonCodec();

  @Test
  void modelsOnlyCarryMaxCardinality() {
    // os modelos r4 declaram min=0: ausência de Patient.link.other é tarefa do validador oficial
    Resource r = codec.parse("{\"resourceType\":\"Patient\",\"link\":[{\"type\":\"seealso\"}]}");
    assertThat(CardinalityChecker.check(r)).isEmpty();
    org.hl7.fhir.r4.model.Patient p = (org.hl7.fhir.r4.model.Patient) r;
    assertThat(
            p.getLink().get(0).children().stream()
                .filter(c -> "other".equals(c.getName()))
                .findFirst()
                .orElseThrow()
                .getMaxCardinality())
        .isEqualTo(1);
  }

  @Test
  void validResourceHasNoIssues() {
    Resource r =
        codec.parse(
            "{\"resourceType\":\"Patient\",\"name\":[{\"family\":\"X\"}],\"link\":[{\"other\":{\"reference\":\"Patient/1\"},\"type\":\"seealso\"}]}");
    assertThat(CardinalityChecker.check(r)).isEmpty();
  }
}
