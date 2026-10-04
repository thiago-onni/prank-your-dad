package br.gov.sus.nexus.connectors.rnds;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Configuração do conector RNDS (prefixo {@code rnds.*}).
 *
 * <p>Endereços, cabeçalhos e formato do token seguem o Guia de Integração da RNDS
 * (rnds-guia.saude.gov.br) e o Manual de Integração do DATASUS v1.2 — fontes e o que ainda depende
 * de homologação estão no README e em {@code application.properties}.
 */
@ConfigMapping(prefix = "rnds")
public interface RndsConfig {

  /**
   * Ambiente da RNDS: {@code homologacao} (único para o Brasil) ou {@code producao} (EHR por UF).
   * Seleciona {@code endpoints.<ambiente>}.
   */
  @WithDefault("homologacao")
  String environment();

  /**
   * UF (sigla minúscula, ex.: {@code mg}) dos estabelecimentos credenciados: em produção o EHR é
   * {@code https://<uf>-ehr-services.saude.gov.br/api} e a credencial só vale para essa UF.
   */
  Optional<String> uf();

  /** Endereços oficiais por ambiente (chave = {@link #environment()}). */
  Map<String, Endpoint> endpoints();

  /** Sobrescreve o endereço do token do ambiente (testes/proxy). */
  Optional<String> authUrl();

  /** Sobrescreve a base do EHR do ambiente (testes/proxy). */
  Optional<String> ehrUrl();

  /**
   * CNS do profissional de saúde, lotado no estabelecimento credenciado, em nome do qual as
   * requisições são feitas — enviado em {@link Headers#requester()} ({@code Authorization}).
   */
  Optional<String> requesterCns();

  /** CNES do estabelecimento (autor da Composition). */
  Optional<String> cnesSolicitante();

  /**
   * Identificador do solicitante atribuído pela RNDS na aprovação da solicitação de acesso (Portal
   * de Serviços do DATASUS) — {@code {solicitante}} em {@code BRRNDS-{solicitante}}. Não é o CNES.
   */
  Optional<String> solicitanteId();

  @WithDefault(
      "RNDS: Guia de Integração (rnds-guia.saude.gov.br) + Manual de Integração DATASUS v1.2;"
          + " REL BRResultadoExameLaboratorial-1.1")
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

  /** Endereços de um ambiente; {@code {uf}} em {@code ehr-url} é trocado por {@link #uf()}. */
  interface Endpoint {
    String authUrl();

    String ehrUrl();
  }

  interface Auth {
    /**
     * Método HTTP do endpoint de token: {@code GET /api/token} (guia e coleção Postman oficial; o
     * Manual v1.2 cita POST, que responde 405).
     */
    @WithDefault("GET")
    String method();

    /** Campo JSON da resposta com o token. */
    @WithDefault("access_token")
    String tokenField();

    /** Campo JSON com a validade (quando ausente usa {@link #defaultTtl()}). */
    @WithDefault("expires_in")
    String expiresInField();

    /**
     * Unidade de {@link #expiresInField()} ({@link java.time.temporal.ChronoUnit}): o Manual v1.2
     * documenta {@code "expires_in": 1800000} para um token de 30 minutos → {@code MILLIS}.
     */
    @WithDefault("MILLIS")
    String expiresInUnit();

    /** Validade assumida quando a resposta não informa expiração. */
    @WithDefault("PT30M")
    Duration defaultTtl();

    /** Teto da validade (o token da RNDS vale 30 minutos), contra unidade/valor inesperado. */
    @WithDefault("PT30M")
    Duration maxTtl();

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

    /** Cabeçalho que leva o CNS do profissional requisitante. */
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
