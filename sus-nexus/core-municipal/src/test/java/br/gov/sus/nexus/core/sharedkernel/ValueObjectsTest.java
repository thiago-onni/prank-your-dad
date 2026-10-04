package br.gov.sus.nexus.core.sharedkernel;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ValueObjectsTest {

  @Test
  void cnesAndCbo() {
    assertThat(Cnes.isValid("1234567")).isTrue();
    assertThat(Cnes.isValid("123456")).isFalse();
    assertThat(Cnes.isValid("12345678")).isFalse();
    assertThat(Cbo.isValid("225125")).isTrue();
    assertThat(Cbo.isValid("22512")).isFalse();
  }

  @Test
  void competence() {
    assertThat(Competence.isValid("202403")).isTrue();
    assertThat(Competence.isValid("202413")).isFalse();
    assertThat(Competence.isValid("2024-03")).isFalse();
    Competence c = new Competence("202403");
    assertThat(c.isWithin("202401", null)).isTrue();
    assertThat(c.isWithin("202401", "202402")).isFalse();
    assertThat(c.isWithin(null, "202403")).isTrue();
    assertThat(c.compareTo(new Competence("202402"))).isPositive();
    assertThat(c.toYearMonth().getMonthValue()).isEqualTo(3);
  }

  @Test
  void masks() {
    assertThat(Masks.cpf("52998224725")).isEqualTo("***.***.***-25");
    assertThat(Masks.cns("898001234565678")).isEqualTo("***********5678");
    assertThat(Masks.forSystem("CNS", "898001234565678")).isEqualTo("***********5678");
    assertThat(Masks.forSystem("PEC", "ABC123456")).endsWith("456").startsWith("***");
    assertThat(Masks.phone("+5538999991234")).endsWith("1234").doesNotContain("9999");
    assertThat(Masks.email("maria.silva@example.org")).isEqualTo("m***@example.org");
    assertThat(Masks.name("Maria da Silva Souza")).isEqualTo("Maria d. S. S.");
  }

  @Test
  void identifierHashIsPerTenantAndStable() {
    IdentifierHash h =
        new IdentifierHash("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
    String a1 = h.hash("ibge_3143302", "CNS", "898001234565678");
    String a2 = h.hash("ibge_3143302", "CNS", "898 0012 3456 5678");
    String b = h.hash("ibge_3106200", "CNS", "898001234565678");
    assertThat(a1).matches("^[a-f0-9]{64}$").isEqualTo(a2).isNotEqualTo(b);
    assertThat(h.hash("ibge_3143302", "CPF", "529.982.247-25"))
        .isEqualTo(h.hash("ibge_3143302", "CPF", "52998224725"));
  }
}
