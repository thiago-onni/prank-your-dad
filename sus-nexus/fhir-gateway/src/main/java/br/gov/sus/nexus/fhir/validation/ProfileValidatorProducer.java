package br.gov.sus.nexus.fhir.validation;

import br.gov.sus.nexus.fhir.config.FhirGatewayConfig;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import java.util.Arrays;
import java.util.List;
import org.jboss.logging.Logger;

/**
 * Seleciona a implementação de {@link ProfileValidator} conforme {@code sus.fhir.validation.mode} e
 * a inicializa no start da aplicação (o carregamento das definições leva alguns segundos).
 */
@ApplicationScoped
public class ProfileValidatorProducer {

  private static final Logger LOG = Logger.getLogger(ProfileValidatorProducer.class);

  @Inject FhirGatewayConfig config;

  @Produces
  @ApplicationScoped
  @Startup
  public ProfileValidator profileValidator() {
    String mode = config.validation().mode();
    if ("official".equalsIgnoreCase(mode)) {
      List<String> packages =
          config
              .validation()
              .igPackages()
              .map(
                  s ->
                      Arrays.stream(s.split(","))
                          .map(String::trim)
                          .filter(p -> !p.isEmpty())
                          .toList())
              .orElse(List.of());
      try {
        return new OfficialProfileValidator(packages);
      } catch (RuntimeException e) {
        LOG.errorf(e, "Falha ao iniciar validador oficial; usando modo structural");
        return new StructuralProfileValidator();
      }
    }
    LOG.infof("Validação de perfil em modo structural");
    return new StructuralProfileValidator();
  }
}
