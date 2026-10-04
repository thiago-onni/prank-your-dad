package br.gov.sus.nexus.connectors.rnds;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.hl7.fhir.convertors.factory.VersionConvertorFactory_40_50;
import org.hl7.fhir.convertors.loaders.loaderR5.NullLoaderKnowledgeProviderR5;
import org.hl7.fhir.convertors.loaders.loaderR5.R4ToR5Loader;
import org.hl7.fhir.r4.formats.JsonParser;
import org.hl7.fhir.r4.formats.XmlParser;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Specimen;
import org.hl7.fhir.r5.conformance.profile.ProfileUtilities;
import org.hl7.fhir.r5.context.SimpleWorkerContext;
import org.hl7.fhir.r5.elementmodel.Manager.FhirFormat;
import org.hl7.fhir.r5.model.CanonicalResource;
import org.hl7.fhir.r5.model.PackageInformation;
import org.hl7.fhir.r5.model.StructureDefinition;
import org.hl7.fhir.r5.model.ValueSet;
import org.hl7.fhir.r5.utils.validation.ValidatorSession;
import org.hl7.fhir.r5.utils.validation.constants.BestPracticeWarningLevel;
import org.hl7.fhir.r5.utils.validation.constants.ReferenceValidationPolicy;
import org.hl7.fhir.r5.utils.xver.XVerExtensionManagerFactory;
import org.hl7.fhir.utilities.ByteProvider;
import org.hl7.fhir.utilities.validation.ValidationMessage;
import org.hl7.fhir.validation.ValidatorSettings;
import org.hl7.fhir.validation.instance.InstanceValidator;
import org.hl7.fhir.validation.instance.advisor.BasePolicyAdvisorForFullValidation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Valida os Bundles gerados pelo {@link BundleAssembler} contra os perfis OFICIAIS da RNDS,
 * offline, com o validador HL7 ({@code org.hl7.fhir.validation} 6.10.4, mesmo usado no
 * fhir-gateway): base FHIR 4.0.1 do artefato {@code hapi-fhir-validation-resources-r4} + definições
 * do Ministério da Saúde em {@code src/test/resources/rnds-definicoes} (origem, versões e hashes em
 * ORIGEM.txt).
 */
class RndsProfileValidationTest {

  private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");
  private static final String BR = "http://www.saude.gov.br/fhir/r4/";
  private static final List<String> RNDS_FILES =
      List.of(
          "BRTipoDocumento.xml",
          "BRTipoDocumento-1.0.xml",
          "BREstadoDocumento-1.0.xml",
          "BREstadoObservacao-1.0-duplicate-2.xml",
          "BRSubgrupoTabelaSUS.xml",
          "BRCategoriaExame-1.0.xml",
          "BRNomeExameCOVID19LOINC.xml",
          "BRNomeExameCOVID19GAL.xml",
          "BRNomeExame-1.0.xml",
          "BRResultadoQualitativoExame.xml",
          "BRResultadoQualitativoExame-1.0.xml",
          "BRTipoAmostraGAL.xml",
          "BRTipoAmostra-1.0.xml",
          "BRAmostraBiologica.xml",
          "BRDiagnosticoLaboratorioClinico.xml",
          "BRResultadoExameLaboratorial.xml");
  private static final List<String> BASE_FILES =
      List.of(
          "profile/profiles-types.xml",
          "profile/profiles-resources.xml",
          "valueset/valuesets.xml",
          "valueset/v3-codesystems.xml",
          "valueset/v2-tables.xml",
          "extension/extension-definitions.xml");
  private static final Set<String> LOADED_TYPES =
      Set.of("StructureDefinition", "ValueSet", "CodeSystem", "NamingSystem");

  private static SimpleWorkerContext context;

  @BeforeAll
  static void loadDefinitions() throws IOException {
    Map<String, ByteProvider> defs = new LinkedHashMap<>();
    for (String file : BASE_FILES) {
      try (InputStream in =
          RndsProfileValidationTest.class.getResourceAsStream("/org/hl7/fhir/r4/model/" + file)) {
        assertThat(in).as(file).isNotNull();
        byte[] bytes = in.readAllBytes();
        if (file.endsWith("valuesets.xml")) {
          bytes = stripEntry(bytes, "http://hl7.org/fhir/CodeSystem/spdx-license");
        }
        defs.put(file.substring(file.indexOf('/') + 1), ByteProvider.forBytes(bytes));
      }
    }
    defs.put(
        "version.info",
        ByteProvider.forBytes(
            "[FHIR]\nversion=4.0.1\nrevision=1\ndate=20191101\n".getBytes(StandardCharsets.UTF_8)));
    context =
        new SimpleWorkerContext.SimpleWorkerContextBuilder()
            .withAllowLoadingDuplicates(true)
            .withDefaultParams()
            .fromDefinitions(
                defs,
                new R4ToR5Loader(
                    new HashSet<>(LOADED_TYPES), new NullLoaderKnowledgeProviderR5(), "4.0.1"),
                new PackageInformation("hl7.fhir.r4.core", "4.0.1", "4.0.1", new Date()));
    context.setNoTerminologyServer(true);
    context.setCanRunWithoutTerminology(true);

    PackageInformation rnds =
        new PackageInformation("br.gov.saude.rnds.definicoes", "2021-06-08", "4.0.1", new Date());
    for (String file : RNDS_FILES) {
      try (InputStream in =
          RndsProfileValidationTest.class.getResourceAsStream("/rnds-definicoes/" + file)) {
        assertThat(in).as(file).isNotNull();
        org.hl7.fhir.r4.model.Resource r4 = new XmlParser().parse(in);
        org.hl7.fhir.r5.model.Resource r5 = VersionConvertorFactory_40_50.convertResource(r4);
        if (r5 instanceof StructureDefinition sd && !sd.hasSnapshot()) {
          StructureDefinition base =
              context.fetchResource(StructureDefinition.class, sd.getBaseDefinition());
          List<ValidationMessage> msgs = new ArrayList<>();
          new ProfileUtilities(context, msgs, null)
              .generateSnapshot(base, sd, sd.getUrl(), BR, sd.getName());
        }
        if (r5 instanceof ValueSet vs) {
          // Os ValueSets oficiais (2020) usam include.version="*" (qualquer versão), que o
          // validador 6.x trata como versão literal; omitir a versão tem o mesmo significado.
          vs.getCompose().getInclude().stream()
              .filter(i -> "*".equals(i.getVersion()))
              .forEach(i -> i.setVersion(null));
        }
        ((CanonicalResource) r5).setWebPath(BR + r5.fhirType() + "/" + r5.getIdBase());
        context.cacheResource(r5);
      }
    }
    assertThat(
            context.fetchResource(
                StructureDefinition.class,
                BR + "StructureDefinition/BRResultadoExameLaboratorial-1.1"))
        .isNotNull();
  }

  private static byte[] stripEntry(byte[] xml, String fullUrl) {
    String text = new String(xml, StandardCharsets.UTF_8);
    int at = text.indexOf("<fullUrl value=\"" + fullUrl + "\"");
    if (at < 0) return xml;
    int start = text.lastIndexOf("<entry>", at);
    int end = text.indexOf("</entry>", at) + "</entry>".length();
    return (text.substring(0, start) + text.substring(end)).getBytes(StandardCharsets.UTF_8);
  }

  private static List<ValidationMessage> validate(String json) {
    ValidatorSettings settings = new ValidatorSettings();
    settings.setAssumeValidRestReferences(true);
    InstanceValidator v =
        new InstanceValidator(
            context,
            null,
            XVerExtensionManagerFactory.createExtensionManager(context),
            new ValidatorSession(),
            settings);
    v.setPolicyAdvisor(
        new BasePolicyAdvisorForFullValidation(ReferenceValidationPolicy.IGNORE, Set.of()));
    v.setErrorForUnknownProfiles(true);
    v.setNoExtensibleWarnings(true);
    v.setBestPracticeWarningLevel(BestPracticeWarningLevel.Ignore);
    List<ValidationMessage> messages = new ArrayList<>();
    v.validate(
        null,
        messages,
        new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)),
        FhirFormat.JSON);
    return messages;
  }

  private static List<String> errors(List<ValidationMessage> messages) {
    return messages.stream()
        .filter(
            m ->
                m.getLevel() == ValidationMessage.IssueSeverity.ERROR
                    || m.getLevel() == ValidationMessage.IssueSeverity.FATAL)
        .map(m -> m.getLocation() + ": " + m.getMessage())
        .toList();
  }

  /**
   * Resultado de COVID-19 (escopo oficial do REL): IgG SARS-CoV-2 LOINC 94507-1, resultado
   * qualitativo "1" (Detectável), amostra GAL "SGHEM" (sangue); categoria 0202 (laboratório
   * clínico) derivada do código SIGTAP do DiagnosticReport da fixture.
   */
  private static SourceResources covid() throws IOException {
    SourceResources base =
        BundleAssemblerTest.exam(BundleAssemblerTest.parse("/fhir/patient.json"));
    org.hl7.fhir.r4.model.DiagnosticReport report = base.report().copy();
    Observation o = base.observations().get(0).copy();
    o.setCode(new CodeableConcept().addCoding(new Coding("http://loinc.org", "94507-1", null)));
    o.setValue(
        new CodeableConcept()
            .addCoding(new Coding(BR + "CodeSystem/BRResultadoQualitativoExame", "1", null)));
    o.getInterpretation().clear();
    o.getMethod().setText("Imunocromatográfico");
    o.getReferenceRange().clear();
    o.addReferenceRange()
        .setText("(1) Detectável = presença de anticorpos; (2) Não detectável = ausência");
    Specimen sangue = base.specimens().values().iterator().next().copy();
    sangue.setType(
        new CodeableConcept()
            .addCoding(new Coding(BR + "CodeSystem/BRTipoAmostraGAL", "SGHEM", null)));
    return new SourceResources(
        base.model(),
        base.stableId(),
        base.patient(),
        report,
        List.of(o),
        null,
        List.of(),
        null,
        Map.of(),
        base.eventData(),
        Map.of("Specimen/" + Fixtures.SPECIMEN_ID, sangue),
        null);
  }

  private static String assemble(SourceResources src) throws IOException {
    ModelMapping mapping = ModelMappingLoader.load(BundleAssemblerTest.REL);
    AssembledBundle a =
        BundleAssembler.assemble(
            mapping, src, new BundleAssembler.Context("evt_v", "document", "99", "2222222", NOW));
    assertThat(PreValidator.validate(mapping, a, "evt_v").isValid()).isTrue();
    return new JsonParser().composeString(a.bundle());
  }

  @Test
  void bundleRelCovidValidaContraPerfisOficiais() throws IOException {
    List<ValidationMessage> messages = validate(assemble(covid()));

    assertThat(errors(messages)).isEmpty();
    // Achado nas definições oficiais: o perfil v1.1 vincula Composition.status a um ValueSet
    // inexistente (BRDocumentoEstado-1.0); o validador só adverte.
    assertThat(messages)
        .filteredOn(m -> m.getLevel() == ValidationMessage.IssueSeverity.WARNING)
        .extracting(ValidationMessage::getMessage)
        .allMatch(m -> m.contains("BRDocumentoEstado-1.0"));
  }

  @Test
  void substituicaoComRelatesToValidaContraPerfilV11() throws IOException {
    List<ValidationMessage> messages =
        validate(assemble(covid().replacing("eb4fc099-e5e2-4895-9d3b-23a6de2d7324-r3x7")));

    assertThat(errors(messages)).isEmpty();
  }

  /**
   * Exames fora da tabela COVID-19 (glicose LOINC 2345-7, HbA1c 4548-4): o CodeSystem oficial
   * BRNomeExameLOINC é um "fragment", então o validador só ADVERTE (código desconhecido) — o perfil
   * não os proíbe, mas o guia restringe o REL a COVID-19 (pendência registrada no README).
   */
  @Test
  void exameForaDoEscopoCovidGeraAdvertenciaDeCodigoDesconhecido() throws IOException {
    SourceResources glicose =
        BundleAssemblerTest.exam(BundleAssemblerTest.parse("/fhir/patient.json"));

    List<ValidationMessage> messages = validate(assemble(glicose));

    assertThat(errors(messages)).isEmpty();
    assertThat(messages)
        .filteredOn(m -> m.getLevel() == ValidationMessage.IssueSeverity.WARNING)
        .extracting(ValidationMessage::getMessage)
        .anyMatch(m -> m.contains("2345-7") && m.contains("fragment"));
  }
}
