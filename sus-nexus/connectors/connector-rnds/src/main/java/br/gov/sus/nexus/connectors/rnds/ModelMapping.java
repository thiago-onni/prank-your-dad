package br.gov.sus.nexus.connectors.rnds;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Mapeamento declarativo de um modelo de informação da RNDS (YAML versionado em {@code
 * mappings/rnds-<modelo>-<versão>.yaml}). Define perfis {@code meta.profile}, sistemas de
 * identificador (CNS/CPF/CNES), forma do Bundle e as regras de pré-validação.
 */
public record ModelMapping(
    String model,
    String version,
    String status,
    String description,
    BundleSpec bundle,
    CompositionSpec composition,
    IdentifierSystems identifierSystems,
    TargetSystems targetSystems,
    Map<String, String> codeSystems,
    ObservationSpec observation,
    ReplacementSpec replacement,
    Map<String, ResourceSpec> resources,
    StripSpec strip,
    List<RequiredRule> required) {

  public static final Set<String> BUNDLE_TYPES = Set.of("document", "transaction");
  public static final Set<String> CHECKS =
      Set.of("present", "cnes", "cns", "cpf", "cns_or_cpf", "datetime", "one_of", "min_count");
  public static final Set<String> IDENTIFIER_VALUES = Set.of("source_id", "source_id_version");
  public static final Set<String> MODES = Set.of("entry", "logical", "omit");

  public ModelMapping {
    resources = resources == null ? Map.of() : Map.copyOf(resources);
    codeSystems = codeSystems == null ? Map.of() : Map.copyOf(codeSystems);
    required = required == null ? List.of() : List.copyOf(required);
    strip = strip == null ? new StripSpec(List.of(), List.of(), List.of()) : strip;
  }

  /**
   * Forma do Bundle. {@code identifierSystem} aceita o marcador {@code {solicitante}}; {@code
   * identifierValue}: {@code source_id} (id canônico do registro — o MESMO em envio e substituição,
   * como exige o guia da RNDS) ou {@code source_id_version} ({@code <id>-v<versionId>}).
   */
  public record BundleSpec(
      String type, String profile, String identifierSystem, String identifierValue) {
    public String identifierValueOrDefault() {
      return identifierValue == null ? "source_id" : identifierValue;
    }
  }

  /** Composition (apenas Bundle {@code document}). */
  public record CompositionSpec(
      String profile, CodingSpec type, CodingSpec category, String title, String sectionTitle) {}

  public record CodingSpec(String system, String code, String display) {}

  /** NamingSystems dos identificadores nos recursos LIDOS do fhir-gateway (origem). */
  public record IdentifierSystems(String cns, String cpf, String cnes) {}

  /**
   * Sistemas de identificador usados nas referências lógicas DENTRO do Bundle enviado (destino).
   * Ausente = mesmos de {@link IdentifierSystems}; campo nulo = identificador não permitido pelo
   * perfil (ex.: CPF do paciente no REL).
   */
  public record TargetSystems(String patientCns, String patientCpf, String cnes) {}

  /**
   * Observation do REL ({@code BRDiagnosticoLaboratorioClinico}): construída só com os elementos
   * permitidos pelo perfil.
   *
   * @param status status enviado (o EHR exige {@code final}: EHR-ERR924)
   * @param categorySystem CodeSystem de {@code category} (BRSubgrupoTabelaSUS)
   * @param categoryFromSystem sistema do código SIGTAP de onde se deriva o subgrupo (4 dígitos)
   * @param defaultCategory subgrupo quando não há código SIGTAP (nulo = pré-validação reprova)
   * @param codeSystemMap sistema de origem → sistema do código do exame no Bundle
   * @param qualitativeSystem CodeSystem de resultado qualitativo/interpretação
   * @param specimenTypeSystems sistemas aceitos em {@code Specimen.type}
   */
  public record ObservationSpec(
      String status,
      String categorySystem,
      String categoryFromSystem,
      String defaultCategory,
      Map<String, String> codeSystemMap,
      String qualitativeSystem,
      List<String> specimenTypeSystems) {
    public ObservationSpec {
      codeSystemMap = codeSystemMap == null ? Map.of() : Map.copyOf(codeSystemMap);
      specimenTypeSystems =
          specimenTypeSystems == null ? List.of() : List.copyOf(specimenTypeSystems);
    }
  }

  /**
   * Substituição de documento já aceito: {@code Composition.relatesTo.code} e a referência ao
   * documento substituído ({@code {protocolo}} = id atribuído pela RNDS, header Location).
   */
  public record ReplacementSpec(String code, String targetReference) {}

  /**
   * Tratamento de cada tipo de recurso: {@code entry} (incluído no Bundle com {@code profile}),
   * {@code logical} (referência por identificador, sem o recurso) ou {@code omit}.
   */
  public record ResourceSpec(String mode, String profile) {
    public String modeOrDefault() {
      return mode == null ? "entry" : mode;
    }
  }

  /** Remoções antes do envio: extensões/identificadores locais e elementos internos. */
  public record StripSpec(
      List<String> extensionPrefixes, List<String> identifierPrefixes, List<String> elements) {
    public StripSpec {
      extensionPrefixes = extensionPrefixes == null ? List.of() : List.copyOf(extensionPrefixes);
      identifierPrefixes = identifierPrefixes == null ? List.of() : List.copyOf(identifierPrefixes);
      elements = elements == null ? List.of() : List.copyOf(elements);
    }
  }

  /**
   * Regra de pré-validação: {@code check} ∈ {@link #CHECKS} aplicado aos {@code facts} extraídos
   * dos recursos (ex.: {@code patient.cns}); {@code values} para {@code one_of}/{@code min_count}.
   */
  public record RequiredRule(String name, String check, List<String> facts, List<String> values) {
    public RequiredRule {
      facts = facts == null ? List.of() : List.copyOf(facts);
      values = values == null ? List.of() : List.copyOf(values);
    }
  }

  /** Sistema do CNS do paciente no Bundle (destino). */
  public String targetPatientCns() {
    return targetSystems == null ? identifierSystems.cns() : targetSystems.patientCns();
  }

  /** Sistema do CPF do paciente no Bundle; nulo = CPF não permitido pelo perfil. */
  public String targetPatientCpf() {
    return targetSystems == null ? identifierSystems.cpf() : targetSystems.patientCpf();
  }

  /** Sistema do CNES (estabelecimento) no Bundle. */
  public String targetCnes() {
    return targetSystems == null || targetSystems.cnes() == null
        ? identifierSystems.cnes()
        : targetSystems.cnes();
  }

  public ResourceSpec resource(String type) {
    ResourceSpec spec = resources.get(type);
    return spec == null ? new ResourceSpec("omit", null) : spec;
  }

  /** Falha rápido em mapeamento incoerente (carregado na inicialização). */
  public ModelMapping validate() {
    require(model, "model");
    require(version, "version");
    if (bundle == null || !BUNDLE_TYPES.contains(bundle.type())) {
      throw new IllegalStateException(
          "mapping " + model + ": bundle.type deve ser um de " + BUNDLE_TYPES);
    }
    if (!IDENTIFIER_VALUES.contains(bundle.identifierValueOrDefault())) {
      throw new IllegalStateException(
          "mapping " + model + ": bundle.identifier_value deve ser um de " + IDENTIFIER_VALUES);
    }
    if (identifierSystems == null
        || identifierSystems.cns() == null
        || identifierSystems.cpf() == null
        || identifierSystems.cnes() == null) {
      throw new IllegalStateException("mapping " + model + ": identifier_systems cns/cpf/cnes");
    }
    resources.forEach(
        (type, spec) -> {
          if (!MODES.contains(spec.modeOrDefault())) {
            throw new IllegalStateException(
                "mapping " + model + ": resources." + type + ".mode inválido: " + spec.mode());
          }
        });
    for (RequiredRule rule : required) {
      if (!CHECKS.contains(rule.check())) {
        throw new IllegalStateException(
            "mapping " + model + ": regra '" + rule.name() + "' com check desconhecido");
      }
      if (rule.facts().isEmpty()) {
        throw new IllegalStateException(
            "mapping " + model + ": regra '" + rule.name() + "' sem facts");
      }
    }
    return this;
  }

  private void require(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("mapping RNDS sem " + field);
    }
  }
}
