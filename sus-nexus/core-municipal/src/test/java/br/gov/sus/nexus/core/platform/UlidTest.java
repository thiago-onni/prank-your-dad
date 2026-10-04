package br.gov.sus.nexus.core.platform;

import static org.assertj.core.api.Assertions.assertThat;

import br.gov.sus.nexus.core.platform.ids.Ulid;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class UlidTest {

  @Test
  void generatesPrefixedCrockfordIds() {
    String id = Ulid.generate(Ulid.CITIZEN);
    assertThat(id).matches("^cit_[0-9A-HJKMNP-TV-Z]{26}$");
    assertThat(Ulid.isValid(Ulid.CITIZEN, id)).isTrue();
    assertThat(Ulid.isValid(Ulid.EVENT, id)).isFalse();
  }

  @Test
  void isUniqueAndRoughlyTimeOrdered() throws InterruptedException {
    Set<String> ids = new HashSet<>();
    for (int i = 0; i < 1000; i++) {
      ids.add(Ulid.generate());
    }
    assertThat(ids).hasSize(1000);
    String a = Ulid.generate();
    Thread.sleep(5);
    String b = Ulid.generate();
    assertThat(a.compareTo(b)).isNegative();
  }
}
