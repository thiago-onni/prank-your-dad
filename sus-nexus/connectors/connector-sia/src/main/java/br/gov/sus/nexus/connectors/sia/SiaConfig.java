package br.gov.sus.nexus.connectors.sia;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.time.Duration;

/**
 * Configuração do conector SIA/SIH (prefixo {@code sia.*}). Modo arquivo: pastas monitoradas
 * (normalmente um SFTP montado) com exportações de produção do sistema de origem e com os retornos
 * de processamento SIA/SIH.
 */
@ConfigMapping(prefix = "sia")
public interface SiaConfig {

  /** Descrição das fontes suportadas (descriptor e README). */
  @WithDefault(
      "exportações de produção do sistema de origem (CSV) + retornos SIA/SIH (CSV/TXT, layout A"
          + " CONFIRMAR)")
  String sourceVersion();

  File file();

  /** Layout dos arquivos ({@code classpath:} ou caminho) — versionado em YAML. */
  @WithDefault("classpath:layouts/sia-layouts.yaml")
  String layout();

  Mapping mapping();

  Publish publish();

  Schedule heartbeat();

  Reconciliation reconciliation();

  interface File {
    /** Pasta das exportações de produção (BPA-C/BPA-I/APAC/AIH) — kinds {@code group: producao}. */
    @WithDefault("data/sia/producao")
    String productionDir();

    /** Pasta dos retornos de processamento SIA/SIH — kinds {@code group: retorno}. */
    @WithDefault("data/sia/retornos")
    String returnsDir();

    /** Charset padrão (cada kind do layout pode sobrescrever). */
    @WithDefault("UTF-8")
    String charset();

    @WithDefault("5000")
    long pollDelayMs();

    /** Registro (JSON) dos arquivos já processados por SHA-256 (marca d'água por arquivo). */
    @WithDefault("data/sia/processed-files.json")
    String processedRegistry();
  }

  interface Mapping {
    @WithDefault("mappings/sia-production-record-1.0.0.yaml")
    String record();

    @WithDefault("mappings/sia-production-outcome-1.1.0.yaml")
    String outcome();
  }

  interface Publish {
    /** Tópico de ingestão (catálogo {@code contracts/events/topics.yaml}). */
    @WithDefault("sus.ingest.production.v1")
    String topic();

    /** Tempo máximo de espera do ack do broker por registro (excedido → erro transitório). */
    @WithDefault("PT30S")
    Duration ackTimeout();
  }

  interface Schedule {
    @WithDefault("true")
    boolean enabled();

    @WithDefault("60000")
    long periodMs();
  }

  interface Reconciliation {
    @WithDefault("true")
    boolean enabled();

    @WithDefault("3600000")
    long periodMs();

    /** Janela avaliada a cada execução. */
    @WithDefault("P1D")
    Duration window();
  }
}
