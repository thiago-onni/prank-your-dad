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
import org.junit.jupiter.api.Test;

/** Montagem pura (sem HTTP) a partir das fixtures no formato do fhir-gateway. */
class BundleAssemblerTest {

  private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");

  @SuppressWarnings("unchecked")
  private static <T> T parse(String fixture) throws IOException {
    return (T) new JsonParser().parse(Fixtures.read(fixture));
  }

  private static SourceResources exam(Patient patient) throws IOException {
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
        Map.of("performer_cnes", "1234567"));
  }

  @Test
  void resultadoExameComoTransaction() throws IOException {
    ModelMapping mapping = ModelMappingLoader.load("mappings/rnds-resultado-exame-1.0.0.yaml");
    AssembledBundle a =
        BundleAssembler.assemble(
            mapping,
            exam(parse("/fhir/patient.json")),
            new BundleAssembler.Context("evt_x", "transaction", null, "2222222", NOW));

    Bundle b = a.bundle();
    assertThat(b.getType()).isEqualTo(Bundle.BundleType.TRANSACTION);
    assertThat(b.getEntry()).hasSize(3); // sem Composition
    assertThat(b.getEntry())
        .allMatch(e -> e.getRequest().getMethod() == Bundle.HTTPVerb.POST)
        .extracting(e -> e.getRequest().getUrl())
        .containsExactly("DiagnosticReport", "Observation", "Observation");
    DiagnosticReport report = (DiagnosticReport) b.getEntryFirstRep().getResource();
    assertThat(report.getIdElement().isEmpty()).isTrue();
    assertThat(a.facts())
        .containsEntry("patient.cns", Fixtures.CNS)
        .containsEntry("performer.cnes", "1234567")
        .containsEntry("exam.code", "0202010473")
        .containsEntry("report.status", "final")
        .containsKey("exam.date");
    assertThat(PreValidator.validate(mapping, a, "evt_x").isValid()).isTrue();
  }

  @Test
  void preValidacaoListaTodasAsFalhasSemValores() throws IOException {
    ModelMapping mapping = ModelMappingLoader.load("mappings/rnds-resultado-exame-1.0.0.yaml");
    SourceResources src = exam(parse("/fhir/patient-no-ids.json"));
    DiagnosticReport preliminary = src.report().copy();
    preliminary.setStatus(DiagnosticReport.DiagnosticReportStatus.PRELIMINARY);
    preliminary.getPerformer().clear();
    SourceResources changed =
        new SourceResources(
            src.model(),
            src.stableId(),
            src.patient(),
            preliminary,
            src.observations(),
            src.serviceRequest(),
            List.of(),
            null,
            Map.of(),
            Map.of());

    AssembledBundle a =
        BundleAssembler.assemble(
            mapping, changed, new BundleAssembler.Context("evt_y", "document", null, null, NOW));
    ValidationReport report = PreValidator.validate(mapping, a, "evt_y");

    assertThat(report.isValid()).isFalse();
    assertThat(report.errors())
        .extracting(ValidationReport.Issue::field)
        .containsExactlyInAnyOrder("patient_identifier", "performer_cnes", "final_status");
  }

  @Test
  void sumarioDeAltaComCondicaoCid10() throws IOException {
    ModelMapping mapping = ModelMappingLoader.load("mappings/rnds-sumario-alta-1.0.0.yaml");
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
    assertThat(b.getIdentifier().getValue()).isEqualTo(Fixtures.HEP + "-v4");
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
