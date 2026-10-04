package br.gov.sus.nexus.core.sharedkernel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.gov.sus.nexus.core.support.Fixtures;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CnsTest {

  @RepeatedTest(50)
  void acceptsGeneratedProvisionalCns() {
    String cns = Fixtures.randomProvisionalCns();
    assertThat(Cns.isValid(cns)).as(cns).isTrue();
    assertThat(cns).hasSize(15).matches("^[789].*");
  }

  @RepeatedTest(50)
  void acceptsGeneratedDefinitiveCns() {
    String cns = Fixtures.randomDefinitiveCns();
    assertThat(Cns.isValid(cns)).as(cns).isTrue();
    assertThat(cns).hasSize(15).matches("^[12].*");
  }

  @Test
  void definitiveCnsFollowsPisAlgorithm() {
    // PIS 12345678901: soma ponderada 15..5 = 440, resto 0, dv 11→0 → 12345678901 000 0
    assertThat(Cns.fromPis("12345678901")).isEqualTo("123456789010000");
    assertThat(Cns.isValid("123456789010000")).isTrue();
    // PIS cujo DV cai em 10 usa o sufixo 001 (soma + 2)
    assertThat(Cns.fromPis("10000000006")).isEqualTo("100000000060018");
    assertThat(Cns.isValid("100000000060018")).isTrue();
  }

  @Test
  void rejectsCorruptedDigit() {
    String cns = Fixtures.randomProvisionalCns();
    char last = cns.charAt(14);
    char changed = (char) ('0' + ((last - '0' + 1) % 10));
    String corrupted = cns.substring(0, 14) + changed;
    assertThat(Cns.isValid(corrupted)).isFalse();
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "123", "000000000000000", "300000000000000", "12345678901234a"})
  void rejectsInvalidShapes(String value) {
    assertThat(Cns.isValid(value)).isFalse();
    assertThat(Cns.parse(value)).isEmpty();
  }

  @Test
  void acceptsFormattedInput() {
    String cns = Fixtures.randomProvisionalCns();
    String formatted =
        cns.substring(0, 3)
            + " "
            + cns.substring(3, 7)
            + " "
            + cns.substring(7, 11)
            + " "
            + cns.substring(11);
    assertThat(Cns.parse(formatted)).isPresent().get().extracting(Cns::value).isEqualTo(cns);
  }

  @Test
  void maskKeepsOnlyLastFourDigits() {
    String cns = Fixtures.randomProvisionalCns();
    Cns vo = new Cns(cns);
    assertThat(vo.masked()).isEqualTo("***********" + cns.substring(11));
    assertThat(vo.toString()).doesNotContain(cns.substring(0, 11));
  }

  @Test
  void constructorRejectsInvalid() {
    assertThatThrownBy(() -> new Cns("123456789012345"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
