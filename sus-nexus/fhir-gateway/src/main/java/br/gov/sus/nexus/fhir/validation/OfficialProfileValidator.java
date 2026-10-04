package br.gov.sus.nexus.fhir.validation;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.hl7.fhir.convertors.loaders.loaderR5.NullLoaderKnowledgeProviderR5;
import org.hl7.fhir.convertors.loaders.loaderR5.R4ToR5Loader;
import org.hl7.fhir.r4.model.OperationOutcome.IssueSeverity;
import org.hl7.fhir.r4.model.OperationOutcome.IssueType;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r5.context.SimpleWorkerContext;
import org.hl7.fhir.r5.elementmodel.Manager.FhirFormat;
import org.hl7.fhir.r5.model.PackageInformation;
import org.hl7.fhir.r5.model.StructureDefinition;
import org.hl7.fhir.r5.utils.validation.ValidatorSession;
import org.hl7.fhir.r5.utils.validation.constants.BestPracticeWarningLevel;
import org.hl7.fhir.r5.utils.validation.constants.ReferenceValidationPolicy;
import org.hl7.fhir.r5.utils.xver.XVerExtensionManagerFactory;
import org.hl7.fhir.utilities.ByteProvider;
import org.hl7.fhir.utilities.npm.NpmPackage;
import org.hl7.fhir.utilities.validation.ValidationMessage;
import org.hl7.fhir.validation.ValidatorSettings;
import org.hl7.fhir.validation.instance.InstanceValidator;
import org.hl7.fhir.validation.instance.advisor.BasePolicyAdvisorForFullValidation;
import org.jboss.logging.Logger;

/**
 * Validador oficial HL7 ({@code org.hl7.fhir.validation.InstanceValidator}) usado <b>como
 * biblioteca</b> (ADR-003), totalmente offline:
 *
 * <ul>
 *   <li>definições base FHIR 4.0.1 carregadas do classpath (artefato Maven {@code
 *       hapi-fhir-validation-resources-r4}: profiles-types/resources, valuesets, extensions),
 *       convertidas R4→R5 pelo {@code R4ToR5Loader} — sem download de {@code hl7.fhir.r4.core};
 *   <li>pacotes NPM de IGs (br-core, RNDS) opcionais, lidos do classpath ({@code
 *       sus.fhir.validation.ig-packages});
 *   <li>sem servidor de terminologia (code systems externos como CBO/CID não são expandidos).
 * </ul>
 */
public class OfficialProfileValidator implements ProfileValidator {

  private static final Logger LOG = Logger.getLogger(OfficialProfileValidator.class);

  private static final String DEFINITIONS_ROOT = "/org/hl7/fhir/r4/model/";
  private static final String SPDX_CODESYSTEM_FULL_URL =
      "http://hl7.org/fhir/CodeSystem/spdx-license";
  private static final List<String> DEFINITION_FILES =
      List.of(
          "profile/profiles-types.xml",
          "profile/profiles-resources.xml",
          "valueset/valuesets.xml",
          "valueset/v3-codesystems.xml",
          "valueset/v2-tables.xml",
          "extension/extension-definitions.xml");
  private static final Set<String> LOADED_TYPES =
      Set.of(
          "StructureDefinition",
          "ValueSet",
          "CodeSystem",
          "ConceptMap",
          "SearchParameter",
          "OperationDefinition",
          "NamingSystem");

  private final SimpleWorkerContext context;
  private final List<String> loadedPackages = new ArrayList<>();

  public OfficialProfileValidator(List<String> igPackageResources) {
    long start = System.currentTimeMillis();
    this.context = loadBaseContext();
    for (String resource : igPackageResources) {
      loadIgPackage(resource);
    }
    LOG.infof(
        "Validador oficial HL7 carregado em %d ms (base FHIR 4.0.1 + %d pacote(s) de IG)",
        System.currentTimeMillis() - start, loadedPackages.size());
  }

  private static SimpleWorkerContext loadBaseContext() {
    try {
      Map<String, ByteProvider> defs = new LinkedHashMap<>();
      for (String file : DEFINITION_FILES) {
        try (InputStream in =
            OfficialProfileValidator.class.getResourceAsStream(DEFINITIONS_ROOT + file)) {
          if (in == null) {
            throw new IllegalStateException("Definição FHIR ausente no classpath: " + file);
          }
          byte[] bytes = in.readAllBytes();
          if (file.endsWith("valuesets.xml")) {
            bytes = stripEntry(bytes, SPDX_CODESYSTEM_FULL_URL);
          }
          defs.put(file.substring(file.indexOf('/') + 1), ByteProvider.forBytes(bytes));
        }
      }
      defs.put(
          "version.info",
          ByteProvider.forBytes(
              "[FHIR]\nversion=4.0.1\nrevision=1\ndate=20191101\n"
                  .getBytes(StandardCharsets.UTF_8)));
      R4ToR5Loader loader =
          new R4ToR5Loader(
              new HashSet<>(LOADED_TYPES), new NullLoaderKnowledgeProviderR5(), "4.0.1");
      PackageInformation pi =
          new PackageInformation("hl7.fhir.r4.core", "4.0.1", "4.0.1", new Date());
      SimpleWorkerContext ctx =
          new SimpleWorkerContext.SimpleWorkerContextBuilder()
              .withAllowLoadingDuplicates(true)
              .withDefaultParams()
              .fromDefinitions(defs, loader, pi);
      ctx.setNoTerminologyServer(true);
      ctx.setCanRunWithoutTerminology(true);
      return ctx;
    } catch (IOException e) {
      throw new IllegalStateException("Falha ao carregar definições FHIR 4.0.1", e);
    }
  }

  /**
   * O {@code SimpleWorkerContextBuilder} injeta um CodeSystem spdx-license embutido após carregar
   * as definições e, no caminho {@code fromDefinitions}, não aplica {@code allowLoadingDuplicates};
   * removemos a entrada equivalente do bundle para evitar "Duplicate Resource".
   */
  static byte[] stripEntry(byte[] xml, String fullUrl) {
    String text = new String(xml, StandardCharsets.UTF_8);
    int at = text.indexOf("<fullUrl value=\"" + fullUrl + "\"");
    if (at < 0) {
      return xml;
    }
    int start = text.lastIndexOf("<entry>", at);
    int end = text.indexOf("</entry>", at);
    if (start < 0 || end < 0) {
      return xml;
    }
    end += "</entry>".length();
    return (text.substring(0, start) + text.substring(end)).getBytes(StandardCharsets.UTF_8);
  }

  private void loadIgPackage(String classpathResource) {
    String path = classpathResource.startsWith("/") ? classpathResource : "/" + classpathResource;
    try (InputStream in = OfficialProfileValidator.class.getResourceAsStream(path)) {
      if (in == null) {
        LOG.warnf("Pacote de IG não encontrado no classpath: %s", path);
        return;
      }
      NpmPackage npm = NpmPackage.fromPackage(in);
      R4ToR5Loader loader =
          new R4ToR5Loader(
              new HashSet<>(LOADED_TYPES), new NullLoaderKnowledgeProviderR5(), "4.0.1");
      int n = context.loadFromPackage(npm, loader, true);
      loadedPackages.add(npm.name() + "#" + npm.version());
      LOG.infof("Pacote de IG carregado: %s#%s (%d recursos)", npm.name(), npm.version(), n);
    } catch (IOException e) {
      throw new IllegalStateException("Falha ao carregar pacote de IG " + path, e);
    }
  }

  @Override
  public List<ValidationIssue> validate(Resource resource, String json) {
    InstanceValidator validator = newValidator();
    List<ValidationMessage> messages = new ArrayList<>();
    try {
      validator.validate(
          null,
          messages,
          new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)),
          FhirFormat.JSON);
    } catch (RuntimeException e) {
      LOG.warnf(
          "Validador oficial falhou para %s: %s: %s",
          resource.fhirType(), e.getClass().getSimpleName(), e.getMessage());
      String detail = e.getMessage() == null ? "" : ": " + e.getMessage();
      return List.of(
          ValidationIssue.error(
              IssueType.STRUCTURE,
              "Falha do validador oficial (" + e.getClass().getSimpleName() + ")" + detail,
              resource.fhirType()));
    }
    List<ValidationIssue> issues = new ArrayList<>();
    for (ValidationMessage m : messages) {
      IssueSeverity severity =
          switch (m.getLevel()) {
            case FATAL, ERROR -> IssueSeverity.ERROR;
            case WARNING -> IssueSeverity.WARNING;
            default -> null;
          };
      if (severity == null) {
        continue;
      }
      IssueType type;
      try {
        type = IssueType.fromCode(m.getType().toCode());
      } catch (RuntimeException e) {
        type = IssueType.INVALID;
      }
      issues.add(new ValidationIssue(severity, type, m.getMessage(), m.getLocation()));
    }
    return issues;
  }

  private InstanceValidator newValidator() {
    ValidatorSettings settings = new ValidatorSettings();
    settings.setAssumeValidRestReferences(true);
    InstanceValidator v =
        new InstanceValidator(
            context,
            null,
            XVerExtensionManagerFactory.createExtensionManager(context),
            new ValidatorSession(),
            settings);
    v.setAssumeValidRestReferences(true);
    // referências relativas (Organization/x) não são resolvidas aqui: existência é garantida pelo
    // gateway/core, não pelo validador
    v.setPolicyAdvisor(
        new BasePolicyAdvisorForFullValidation(ReferenceValidationPolicy.IGNORE, Set.of()));
    v.setErrorForUnknownProfiles(false);
    v.setAnyExtensionsAllowed(true);
    v.setNoExtensibleWarnings(true);
    v.setBestPracticeWarningLevel(BestPracticeWarningLevel.Ignore);
    v.setAllowExamples(true);
    return v;
  }

  @Override
  public String describe() {
    return "official: HL7 InstanceValidator "
        + context.getVersion()
        + " (definições base do classpath"
        + (loadedPackages.isEmpty() ? "" : ", IGs: " + String.join(", ", loadedPackages))
        + ")";
  }

  @Override
  public boolean knowsProfile(String canonical) {
    return context.fetchResource(StructureDefinition.class, canonical) != null;
  }
}
