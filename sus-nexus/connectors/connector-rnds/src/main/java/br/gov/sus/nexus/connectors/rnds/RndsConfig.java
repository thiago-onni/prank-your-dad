package br.gov.sus.nexus.connectors.rnds;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Configuração do conector RNDS (prefixo {@code rnds.*}).
 *
 * <p><b>Todos os endereços, cabeçalhos e perfis são parametrizáveis</b>: dependem da documentação
 * oficial vigente da RNDS e da habilitação do município. Os valores em {@code
 * application.properties} são exemplos <i>a confirmar na homologação</i>.
 */
@ConfigMapping(prefix = "rnds")
public interface RndsConfig {

  /** Serviço de autenticação da RNDS (token obtido com o certificado ICP-Brasil via mTLS). */
  String authUrl();

  /** Base do serviço EHR da RNDS (o caminho do recurso vem de {@code models.<m>.ehr-path}). */
  String ehrUrl();

  /** CPF do profissional responsável (solicitante), enviado em {@link Headers#requester()}. */
  Optional<String> requesterCpf();

  /** CNES do estabelecimento solicitante (autor do documento / identificador do Bundle). */
  Optional<String> cnesSolicitante();

  /**
   * Identificador do solicitante usado no sistema do identificador do Bundle ({@code {solicitante}}
   * em {@code bundle.identifier_system}); padrão = {@link #cnesSolicitante()}.
   */
  Optional<String> solicitanteId();

  @WithDefault("RNDS (versão da documentação oficial a confirmar na homologação)")
  String sourceVersion();

  Auth auth();

  Headers headers();

  Certificate certificate();

  /** Timeout de conexão/leitura para autenticação e envio. */
  @WithDefault("PT30S")
  Duration timeout();

  /** Modelos de informação (chave = id do modelo, ex.: {@code resultado-exame}). */
  Map<String, Model> models();

  Fhir fhir();

  SubmissionStore submissionStore();

  Heartbeat heartbeat();

  Reconciliation reconciliation();

  /** Espelha o ledger e o heartbeat no core ({@code /api/v1/integration/*}). */
  @WithDefault("true")
  boolean mirrorToCore();

  interface Auth {
    /** Método HTTP do endpoint de token ({@code GET} ou {@code POST}). */
    @WithDefault("GET")
    String method();

    /** Campo JSON da resposta com o token. */
    @WithDefault("access_token")
    String tokenField();

    /** Campo JSON com a validade em segundos (quando ausente usa {@link #defaultTtl()}). */
    @WithDefault("expires_in")
    String expiresInField();

    /** Validade assumida quando a resposta não informa expiração. */
    @WithDefault("PT25M")
    Duration defaultTtl();

    /** Folga antes da expiração para renovar o token. */
    @WithDefault("PT60S")
    Duration refreshSkew();
  }

  interface Headers {
    /** Cabeçalho que leva o token da RNDS. */
    @WithDefault("X-Authorization-Server")
    String token();

    /** Esquema prefixado ao token (vazio = token puro). */
    @WithDefault("Bearer")
    String tokenScheme();

    /** Cabeçalho que leva o CPF do solicitante. */
    @WithDefault("Authorization")
    String requester();

    /** Content-Type do envio. */
    @WithDefault("application/fhir+json")
    String contentType();
  }

  interface Certificate {
    /** Keystore PKCS#12 do e-CNPJ (em produção montado a partir do OpenBao/External Secrets). */
    Optional<String> keystorePath();

    Optional<String> keystorePassword();

    /** Senha da chave privada (padrão = senha do keystore). */
    Optional<String> keyPassword();

    @WithDefault("PKCS12")
    String keystoreType();

    /** Truststore com a cadeia dos servidores da RNDS; ausente = truststore padrão da JVM. */
    Optional<String> truststorePath();

    Optional<String> truststorePassword();

    @WithDefault("PKCS12")
    String truststoreType();
  }

  interface Model {
    /** Habilitação do modelo (default false: só ligar após a habilitação formal do município). */
    @WithDefault("false")
    boolean enabled();

    /** Mapeamento declarativo versionado (classpath ou arquivo). */
    String mapping();

    /** Sobrescreve {@code bundle.type} do YAML ({@code document} ou {@code transaction}). */
    Optional<String> bundleType();

    /** Caminho relativo a {@link #ehrUrl()} para o POST do Bundle. */
    @WithDefault("/fhir/r4/Bundle")
    String ehrPath();
  }

  interface Fhir {
    /** Base FHIR R4 do fhir-gateway municipal. */
    @WithDefault("http://localhost:8081/fhir/r4")
    String baseUrl();

    /** {@code oauth2} (client credentials), {@code static} ou {@code none}. */
    @WithDefault("oauth2")
    String authMode();

    @WithDefault("http://localhost:8180/realms/sus-nexus/protocol/openid-connect/token")
    String tokenUrl();

    @WithDefault("connector-rnds")
    String clientId();

    Optional<String> clientSecret();

    @WithDefault("system/*.read")
    String scope();

    Optional<String> staticToken();

    /** Cabeçalhos extras (ex.: identidade de teste do gateway em dev). */
    Map<String, String> extraHeaders();

    @WithDefault("PT20S")
    Duration timeout();
  }

  interface SubmissionStore {
    /** {@code memory} ou {@code jdbc} (tabela {@code rnds_submission}, datasource default). */
    @WithDefault("memory")
    String type();

    @WithDefault("true")
    boolean createSchema();
  }

  interface Heartbeat {
    @WithDefault("true")
    boolean enabled();

    @WithDefault("60000")
    long periodMs();
  }

  interface Reconciliation {
    @WithDefault("true")
    boolean enabled();

    /** Intervalo do job (ms). */
    @WithDefault("3600000")
    long periodMs();

    /** Janela reconciliada a cada execução. */
    @WithDefault("P1D")
    Duration window();
  }
}
