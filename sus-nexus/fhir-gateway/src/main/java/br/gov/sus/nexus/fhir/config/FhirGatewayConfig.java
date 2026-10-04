package br.gov.sus.nexus.fhir.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;
import java.util.Optional;

/** Configuração do gateway (prefixo {@code sus.fhir}). */
@ConfigMapping(prefix = "sus.fhir")
public interface FhirGatewayConfig {

  Security security();

  Search search();

  Validation validation();

  Profiles profiles();

  Terminology terminology();

  Core core();

  Binary binary();

  Everything everything();

  /** URL base pública usada em AuditEvent.source e em referências absolutas. */
  @WithName("base-url")
  String baseUrl();

  interface Security {
    /** {@code oidc} ou {@code test-headers}. */
    @WithDefault("oidc")
    String mode();

    @WithName("tenant-claim")
    @WithDefault("municipality_id")
    String tenantClaim();

    @WithName("scope-claim")
    @WithDefault("scope")
    String scopeClaim();
  }

  interface Search {
    @WithName("cursor-secret")
    String cursorSecret();

    @WithName("default-count")
    @WithDefault("20")
    int defaultCount();

    @WithName("max-count")
    @WithDefault("200")
    int maxCount();
  }

  interface Validation {
    /** {@code official} (InstanceValidator HL7) ou {@code structural}. */
    @WithDefault("official")
    String mode();

    /** Lista separada por vírgula de caminhos de classpath para pacotes NPM (.tgz) de IGs. */
    @WithName("ig-packages")
    Optional<String> igPackages();
  }

  interface Profiles {
    @WithName("base-url")
    String baseUrl();

    /** Base dos perfis municipais (tipos sem perfil br-core). */
    @WithName("municipal-base-url")
    @WithDefault("http://sus-nexus.gov.br/fhir/StructureDefinition")
    String municipalBaseUrl();

    String patient();

    String organization();

    String location();

    String practitioner();

    @WithName("practitioner-role")
    String practitionerRole();

    String encounter();

    String appointment();

    @WithName("service-request")
    String serviceRequest();

    String task();

    String condition();

    @WithName("care-plan")
    String carePlan();

    /** FHIR-3: perfis municipais de resultados/documentos. */
    String observation();

    @WithName("diagnostic-report")
    String diagnosticReport();

    @WithName("document-reference")
    String documentReference();

    @WithName("require-profile")
    @WithDefault("true")
    boolean requireProfile();
  }

  /** Sistemas de terminologia usados pelos mapeadores canônico → FHIR (FHIR-2). */
  interface Terminology {
    /** CodeSystem SIGTAP (Tabela SUS). */
    @WithName("sigtap-system")
    @WithDefault("http://www.saude.gov.br/fhir/r4/CodeSystem/BRTabelaSUS")
    String sigtapSystem();

    @WithName("loinc-system")
    @WithDefault("http://loinc.org")
    String loincSystem();

    /** CodeSystem para códigos locais (code_system = LOCAL). */
    @WithName("local-system")
    @WithDefault("http://sus-nexus.gov.br/fhir/CodeSystem/local-procedure")
    String localSystem();

    /** CodeSystem de unidades de medida dos resultados (UCUM). */
    @WithName("ucum-system")
    @WithDefault("http://unitsofmeasure.org")
    String ucumSystem();
  }

  /** Armazenamento de conteúdo de {@code Binary} (object storage; nunca inline no banco). */
  interface Binary {
    /** {@code file} (desenvolvimento/teste) ou {@code s3}. */
    @WithDefault("file")
    String storage();

    /** Diretório base do armazenamento em arquivo. */
    @WithName("file-dir")
    @WithDefault("target/fhir-binary")
    String fileDir();

    /** Tamanho máximo aceito em {@code POST Binary} (bytes decodificados). */
    @WithName("max-bytes")
    @WithDefault("20971520")
    long maxBytes();

    S3 s3();

    /** Parâmetros do S3 (AWS SDK v2). */
    interface S3 {
      @WithDefault("sus-nexus-fhir-binary")
      String bucket();

      @WithDefault("sa-east-1")
      String region();

      /** Endpoint alternativo (MinIO etc.); vazio = AWS. */
      Optional<String> endpoint();

      @WithName("path-style")
      @WithDefault("true")
      boolean pathStyle();

      /** Credenciais estáticas opcionais; ausentes = cadeia padrão do SDK. */
      @WithName("access-key")
      Optional<String> accessKey();

      @WithName("secret-key")
      Optional<String> secretKey();
    }
  }

  /** Limites de {@code Patient/$everything}. */
  interface Everything {
    @WithName("default-count")
    @WithDefault("50")
    int defaultCount();

    @WithName("max-count")
    @WithDefault("200")
    int maxCount();

    /** Número máximo de páginas que um cursor pode percorrer (0 = sem limite). */
    @WithName("max-pages")
    @WithDefault("100")
    int maxPages();
  }

  /** Acesso ao core municipal pelo consumidor de projeção (client-credentials). */
  interface Core {
    @WithName("token-url")
    Optional<String> tokenUrl();

    @WithName("client-id")
    @WithDefault("fhir-gateway")
    String clientId();

    @WithName("client-secret")
    Optional<String> clientSecret();

    /** Escopo pedido ao servidor de autorização (opcional). */
    Optional<String> scope();
  }
}
