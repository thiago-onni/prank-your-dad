package br.gov.sus.nexus.fhir.mapping;

import br.gov.sus.nexus.fhir.config.FhirGatewayConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

/**
 * Parâmetros dos mapeadores (perfis e sistemas de terminologia), extraídos da configuração para
 * permitir testes unitários sem CDI.
 */
public record MapperSettings(
    String encounterProfile,
    String appointmentProfile,
    String serviceRequestProfile,
    String taskProfile,
    String conditionProfile,
    String carePlanProfile,
    String sigtapSystem,
    String loincSystem,
    String localSystem) {

  /** Valores padrão (mesmos de {@code application.properties}) para testes unitários. */
  public static MapperSettings defaults() {
    String base = "https://br-core.saude.gov.br/fhir/StructureDefinition";
    String municipal = "http://sus-nexus.gov.br/fhir/StructureDefinition";
    return new MapperSettings(
        base + "/BRCoreEncounter",
        municipal + "/SUSNexusAppointment",
        municipal + "/SUSNexusServiceRequest",
        municipal + "/SUSNexusTask",
        base + "/BRCoreCondition",
        municipal + "/SUSNexusCarePlan",
        "http://www.saude.gov.br/fhir/r4/CodeSystem/BRTabelaSUS",
        "http://loinc.org",
        "http://sus-nexus.gov.br/fhir/CodeSystem/local-procedure");
  }

  /** Produtor CDI a partir de {@link FhirGatewayConfig}. */
  @ApplicationScoped
  public static class Producer {
    @Inject FhirGatewayConfig config;

    /** Escopo dependente: um record é final e não admite proxy de escopo normal. */
    @Produces
    MapperSettings settings() {
      FhirGatewayConfig.Profiles p = config.profiles();
      FhirGatewayConfig.Terminology t = config.terminology();
      return new MapperSettings(
          p.encounter(),
          p.appointment(),
          p.serviceRequest(),
          p.task(),
          p.condition(),
          p.carePlan(),
          t.sigtapSystem(),
          t.loincSystem(),
          t.localSystem());
    }
  }
}
