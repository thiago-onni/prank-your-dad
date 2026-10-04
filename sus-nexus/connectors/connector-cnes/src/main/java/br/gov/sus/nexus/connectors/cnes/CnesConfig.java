package br.gov.sus.nexus.connectors.cnes;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.util.Optional;

/** Configuração do conector CNES (prefixo {@code cnes.*}). */
@ConfigMapping(prefix = "cnes")
public interface CnesConfig {

  /** Diretório observado para arquivos tbEstabelecimento (CSV ou DBF) por competência. */
  @WithDefault("data/cnes/in")
  String inputDir();

  /** Charset dos arquivos oficiais (BASE_DE_DADOS_CNES usa ISO-8859-1). */
  @WithDefault("ISO-8859-1")
  String charset();

  @WithDefault(";")
  String csvDelimiter();

  /** Filtra apenas unidades do município gestor (código IBGE de 6 dígitos). Vazio = todas. */
  Optional<String> municipioGestor();

  @WithDefault("5000")
  long pollDelayMs();

  @WithDefault("mappings/cnes-health-unit-1.0.0.yaml")
  String mapping();
}
