package br.gov.sus.nexus.connectors.sisreg;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/** Configuração do conector SISREG (prefixo {@code sisreg.*}). Somente modo arquivo (plano B). */
@ConfigMapping(prefix = "sisreg")
public interface SisregConfig {

  /** Versão/variante do SISREG de origem (informativo; descriptor e README). */
  @WithDefault("SISREG III (exportações do gestor municipal)")
  String sourceVersion();

  File file();

  /** Layout dos arquivos exportados ({@code classpath:} ou caminho). */
  @WithDefault("classpath:layouts/sisreg-layouts.yaml")
  String layout();

  Mapping mapping();

  interface File {
    @WithDefault("data/sisreg/in")
    String inputDir();

    /** Charset dos CSV (exportações do SISREG costumam vir em ISO-8859-1). */
    @WithDefault("UTF-8")
    String charset();

    @WithDefault(";")
    String delimiter();

    /** Índice da planilha lida em arquivos XLSX. */
    @WithDefault("0")
    int sheetIndex();

    @WithDefault("5000")
    long pollDelayMs();

    /** Registro (JSON) dos arquivos já processados por SHA-256 (marca d'água por arquivo). */
    @WithDefault("data/sisreg/processed-files.json")
    String processedRegistry();
  }

  interface Mapping {
    @WithDefault("mappings/sisreg-regulation-request-1.0.0.yaml")
    String request();

    @WithDefault("mappings/sisreg-regulation-status-1.0.0.yaml")
    String status();

    @WithDefault("mappings/sisreg-provider-capacity-1.0.0.yaml")
    String capacity();
  }
}
