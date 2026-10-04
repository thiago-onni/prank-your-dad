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

    String patient();

    String organization();

    String location();

    String practitioner();

    @WithName("practitioner-role")
    String practitionerRole();

    @WithName("require-profile")
    @WithDefault("true")
    boolean requireProfile();
  }
}
