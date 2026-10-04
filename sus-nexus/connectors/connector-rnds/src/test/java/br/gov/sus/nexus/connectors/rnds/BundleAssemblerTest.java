package br.gov.sus.nexus.connectors.rnds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.gov.sus.nexus.connectors.sdk.api.ValidationReport;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.hl7.fhir.r4.formats.JsonParser;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Composition;
import org.hl7.fhir.r4.model.Condition;
import org.hl7.fhir.r4.model.DiagnosticReport;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Specimen;
import org.junit.jupiter.api.Test;

/** Montagem pura (sem HTTP) a partir das fixtures no formato do fhir-gateway. */
class BundleAssemblerTest {

  private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");

  @SuppressWarnings("unchecked")
  static <T> T parse(String fixture) throws IOException {
    return (T) new JsonParser().parse(Fixtures.read(fixture));
  }

  static final String REL = "mappings/rnds-resultado-exame-1.1.0.yaml";
  static final String BR = "http://www.saude.gov.br/fhir/r4/";

  static SourceResources exam(Patient patient) throws IOException {
    Specimen specimen = parse("/fhir/specimen.json");
    return new SourceResources(
        BundleAssembler.RESULTADO_EXAME,
        Fixtures.EXR,
        patient,
        parse("/fhir/diagnostic-report.json"),
        List.of(parse("/fhir/observation-1.json"), (Observation) parse("/fhir/observation-2.json")),
        parse("/fhir/service-request.json"),
        List.of(),
        null,
        Map.of(),
        Map.of("performer_cnes", "1234567"),
        Map.of("Specimen/" + Fixtures.SPECIMEN_ID, specimen),
        null);
  }

  @Test
  void resultadoExameConformeModeloComputacionalDaRnds() throws IOException {
    ModelMapping mapping = ModelMappingLoader.load(REL);
    AssembledBundle a =
        BundleAssembler.assemble(
            mapping,
            exam(parse("/fhir/patient.json")),
            new BundleAssembler.Context("evt_x", "document", "99", "2222222", NOW));

    Bundle b = a.bundle();
    assertThat(b.getType()).isEqualTo(Bundle.BundleType.DOCUMENT);
    assertThat(b.getIdentifier().getSystem()).isEqualTo(BR + "NamingSystem/BRRNDS-99");
    assertThat(b.getIdentifier().getValue()).isEqualTo(Fixtures.EXR); // igual em substituições
    // Composition primeiro; Observation → Specimen (compartilhado pelas duas Observations)
    assertThat(b.getEntry())
        .extracting(e -> e.getResource().fhirType())
        .containsExactly("Composition", "Observation", "Specimen", "Observation");
    assertThat(b.getEntry()).allMatch(e -> e.getFullUrl().startsWith("urn:uuid:"));
    assertThat(b.getEntry()).allMatch(e -> !e.getResource().getIdElement().hasIdPart());

    Composition c = (Composition) b.getEntryFirstRep().getResource();
    assertThat(c.getMeta().getProfile().get(0).getValue())
        .isEqualTo(BR + "StructureDefinition/BRResultadoExameLaboratorial-1.1");
    assertThat(c.getStatus()).isEqualTo(Composition.CompositionStatus.FINAL);
    assertThat(c.getType().getCodingFirstRep().getSystem())
        .isEqualTo(BR + "CodeSystem/BRTipoDocumento");
    assertThat(c.getType().getCodingFirstRep().getCode()).isEqualTo("REL");
    assertThat(c.getType().getCodingFirstRep().hasDisplay()).isFalse(); // display 0..0
    assertThat(c.getTitle()).isEqualTo("Resultado de Exame Laboratorial");
    assertThat(c.getSubject().getIdentifier().getSystem())
        .isEqualTo(BR + "StructureDefinition/BRIndividuo-1.0");
    assertThat(c.getSubject().getIdentifier().getValue()).isEqualTo(Fixtures.CNS);
    assertThat(c.getSubject().hasType()).isFalse();
    assertThat(c.getAuthorFirstRep().getIdentifier().getSystem())
        .isEqualTo(BR + "StructureDefinition/BREstabelecimentoSaude-1.0");
    assertThat(c.getAuthorFirstRep().getIdentifier().getValue()).isEqualTo("2222222");
    assertThat(c.hasRelatesTo()).isFalse();
    assertThat(c.getSection()).hasSize(1);
    assertThat(c.getSectionFirstRep().hasTitle()).isFalse();
    assertThat(c.getSectionFirstRep().getEntry())
        .extracting(r -> r.getReference())
        .containsExactly(b.getEntry().get(1).getFullUrl(), b.getEntry().get(3).getFullUrl());

    Observation glicose = (Observation) b.getEntry().get(1).getResource();
    assertThat(glicose.getMeta().getProfile().get(0).getValue())
        .isEqualTo(BR + "StructureDefinition/BRDiagnosticoLaboratorioClinico-1.0");
    assertThat(glicose.getStatus()).isEqualTo(Observation.ObservationStatus.FINAL);
    assertThat(glicose.getCategoryFirstRep().getCodingFirstRep().getSystem())
        .isEqualTo(BR + "CodeSystem/BRSubgrupoTabelaSUS");
    assertThat(glicose.getCategoryFirstRep().getCodingFirstRep().getCode())
        .isEqualTo("0202"); // de SIGTAP 0202010473
    assertThat(glicose.getCode().getCodingFirstRep().getSystem())
        .isEqualTo(BR + "CodeSystem/BRNomeExameLOINC");
    assertThat(glicose.getCode().getCodingFirstRep().getCode()).isEqualTo("2345-7");
    assertThat(glicose.getCode().getCodingFirstRep().hasDisplay()).isFalse();
    assertThat(glicose.hasEffective()).isFalse(); // v1.0: effective[x] 0..0
    assertThat(glicose.hasIssued()).isTrue();
    assertThat(glicose.getPerformer()).hasSize(1);
    assertThat(glicose.getPerformerFirstRep().getIdentifier().getSystem())
        .isEqualTo(BR + "StructureDefinition/BREstabelecimentoSaude-1.0");
    assertThat(glicose.getPerformerFirstRep().getIdentifier().getValue()).isEqualTo("1234567");
    assertThat(glicose.getValueQuantity().getValue()).isEqualByComparingTo("98");
    assertThat(glicose.hasInterpretation()).isFalse(); // v3 "N" não é BRResultadoQualitativoExame
    assertThat(glicose.getMethod().getText()).startsWith("Enzimático");
    assertThat(glicose.getReferenceRangeFirstRep().getText()).isEqualTo("70 a 99 mg/dL");
    assertThat(glicose.getSpecimen().getReference()).isEqualTo(b.getEntry().get(2).getFullUrl());
    assertThat(glicose.getExtension()).isEmpty();
    assertThat(glicose.hasIdentifier() || glicose.hasBasedOn() || glicose.hasEncounter()).isFalse();
    Observation hba1c = (Observation) b.getEntry().get(3).getResource();
    assertThat(hba1c.getMethod().getText()).contains("HPLC");
    assertThat(hba1c.getNoteFirstRep().getText()).isEqualTo("Amostra hemolisada leve");
    assertThat(hba1c.getSpecimen().getReference()).isEqualTo(b.getEntry().get(2).getFullUrl());
    Specimen sp = (Specimen) b.getEntry().get(2).getResource();
    assertThat(sp.getMeta().getProfile().get(0).getValue())
        .isEqualTo(BR + "StructureDefinition/BRAmostraBiologica-1.0");
    assertThat(sp.getType().getCodingFirstRep().getCode()).isEqualTo("SER");
    assertThat(sp.hasSubject() || sp.hasIdentifier() || sp.hasCollection() || sp.hasStatus())
        .isFalse();

    assertThat(a.facts())
        .containsEntry("patient.cns", Fixtures.CNS)
        .containsEntry("performer.cnes", "1234567")
        .containsEntry("exam.code", "2345-7")
        .containsEntry("report.status", "final")
        .containsEntry("requester.solicitante_id", "99");
    assertThat(PreValidator.validate(mapping, a, "evt_x").isValid()).isTrue();
  }

  @Test
  void resultadoRetificadoSubstituiDocumentoAceitoComRelatesTo() throws IOException {
    ModelMapping mapping = ModelMappingLoader.load(REL);
    SourceResources src = exam(parse("/fhir/patient.json"));
    DiagnosticReport amended = src.report().copy();
    amended.setStatus(DiagnosticReport.DiagnosticReportStatus.AMENDED);
    SourceResources changed =
        new SourceResources(
                src.model(),
                src.stableId(),
                src.patient(),
                amended,
                src.observations(),
                src.serviceRequest(),
                List.of(),
                null,
                Map.of(),
                src.eventData(),
                src.specimens(),
                null)
            .replacing("eb4fc099-e5e2-4895-9d3b-23a6de2d7324-r3x7");

    AssembledBundle a =
        BundleAssembler.assemble(
            mapping, changed, new BundleAssembler.Context("evt_r", "document", "99", null, NOW));

    Bundle b = a.bundle();
    assertThat(b.getIdentifier().getValue()).isEqualTo(Fixtures.EXR); // mesmo identifier
    Composition c = (Composition) b.getEntryFirstRep().getResource();
    assertThat(c.getRelatesTo()).hasSize(1);
    assertThat(c.getRelatesToFirstRep().getCode())
        .isEqualTo(Composition.DocumentRelationshipType.REPLACES);
    assertThat(c.getRelatesToFirstRep().getTargetReference().getReference())
        .isEqualTo("Composition/eb4fc099-e5e2-4895-9d3b-23a6de2d7324-r3x7");
    // EHR-ERR924: documento enviado sempre com status final
    assertThat(((Observation) b.getEntry().get(1).getResource()).getStatus())
        .isEqualTo(Observation.ObservationStatus.FINAL);
    assertThat(c.getAuthorFirstRep().getIdentifier().getValue()).isEqualTo("1234567");
    assertThat(PreValidator.validate(mapping, a, "evt_r").isValid()).isTrue();
  }

  @Test
  void preValidacaoListaTodasAsFalhasSemValores() throws IOException {
    ModelMapping mapping = ModelMappingLoader.load(REL);
    SourceResources src = exam(parse("/fhir/patient.json"));
    Patient onlyCpf = src.patient().copy();
    onlyCpf.getIdentifier().removeIf(i -> i.getSystem().endsWith("/cns")); // REL exige CNS
    DiagnosticReport preliminary = src.report().copy();
    preliminary.setStatus(DiagnosticReport.DiagnosticReportStatus.PRELIMINARY);
    preliminary.getPerformer().clear();
    preliminary.getCode().getCoding().clear();
    Observation semCodigo = src.observations().get(0).copy();
    semCodigo.getCode().getCodingFirstRep().setSystem("http://exemplo.local/exames");
    semCodigo.setMethod(null);
    SourceResources changed =
        new SourceResources(
            src.model(),
            src.stableId(),
            onlyCpf,
            preliminary,
            List.of(semCodigo),
            null,
            List.of(),
            null,
            Map.of(),
            Map.of(),
            Map.of(), // sem Specimen
            null);

    AssembledBundle a =
        BundleAssembler.assemble(
            mapping, changed, new BundleAssembler.Context("evt_y", "document", null, null, NOW));
    ValidationReport report = PreValidator.validate(mapping, a, "evt_y");

    assertThat(report.isValid()).isFalse();
    assertThat(report.errors())
        .extracting(ValidationReport.Issue::field)
        .containsExactlyInAnyOrder(
            "solicitante_id",
            "patient_cns",
            "performer_cnes",
            "exam_code",
            "exam_code_system",
            "exam_category",
            "method",
            "specimen_type",
            "final_status");
    assertThat(report.errors()).noneMatch(i -> i.message().contains(Fixtures.CPF));
  }

  @Test
  void sumarioDeAltaComCondicaoCid10() throws IOException {
    ModelMapping mapping = ModelMappingLoader.load("mappings/rnds-sumario-alta-1.1.0.yaml");
    Patient patient = parse("/fhir/patient.json");
    patient.getIdentifier().removeIf(i -> i.getSystem().endsWith("/cns")); // só CPF
    Encounter encounter = parse("/fhir/encounter.json");
    SourceResources src =
        new SourceResources(
            BundleAssembler.SUMARIO_ALTA,
            Fixtures.HEP,
            patient,
            null,
            List.of(),
            null,
            List.of(),
            encounter,
            Map.of("Organization/org-hospital", "7654321"),
            Map.of("principal_diagnosis_code", "I500", "hospital_cnes", "7654321"));

    AssembledBundle a =
        BundleAssembler.assemble(
            mapping, src, new BundleAssembler.Context("evt_alta", "document", "SOLIC", null, NOW));

    Bundle b = a.bundle();
    assertThat(b.getType()).isEqualTo(Bundle.BundleType.DOCUMENT);
    assertThat(b.getIdentifier().getSystem()).endsWith("BRRNDS-SOLIC");
    assertThat(b.getIdentifier().getValue()).isEqualTo(Fixtures.HEP);
    Composition c = (Composition) b.getEntry().get(0).getResource();
    assertThat(c.getTitle()).isEqualTo("Sumário de Alta");
    assertThat(c.getAuthorFirstRep().getIdentifier().getValue()).isEqualTo("7654321");
    assertThat(c.getEncounter().getReference()).isEqualTo(b.getEntry().get(1).getFullUrl());
    Encounter e = (Encounter) b.getEntry().get(1).getResource();
    assertThat(e.getSubject().getIdentifier().getSystem()).endsWith("/cpf");
    assertThat(e.getSubject().getIdentifier().getValue()).isEqualTo(Fixtures.CPF);
    assertThat(e.getServiceProvider().getIdentifier().getValue()).isEqualTo("7654321");
    assertThat(e.getServiceProvider().hasReference()).isFalse();
    assertThat(e.hasLocation()).isFalse();
    assertThat(e.getHospitalization().hasDestination()).isFalse(); // Location não resolvida
    assertThat(e.getIdentifier())
        .extracting(i -> i.getSystem())
        .containsExactly("http://www.saude.gov.br/fhir/r4/NamingSystem/aih");
    assertThat(e.getDiagnosisFirstRep().getCondition().getReference())
        .isEqualTo(b.getEntry().get(2).getFullUrl());
    Condition dx = (Condition) b.getEntry().get(2).getResource();
    assertThat(dx.getCode().getCodingFirstRep().getCode()).isEqualTo("I500");
    assertThat(dx.getCode().getCodingFirstRep().getSystem()).contains("BRCID10");
    assertThat(PreValidator.validate(mapping, a, "evt_alta").isValid()).isTrue();
    assertThat(a.warnings()).anyMatch(w -> w.contains("Location"));
  }

  @Test
  void mapeamentoComCheckDesconhecidoFalhaNaCarga() {
    assertThatThrownBy(() -> ModelMappingLoader.load("mappings/invalid-check.yaml"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("check desconhecido");
  }

  @Test
  void validaDocumentos() {
    assertThat(Documents.validCns(Fixtures.CNS)).isTrue();
    assertThat(Documents.validCns("700123456789011")).isFalse();
    assertThat(Documents.validCpf(Fixtures.CPF)).isTrue();
    assertThat(Documents.validCpf("529.982.247-25")).isTrue();
    assertThat(Documents.validCpf("11111111111")).isFalse();
    assertThat(Documents.validCnes("1234567")).isTrue();
    assertThat(Documents.validCnes("123")).isFalse();
  }
}
