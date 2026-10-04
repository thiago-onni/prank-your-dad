package br.gov.sus.nexus.core.identity.application;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.util.Map;

/** Configuração do MPI ({@code sus.mpi.*}): limiares, pesos m/u e blocking. */
@ConfigMapping(prefix = "sus.mpi")
public interface MpiConfig {

  @WithDefault("mpi-rules-1.0")
  String ruleVersion();

  Threshold threshold();

  JaroWinkler jaroWinkler();

  Blocking blocking();

  /**
   * Pesos por campo: {@code name}, {@code mother-name}, {@code birthdate}, {@code sex}, {@code
   * phone}.
   */
  Map<String, Weight> weights();

  interface Threshold {
    double high();

    double low();
  }

  interface JaroWinkler {
    @WithDefault("0.92")
    double agree();

    @WithDefault("0.85")
    double partial();
  }

  interface Blocking {
    @WithDefault("50")
    int limit();

    @WithDefault("0.4")
    double minSimilarity();
  }

  interface Weight {
    double m();

    double u();
  }
}
