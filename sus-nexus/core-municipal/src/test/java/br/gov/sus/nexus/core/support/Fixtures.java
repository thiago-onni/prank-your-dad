package br.gov.sus.nexus.core.support;

import br.gov.sus.nexus.core.sharedkernel.Cns;
import br.gov.sus.nexus.core.sharedkernel.Cpf;
import java.util.Random;

/** Geradores de identificadores válidos (pelos algoritmos oficiais) para testes. */
public final class Fixtures {

  private static final Random RANDOM = new Random();

  private Fixtures() {}

  /** CNS provisório (7/8/9): soma ponderada 15..1 múltipla de 11. */
  public static String randomProvisionalCns() {
    while (true) {
      StringBuilder sb = new StringBuilder();
      sb.append(7 + RANDOM.nextInt(3));
      for (int i = 0; i < 13; i++) {
        sb.append(RANDOM.nextInt(10));
      }
      int sum = 0;
      for (int i = 0; i < 14; i++) {
        sum += (sb.charAt(i) - '0') * (15 - i);
      }
      int d = (11 - (sum % 11)) % 11;
      if (d == 10) {
        continue;
      }
      String cns = sb.append(d).toString();
      if (Cns.isValid(cns)) {
        return cns;
      }
    }
  }

  /** CNS definitivo (1/2) derivado de um PIS aleatório. */
  public static String randomDefinitiveCns() {
    while (true) {
      StringBuilder pis = new StringBuilder();
      pis.append(1 + RANDOM.nextInt(2));
      for (int i = 0; i < 10; i++) {
        pis.append(RANDOM.nextInt(10));
      }
      String cns = Cns.fromPis(pis.toString());
      if (Cns.isValid(cns)) {
        return cns;
      }
    }
  }

  public static String randomCpf() {
    while (true) {
      StringBuilder nine = new StringBuilder();
      for (int i = 0; i < 9; i++) {
        nine.append(RANDOM.nextInt(10));
      }
      String cpf = Cpf.withCheckDigits(nine.toString());
      if (Cpf.isValid(cpf)) {
        return cpf;
      }
    }
  }
}
