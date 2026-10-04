package br.gov.sus.nexus.core.sharedkernel;

import static org.assertj.core.api.Assertions.assertThat;

import br.gov.sus.nexus.core.support.Fixtures;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CpfTest {

  @RepeatedTest(50)
  void acceptsGeneratedCpf() {
    String cpf = Fixtures.randomCpf();
    assertThat(Cpf.isValid(cpf)).as(cpf).isTrue();
  }

  @Test
  void knownValidCpf() {
    assertThat(Cpf.isValid("529.982.247-25")).isTrue();
    assertThat(Cpf.isValid("52998224725")).isTrue();
    assertThat(Cpf.withCheckDigits("529982247")).isEqualTo("52998224725");
  }

  @ParameterizedTest
  @ValueSource(strings = {"52998224726", "11111111111", "00000000000", "123", "", "5299822472a"})
  void rejectsInvalid(String value) {
    assertThat(Cpf.isValid(value)).isFalse();
  }

  @Test
  void masked() {
    assertThat(new Cpf("52998224725").masked()).isEqualTo("***.***.***-25");
  }
}
