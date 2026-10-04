package br.gov.sus.nexus.connectors.terminology;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/** Configuração do conector de terminologia (prefixo {@code terminology.*}). */
@ConfigMapping(prefix = "terminology")
public interface TerminologyConfig {

  /**
   * Diretório observado; arquivos processados vão para {@code .done}, com erro para {@code .error}.
   */
  @WithDefault("data/terminology/in")
  String inputDir();

  /** Localização do YAML de layouts ({@code classpath:} ou caminho). */
  @WithDefault("classpath:layouts/terminology-layouts.yaml")
  String layouts();

  /** Intervalo de varredura do diretório (ms). */
  @WithDefault("5000")
  long pollDelayMs();
}
