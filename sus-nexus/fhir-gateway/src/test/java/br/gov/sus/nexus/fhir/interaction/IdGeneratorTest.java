package br.gov.sus.nexus.fhir.interaction;

import static org.assertj.core.api.Assertions.assertThat;

import br.gov.sus.nexus.fhir.FhirConstants;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class IdGeneratorTest {

  @Test
  void generatesValidUniqueFhirIds() {
    Set<String> ids = new HashSet<>();
    for (int i = 0; i < 1000; i++) {
      String id = IdGenerator.ulid();
      assertThat(id).hasSize(26).matches(FhirConstants.FHIR_ID_PATTERN);
      ids.add(id);
    }
    assertThat(ids).hasSize(1000);
  }

  @Test
  void etagMatching() {
    assertThat(FhirInteractionService.etagMatches("W/\"3\"", 3)).isTrue();
    assertThat(FhirInteractionService.etagMatches("\"3\"", 3)).isTrue();
    assertThat(FhirInteractionService.etagMatches("*", 9)).isTrue();
    assertThat(FhirInteractionService.etagMatches("W/\"2\", W/\"3\"", 3)).isTrue();
    assertThat(FhirInteractionService.etagMatches("W/\"2\"", 3)).isFalse();
    assertThat(FhirInteractionService.etagMatches(null, 3)).isFalse();
  }
}
