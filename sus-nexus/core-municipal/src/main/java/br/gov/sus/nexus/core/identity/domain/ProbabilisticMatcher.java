package br.gov.sus.nexus.core.identity.domain;

import br.gov.sus.nexus.core.identity.domain.MatchEvidence.Agreement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.commons.text.similarity.JaroWinklerSimilarity;

/**
 * Matching probabilístico Fellegi-Sunter simplificado. Para cada campo soma-se {@code log2(m/u)} em
 * concordância, {@code log2((1-m)/(1-u))} em discordância, metade do peso de concordância em
 * concordância parcial e 0 quando ausente. Comparadores: Jaro-Winkler (nome, nome da mãe), data de
 * nascimento (exata/parcial), sexo e telefone.
 */
public final class ProbabilisticMatcher {

  public static final String F_NAME = "name";
  public static final String F_MOTHER = "mother_name";
  public static final String F_BIRTHDATE = "birthdate";
  public static final String F_SEX = "sex";
  public static final String F_PHONE = "phone";

  private static final JaroWinklerSimilarity JW = new JaroWinklerSimilarity();

  private final MatcherParameters params;

  public ProbabilisticMatcher(MatcherParameters params) {
    this.params = params;
  }

  public MatcherParameters parameters() {
    return params;
  }

  public MatchScore compare(PersonFeatures incoming, PersonFeatures candidate) {
    List<MatchEvidence> evidences = new ArrayList<>();
    double score = 0;
    score += jaroWinkler(F_NAME, incoming.normalizedName(), candidate.normalizedName(), evidences);
    score +=
        jaroWinkler(
            F_MOTHER, incoming.normalizedMotherName(), candidate.normalizedMotherName(), evidences);
    score += birthdate(incoming.birthdate(), candidate.birthdate(), evidences);
    score += sex(incoming.sex(), candidate.sex(), evidences);
    score += phone(incoming.phones(), candidate.phones(), evidences);
    return new MatchScore(candidate, round(score), List.copyOf(evidences));
  }

  /** Classifica o score pelos limiares configurados. */
  public Classification classify(double score) {
    if (score >= params.thresholdHigh()) {
      return Classification.PROBABLE;
    }
    if (score >= params.thresholdLow()) {
      return Classification.PENDING;
    }
    return Classification.NEW;
  }

  /** Resultado da classificação por limiar (Fase 1: provável NÃO vincula automaticamente). */
  public enum Classification {
    PROBABLE,
    PENDING,
    NEW
  }

  private double jaroWinkler(String field, String a, String b, List<MatchEvidence> out) {
    MatcherParameters.FieldWeight w = params.weight(field);
    if (isBlank(a) || isBlank(b)) {
      out.add(new MatchEvidence(field, "jaro_winkler", Agreement.MISSING, 0));
      return 0;
    }
    double sim = JW.apply(a, b);
    if (sim >= params.jaroWinklerAgree()) {
      return add(out, field, "jaro_winkler=" + fmt(sim), Agreement.AGREE, w.agreeWeight());
    }
    if (sim >= params.jaroWinklerPartial()) {
      return add(out, field, "jaro_winkler=" + fmt(sim), Agreement.PARTIAL, w.agreeWeight() / 2);
    }
    return add(out, field, "jaro_winkler=" + fmt(sim), Agreement.DISAGREE, w.disagreeWeight());
  }

  private double birthdate(LocalDate a, LocalDate b, List<MatchEvidence> out) {
    MatcherParameters.FieldWeight w = params.weight(F_BIRTHDATE);
    if (a == null || b == null) {
      out.add(new MatchEvidence(F_BIRTHDATE, "exact", Agreement.MISSING, 0));
      return 0;
    }
    if (a.equals(b)) {
      return add(out, F_BIRTHDATE, "exact", Agreement.AGREE, w.agreeWeight());
    }
    int equalParts = 0;
    if (a.getYear() == b.getYear()) {
      equalParts++;
    }
    if (a.getMonthValue() == b.getMonthValue()) {
      equalParts++;
    }
    if (a.getDayOfMonth() == b.getDayOfMonth()) {
      equalParts++;
    }
    boolean transposed =
        a.getYear() == b.getYear()
            && a.getMonthValue() == b.getDayOfMonth()
            && a.getDayOfMonth() == b.getMonthValue();
    if (equalParts == 2 || transposed) {
      return add(out, F_BIRTHDATE, "partial", Agreement.PARTIAL, w.agreeWeight() / 2);
    }
    return add(out, F_BIRTHDATE, "exact", Agreement.DISAGREE, w.disagreeWeight());
  }

  private double sex(String a, String b, List<MatchEvidence> out) {
    MatcherParameters.FieldWeight w = params.weight(F_SEX);
    if (isBlank(a) || isBlank(b) || "unknown".equals(a) || "unknown".equals(b)) {
      out.add(new MatchEvidence(F_SEX, "exact", Agreement.MISSING, 0));
      return 0;
    }
    if (a.equalsIgnoreCase(b)) {
      return add(out, F_SEX, "exact", Agreement.AGREE, w.agreeWeight());
    }
    return add(out, F_SEX, "exact", Agreement.DISAGREE, w.disagreeWeight());
  }

  private double phone(Set<String> a, Set<String> b, List<MatchEvidence> out) {
    MatcherParameters.FieldWeight w = params.weight(F_PHONE);
    if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
      out.add(new MatchEvidence(F_PHONE, "any_equal", Agreement.MISSING, 0));
      return 0;
    }
    Set<String> inter = new HashSet<>(a);
    inter.retainAll(b);
    if (!inter.isEmpty()) {
      return add(out, F_PHONE, "any_equal", Agreement.AGREE, w.agreeWeight());
    }
    return add(out, F_PHONE, "any_equal", Agreement.DISAGREE, w.disagreeWeight());
  }

  private static double add(
      List<MatchEvidence> out,
      String field,
      String comparison,
      Agreement agreement,
      double weight) {
    double rounded = round(weight);
    out.add(new MatchEvidence(field, comparison, agreement, rounded));
    return rounded;
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }

  private static String fmt(double d) {
    return String.format(java.util.Locale.ROOT, "%.3f", d);
  }

  static double round(double d) {
    return Math.round(d * 10000.0) / 10000.0;
  }
}
