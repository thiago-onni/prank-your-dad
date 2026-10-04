package br.gov.sus.nexus.fhir.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class FhirDatesTest {

  @Test
  void expandsPrecision() {
    assertThat(FhirDates.parse("2020").orElseThrow())
        .isEqualTo(
            new FhirDates.Range(
                Instant.parse("2020-01-01T00:00:00Z"), Instant.parse("2021-01-01T00:00:00Z")));
    assertThat(FhirDates.parse("2020-02").orElseThrow().high())
        .isEqualTo(Instant.parse("2020-03-01T00:00:00Z"));
    assertThat(FhirDates.parse("2020-02-29").orElseThrow().high())
        .isEqualTo(Instant.parse("2020-03-01T00:00:00Z"));
    assertThat(FhirDates.parse("2020-02-29T10:15:00-03:00").orElseThrow().low())
        .isEqualTo(Instant.parse("2020-02-29T13:15:00Z"));
    assertThat(FhirDates.parse("2020-02-29T10:15:00.250Z").orElseThrow().high())
        .isEqualTo(Instant.parse("2020-02-29T10:15:00.251Z"));
  }

  @Test
  void parsesSearchPrefixes() {
    FhirDates.SearchValue ge = FhirDates.parseSearchValue("ge2020-01-01").orElseThrow();
    assertThat(ge.prefix()).isEqualTo(FhirDates.Prefix.GE);
    assertThat(FhirDates.parseSearchValue("2020-01-01").orElseThrow().prefix())
        .isEqualTo(FhirDates.Prefix.EQ);
    assertThat(FhirDates.parseSearchValue("xx2020")).isEmpty();
    assertThat(FhirDates.parseSearchValue("2020-13-01")).isEmpty();
  }

  @Test
  void normalizesStrings() {
    assertThat(StringNormalizer.normalize("  José   da  SILVA ")).isEqualTo("jose da silva");
    assertThat(StringNormalizer.normalize(null)).isEmpty();
  }
}
