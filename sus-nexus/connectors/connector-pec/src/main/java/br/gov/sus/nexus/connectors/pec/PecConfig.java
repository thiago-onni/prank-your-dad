package br.gov.sus.nexus.connectors.pec;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/** Configuração do conector e-SUS APS/PEC (prefixo {@code pec.*}). */
@ConfigMapping(prefix = "pec")
public interface PecConfig {

  /** {@code file} (CSV exportado) ou {@code jdbc} (réplica PostgreSQL somente leitura). */
  @WithDefault("file")
  String mode();

  /** Versão do PEC da instalação (informativo; usada no descriptor e no README). */
  @WithDefault("5.x")
  String sourceVersion();

  File file();

  Jdbc jdbc();

  Mapping mapping();

  interface File {
    @WithDefault("data/pec/in")
    String inputDir();

    @WithDefault("UTF-8")
    String charset();

    @WithDefault(";")
    String delimiter();

    @WithDefault("5000")
    long pollDelayMs();
  }

  interface Jdbc {
    /**
     * Arquivo SQL ({@code classpath:} ou caminho) da consulta de cidadãos; parâmetro {@code
     * :#watermark}.
     */
    @WithDefault("classpath:sql/pec-citizens.sql")
    String citizensQuery();

    @WithDefault("classpath:sql/pec-appointments.sql")
    String appointmentsQuery();

    /** Intervalo entre varreduras incrementais (ms). */
    @WithDefault("300000")
    long pollIntervalMs();

    /** Arquivo que guarda a marca d'água (último {@code dt_atualizado} sincronizado). */
    @WithDefault("data/pec/watermark.json")
    String watermarkFile();

    /** Valor inicial da marca d'água quando o arquivo não existe. */
    @WithDefault("1970-01-01 00:00:00")
    String initialWatermark();
  }

  interface Mapping {
    @WithDefault("mappings/pec-citizen-1.0.0.yaml")
    String citizen();

    @WithDefault("mappings/pec-appointment-1.0.0.yaml")
    String appointment();
  }
}
