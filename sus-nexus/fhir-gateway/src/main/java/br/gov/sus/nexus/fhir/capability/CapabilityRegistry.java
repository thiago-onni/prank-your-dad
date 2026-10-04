package br.gov.sus.nexus.fhir.capability;

import br.gov.sus.nexus.fhir.config.FhirGatewayConfig;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Registro único de capacidades do gateway. Tudo o que o servidor expõe (tipos, interações,
 * parâmetros de busca, operações) está registrado aqui em código; o {@code CapabilityStatement} é
 * gerado a partir deste registro e o roteador de interações o consulta antes de executar qualquer
 * requisição. Assim é impossível anunciar o que não existe ou executar o que não foi anunciado.
 *
 * <p>Para adicionar um recurso: registre-o aqui (interações + parâmetros com expressão FHIRPath),
 * crie o mapper canônico (se for projeção) e, se necessário, invariantes municipais em {@code
 * MunicipalInvariants}.
 */
@ApplicationScoped
public class CapabilityRegistry {

  /** Parâmetros comuns a todo recurso, computados sobre a tabela de recursos. */
  public static final SearchParamDef PARAM_ID =
      new SearchParamDef("_id", SearchParamType.TOKEN, null, "Id lógico do recurso", List.of());

  public static final SearchParamDef PARAM_LAST_UPDATED =
      new SearchParamDef(
          "_lastUpdated",
          SearchParamType.DATE,
          null,
          "Data da última atualização (meta.lastUpdated)",
          List.of());

  /** Operações de sistema implementadas (nome sem {@code $}). */
  public static final List<String> SYSTEM_OPERATIONS = List.of("validate");

  @Inject FhirGatewayConfig config;

  private final Map<String, ResourceCapability> resources = new LinkedHashMap<>();

  @PostConstruct
  void init() {
    FhirGatewayConfig.Profiles profiles = config.profiles();

    register(
        ResourceCapability.of("Patient")
            .readWrite()
            .profile(profiles.patient())
            .param(
                SearchParamDef.token(
                    "identifier", "Patient.identifier", "Identificador (CNS, CPF, id municipal)"))
            .param(SearchParamDef.string("name", "Patient.name", "Qualquer parte do nome"))
            .param(SearchParamDef.date("birthdate", "Patient.birthDate", "Data de nascimento"))
            .build());

    register(
        ResourceCapability.of("Organization")
            .readWrite()
            .profile(profiles.organization())
            .param(
                SearchParamDef.token(
                    "identifier", "Organization.identifier", "Identificador (CNES, CNPJ)"))
            .param(SearchParamDef.string("name", "Organization.name", "Nome da organização"))
            .build());

    register(
        ResourceCapability.of("Location")
            .readWrite()
            .profile(profiles.location())
            .param(SearchParamDef.token("identifier", "Location.identifier", "Identificador"))
            .param(SearchParamDef.string("name", "Location.name", "Nome do local"))
            .param(
                SearchParamDef.reference(
                    "organization",
                    "Location.managingOrganization",
                    "Organização responsável",
                    "Organization"))
            .build());

    register(
        ResourceCapability.of("Practitioner")
            .readWrite()
            .profile(profiles.practitioner())
            .param(
                SearchParamDef.token(
                    "identifier", "Practitioner.identifier", "Identificador (CNS, CPF, conselho)"))
            .param(SearchParamDef.string("name", "Practitioner.name", "Nome do profissional"))
            .build());

    register(
        ResourceCapability.of("PractitionerRole")
            .readWrite()
            .profile(profiles.practitionerRole())
            .param(
                SearchParamDef.reference(
                    "practitioner",
                    "PractitionerRole.practitioner",
                    "Profissional",
                    "Practitioner"))
            .param(
                SearchParamDef.reference(
                    "organization", "PractitionerRole.organization", "Organização", "Organization"))
            .param(SearchParamDef.token("role", "PractitionerRole.code", "Papel (CBO)"))
            .build());

    // Recursos de auditoria/proveniência: somente leitura (gerados pelo próprio gateway)
    register(
        ResourceCapability.of("AuditEvent")
            .interactions(Interaction.READ, Interaction.SEARCH_TYPE)
            .param(
                SearchParamDef.reference(
                    "entity", "AuditEvent.entity.what", "Recurso afetado", "Patient"))
            .param(SearchParamDef.date("date", "AuditEvent.recorded", "Data de registro"))
            .param(SearchParamDef.token("action", "AuditEvent.action", "Ação (C/R/U/D/E)"))
            .build());

    register(
        ResourceCapability.of("Provenance")
            .interactions(Interaction.READ, Interaction.SEARCH_TYPE)
            .param(
                SearchParamDef.reference(
                    "target", "Provenance.target", "Recurso derivado", "Patient", "Organization"))
            .build());
  }

  private void register(ResourceCapability capability) {
    if (resources.containsKey(capability.type())) {
      throw new IllegalStateException("Tipo registrado em duplicidade: " + capability.type());
    }
    resources.put(capability.type(), capability);
  }

  public Collection<ResourceCapability> all() {
    return Collections.unmodifiableCollection(resources.values());
  }

  public Optional<ResourceCapability> resource(String type) {
    return Optional.ofNullable(resources.get(type));
  }

  public boolean supports(String type, Interaction interaction) {
    return resource(type).map(r -> r.supports(interaction)).orElse(false);
  }

  /** Resolve um parâmetro de busca (comum ou específico do tipo). */
  public Optional<SearchParamDef> searchParam(String type, String name) {
    if (PARAM_ID.name().equals(name)) {
      return Optional.of(PARAM_ID);
    }
    if (PARAM_LAST_UPDATED.name().equals(name)) {
      return Optional.of(PARAM_LAST_UPDATED);
    }
    return resource(type).flatMap(r -> r.searchParam(name));
  }

  public List<SearchParamDef> commonParams() {
    return List.of(PARAM_ID, PARAM_LAST_UPDATED);
  }
}
