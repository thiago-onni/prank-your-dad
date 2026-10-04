package br.gov.sus.nexus.fhir.validation;

import br.gov.sus.nexus.fhir.capability.CapabilityRegistry;
import br.gov.sus.nexus.fhir.capability.ResourceCapability;
import br.gov.sus.nexus.fhir.codec.FhirCodec;
import br.gov.sus.nexus.fhir.config.FhirGatewayConfig;
import br.gov.sus.nexus.fhir.fhirpath.FhirPathEvaluator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.hl7.fhir.r4.model.CanonicalType;
import org.hl7.fhir.r4.model.OperationOutcome.IssueType;
import org.hl7.fhir.r4.model.Resource;

/**
 * Pipeline de validação (seção 6.1 do plano):
 *
 * <ol>
 *   <li>tipo do recurso coerente com a URL;
 *   <li>estrutura e cardinalidade (modelos + validador oficial, se habilitado);
 *   <li>{@code meta.profile} obrigatório quando houver perfil implementado; perfis declarados
 *       desconhecidos são rejeitados;
 *   <li>terminologia básica;
 *   <li>invariantes municipais (FHIRPath).
 * </ol>
 */
@ApplicationScoped
public class ValidationPipeline implements FhirValidator {

  /** Prefixo da mensagem de tipo incoerente com a URL (mapeado para HTTP 400). */
  public static final String TYPE_MISMATCH_PREFIX = "Tipo do recurso";

  @Inject CapabilityRegistry registry;
  @Inject FhirGatewayConfig config;
  @Inject FhirPathEvaluator fhirPath;
  @Inject MunicipalInvariants invariants;
  @Inject ProfileValidator profileValidator;
  @Inject FhirCodec codec;

  @Override
  public List<ValidationIssue> validate(Resource resource, String expectedType) {
    List<ValidationIssue> issues = new ArrayList<>();
    String type = resource.fhirType();

    if (expectedType != null && !expectedType.equals(type)) {
      issues.add(
          ValidationIssue.error(
              IssueType.INVALID,
              TYPE_MISMATCH_PREFIX
                  + " ("
                  + type
                  + ") não corresponde ao tipo da URL ("
                  + expectedType
                  + ")",
              type));
      return issues;
    }

    issues.addAll(CardinalityChecker.check(resource));
    issues.addAll(profileValidator.validate(resource, codec.encode(resource)));

    Optional<ResourceCapability> capability = registry.resource(type);
    capability
        .flatMap(ResourceCapability::profile)
        .ifPresent(p -> checkProfile(resource, p, issues));

    issues.addAll(TerminologyChecks.check(resource));

    for (MunicipalInvariant inv : invariants.forType(type)) {
      boolean ok;
      try {
        ok = fhirPath.evaluateBoolean(resource, inv.expression());
      } catch (FhirPathEvaluator.FhirPathException e) {
        ok = false;
      }
      if (!ok) {
        issues.add(inv.toIssue());
      }
    }
    return issues;
  }

  private void checkProfile(Resource resource, String expected, List<ValidationIssue> issues) {
    List<String> declared =
        resource.getMeta().getProfile().stream().map(CanonicalType::getValue).toList();
    if (config.profiles().requireProfile() && !declared.contains(expected)) {
      issues.add(
          ValidationIssue.error(
              IssueType.BUSINESSRULE,
              "meta.profile deve declarar o perfil implementado " + expected,
              resource.fhirType() + ".meta.profile"));
    }
    for (String p : declared) {
      String canonical = p.contains("|") ? p.substring(0, p.indexOf('|')) : p;
      boolean known =
          canonical.equals(expected)
              || canonical.startsWith(config.profiles().baseUrl())
              || profileValidator.knowsProfile(canonical);
      if (!known) {
        issues.add(
            ValidationIssue.error(
                IssueType.NOTSUPPORTED,
                "Perfil desconhecido declarado em meta.profile: " + canonical,
                resource.fhirType() + ".meta.profile"));
      }
    }
  }
}
