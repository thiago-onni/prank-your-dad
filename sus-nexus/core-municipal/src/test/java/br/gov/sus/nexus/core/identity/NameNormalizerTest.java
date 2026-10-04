package br.gov.sus.nexus.core.identity;

import static org.assertj.core.api.Assertions.assertThat;

import br.gov.sus.nexus.core.identity.domain.ContactNormalizer;
import br.gov.sus.nexus.core.identity.domain.NameNormalizer;
import org.junit.jupiter.api.Test;

class NameNormalizerTest {

  @Test
  void uppercasesStripsAccentsAndParticles() {
    assertThat(NameNormalizer.normalize("  Maria  da Conceição   dos Santos "))
        .isEqualTo("MARIA CONCEICAO SANTOS");
    assertThat(NameNormalizer.normalize("José de Souza Júnior")).isEqualTo("JOSE SOUZA JR");
    assertThat(NameNormalizer.normalize("Antônio D'Ávila e Silva"))
        .isEqualTo("ANTONIO D AVILA SILVA");
  }

  @Test
  void handlesEmptyAndNull() {
    assertThat(NameNormalizer.normalize(null)).isNull();
    assertThat(NameNormalizer.normalize("   ")).isNull();
    assertThat(NameNormalizer.normalize("123")).isNull();
  }

  @Test
  void tokens() {
    String n = NameNormalizer.normalize("Ana Paula de Oliveira");
    assertThat(NameNormalizer.firstToken(n)).isEqualTo("ANA");
    assertThat(NameNormalizer.lastToken(n)).isEqualTo("OLIVEIRA");
  }

  @Test
  void phoneToE164() {
    assertThat(ContactNormalizer.phone("(38) 99999-1234")).isEqualTo("+5538999991234");
    assertThat(ContactNormalizer.phone("038 3222-1234")).isEqualTo("+553832221234");
    assertThat(ContactNormalizer.phone("+55 38 99999-1234")).isEqualTo("+5538999991234");
    assertThat(ContactNormalizer.normalize("email", " Maria@Example.ORG "))
        .isEqualTo("maria@example.org");
  }
}
