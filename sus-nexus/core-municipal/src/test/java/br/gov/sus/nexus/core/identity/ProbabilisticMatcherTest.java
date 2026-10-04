package br.gov.sus.nexus.core.identity;

import static org.assertj.core.api.Assertions.assertThat;

import br.gov.sus.nexus.core.identity.domain.ConflictDetector;
import br.gov.sus.nexus.core.identity.domain.MatchEvidence;
import br.gov.sus.nexus.core.identity.domain.MatchScore;
import br.gov.sus.nexus.core.identity.domain.MatcherParameters;
import br.gov.sus.nexus.core.identity.domain.PersonFeatures;
import br.gov.sus.nexus.core.identity.domain.ProbabilisticMatcher;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ProbabilisticMatcherTest {

  static final MatcherParameters PARAMS =
      new MatcherParameters(
          "mpi-rules-test",
          22.0,
          12.0,
          0.92,
          0.85,
          Map.of(
              "name", new MatcherParameters.FieldWeight(0.95, 0.001),
              "mother_name", new MatcherParameters.FieldWeight(0.90, 0.001),
              "birthdate", new MatcherParameters.FieldWeight(0.98, 0.0001),
              "sex", new MatcherParameters.FieldWeight(0.99, 0.5),
              "phone", new MatcherParameters.FieldWeight(0.70, 0.001)));

  final ProbabilisticMatcher matcher = new ProbabilisticMatcher(PARAMS);

  static PersonFeatures person(
      String id, String name, String mother, LocalDate birth, String sex, Set<String> phones) {
    return person(id, name, mother, birth, sex, phones, Map.of());
  }

  static PersonFeatures person(
      String id,
      String name,
      String mother,
      LocalDate birth,
      String sex,
      Set<String> phones,
      Map<String, String> ids) {
    return new PersonFeatures(id, name, mother, birth, sex, phones, ids);
  }

  @Test
  void fellegiSunterWeights() {
    MatcherParameters.FieldWeight w = PARAMS.weight("name");
    assertThat(w.agreeWeight()).isCloseTo(9.89, org.assertj.core.data.Offset.offset(0.05));
    assertThat(w.disagreeWeight()).isCloseTo(-4.32, org.assertj.core.data.Offset.offset(0.05));
  }

  @Test
  void similarNamesSameBirthdateIsProbable() {
    PersonFeatures a =
        person(
            null,
            "MARIA APARECIDA SILVA",
            "JOANA SILVA",
            LocalDate.of(1990, 5, 5),
            "female",
            Set.of());
    PersonFeatures b =
        person(
            "cit_1",
            "MARIA APARECIDA SILVEIRA",
            "JOANNA SILVA",
            LocalDate.of(1990, 5, 5),
            "female",
            Set.of());
    MatchScore score = matcher.compare(a, b);
    assertThat(score.score()).isGreaterThan(22.0);
    assertThat(matcher.classify(score.score()))
        .isEqualTo(ProbabilisticMatcher.Classification.PROBABLE);
    assertThat(score.evidences())
        .extracting(MatchEvidence::attribute)
        .containsExactly("name", "mother_name", "birthdate", "sex", "phone");
    assertThat(score.evidences().get(4).agreement()).isEqualTo(MatchEvidence.Agreement.MISSING);
  }

  @Test
  void differentPeopleIsNew() {
    PersonFeatures a =
        person(
            null,
            "MARIA APARECIDA SILVA",
            "JOANA SILVA",
            LocalDate.of(1990, 5, 5),
            "female",
            Set.of("+5538999990000"));
    PersonFeatures b =
        person(
            "cit_2",
            "CARLOS EDUARDO PEREIRA",
            "RITA PEREIRA",
            LocalDate.of(1975, 1, 20),
            "male",
            Set.of("+5538988880000"));
    MatchScore score = matcher.compare(a, b);
    assertThat(score.score()).isNegative();
    assertThat(matcher.classify(score.score())).isEqualTo(ProbabilisticMatcher.Classification.NEW);
  }

  @Test
  void partialBirthdateAndNameGivesPending() {
    PersonFeatures a =
        person(null, "JOAO PEDRO ALMEIDA", null, LocalDate.of(1990, 5, 15), "male", Set.of());
    PersonFeatures b =
        person("cit_3", "JOAO PEDRO ALMEIDA", null, LocalDate.of(1990, 5, 1), "male", Set.of());
    MatchScore score = matcher.compare(a, b);
    // nome concorda (~9.9) + data parcial (~6.6) + sexo (~1) = ~17.5 → entre limiares
    assertThat(score.score()).isBetween(12.0, 22.0);
    assertThat(matcher.classify(score.score()))
        .isEqualTo(ProbabilisticMatcher.Classification.PENDING);
  }

  @Test
  void conflictDetectorFlagsBirthdateAndIdentifierDivergence() {
    PersonFeatures incoming =
        person(
            null,
            "A",
            "B",
            LocalDate.of(1980, 1, 1),
            "female",
            Set.of(),
            Map.of("CNS", "h1", "CPF", "c1"));
    PersonFeatures existing =
        person(
            "cit_9",
            "A",
            "B",
            LocalDate.of(1981, 2, 2),
            "female",
            Set.of(),
            Map.of("CNS", "h1", "CPF", "c2"));
    ConflictDetector.Result r = ConflictDetector.detect(incoming, existing, Map.of("CNS", "cit_9"));
    assertThat(r.hasConflicts()).isTrue();
    assertThat(r.conflicts()).containsExactlyInAnyOrder("CPF", "birthdate");

    ConflictDetector.Result ok =
        ConflictDetector.detect(
            incoming,
            person(
                "cit_9",
                "A",
                "B",
                LocalDate.of(1980, 1, 1),
                "female",
                Set.of(),
                Map.of("CNS", "h1")),
            Map.of("CNS", "cit_9"));
    assertThat(ok.hasConflicts()).isFalse();

    ConflictDetector.Result owned =
        ConflictDetector.detect(
            incoming,
            person("cit_9", "A", "B", LocalDate.of(1980, 1, 1), "female", Set.of(), Map.of()),
            Map.of("CPF", "cit_other"));
    assertThat(owned.conflicts()).containsExactly("CPF_owned_by_other");
  }
}
