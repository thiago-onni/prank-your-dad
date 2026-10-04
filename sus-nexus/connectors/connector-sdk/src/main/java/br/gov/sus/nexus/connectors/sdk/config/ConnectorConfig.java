package br.gov.sus.nexus.connectors.sdk.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.time.Duration;
import java.util.Optional;

/** Configuração comum a todo conector (prefixo {@code connector.*}). */
@ConfigMapping(prefix = "connector")
public interface ConnectorConfig {

  /** Tenant alvo: {@code ibge_<7 dígitos>}; vai no header {@code X-Tenant-Id}. */
  String tenantId();

  RawStore rawStore();

  Ledger ledger();

  Core core();

  Retry retry();

  Dlq dlq();

  interface RawStore {
    /** {@code file} (padrão) ou {@code s3}. */
    @WithDefault("file")
    String type();

    @WithDefault("target/raw-zone")
    String dir();

    Optional<String> bucket();

    @WithDefault("raw")
    String prefix();

    Optional<String> endpoint();

    @WithDefault("us-east-1")
    String region();
  }

  interface Ledger {
    /** {@code memory} (padrão) ou {@code jdbc} (requer datasource default do Agroal). */
    @WithDefault("memory")
    String type();

    @WithDefault("true")
    boolean createSchema();
  }

  interface Core {
    Auth auth();

    /** Tamanho máximo de lote em upserts de referência/terminologia. */
    @WithDefault("500")
    int batchSize();

    interface Auth {
      /** {@code static} (dev/test: token fixo) ou {@code oidc} (client credentials). */
      @WithDefault("static")
      String mode();

      @WithDefault("test-token")
      String staticToken();
    }
  }

  interface Retry {
    @WithDefault("5")
    int maxAttempts();

    @WithDefault("PT1S")
    Duration initialBackoff();

    @WithDefault("2.0")
    double multiplier();

    @WithDefault("PT5M")
    Duration maxBackoff();
  }

  interface Dlq {
    /** {@code file} (padrão) ou {@code log}. */
    @WithDefault("file")
    String type();

    @WithDefault("target/dlq")
    String dir();
  }
}
