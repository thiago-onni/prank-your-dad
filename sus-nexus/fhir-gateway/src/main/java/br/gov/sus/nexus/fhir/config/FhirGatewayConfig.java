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
