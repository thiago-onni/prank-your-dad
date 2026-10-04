package br.gov.sus.nexus.core.identity.application;

import br.gov.sus.nexus.core.identity.domain.MatcherParameters;
import br.gov.sus.nexus.core.identity.domain.ProbabilisticMatcher;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import java.util.HashMap;
import java.util.Map;

/** Constrói o {@link ProbabilisticMatcher} a partir de {@link MpiConfig}. */
@ApplicationScoped
public class MatcherProducer {

  @Produces
  @ApplicationScoped
  public ProbabilisticMatcher matcher(MpiConfig config) {
    Map<String, MatcherParameters.FieldWeight> weights = new HashMap<>();
    config
        .weights()
        .forEach(
            (field, w) ->
                weights.put(
                    field.replace('-', '_'), new MatcherParameters.FieldWeight(w.m(), w.u())));
    return new ProbabilisticMatcher(
        new MatcherParameters(
            config.ruleVersion(),
            config.threshold().high(),
            config.threshold().low(),
            config.jaroWinkler().agree(),
            config.jaroWinkler().partial(),
            Map.copyOf(weights)));
  }
}
