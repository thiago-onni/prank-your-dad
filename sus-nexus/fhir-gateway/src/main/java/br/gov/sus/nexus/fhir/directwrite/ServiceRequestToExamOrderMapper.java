package br.gov.sus.nexus.fhir.directwrite;

import br.gov.sus.nexus.fhir.FhirConstants;
import br.gov.sus.nexus.fhir.interaction.FhirException;
import br.gov.sus.nexus.fhir.mapping.MapperSettings;
import br.gov.sus.nexus.fhir.persistence.SearchIndexer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Function;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.OperationOutcome.IssueType;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.ServiceRequest;

/**
 * FHIR {@code ServiceRequest} (parceiro externo) → {@code ExamOrderRegistration}. Só pedidos de
 * exame (categoria laboratory/imaging, SNOMED 108252007/363679005 ou CodeSystem municipal {@code
 * exam-category}) são aceitos; regulação só entra pelo sistema oficial (422).
 */
@ApplicationScoped
public class ServiceRequestToExamOrderMapper {

  @Inject MapperSettings settings;

  public ServiceRequestToExamOrderMapper() {}

  public ServiceRequestToExamOrderMapper(MapperSettings settings) {
    this.settings = settings;
  }

  /** Categoria canônica (laboratory/imaging) ou vazio quando não é pedido de exame. */
  public static Optional<String> examCategory(ServiceRequest sr) {
    for (CodeableConcept cc : sr.getCategory()) {
      for (Coding c : cc.getCoding()) {
        if (FhirConstants.CS_SNOMED.equals(c.getSystem())) {
          if ("108252007".equals(c.getCode())) {
            return Optional.of("laboratory");
          }
          if ("363679005".equals(c.getCode())) {
            return Optional.of("imaging");
          }
        }
        if (FhirConstants.CS_EXAM_CATEGORY.equals(c.getSystem())
            && ("laboratory".equals(c.getCode()) || "imaging".equals(c.getCode()))) {
          return Optional.of(c.getCode());
        }
      }
    }
    return Optional.empty();
  }

  /**
   * @param cnesResolver resolve {@code Organization/id} → CNES (consulta ao fhir-db)
   */
  public ExamOrderRegistration map(
      ServiceRequest sr,
      CitizenRegistration.Source source,
      Function<String, Optional<String>> cnesResolver) {
    String category =
        examCategory(sr)
            .orElseThrow(
                () ->
                    new FhirException(
                        422,
                        IssueType.BUSINESSRULE,
                        "Escrita direta de ServiceRequest aceita apenas pedidos de exame"
                            + " (laboratory/imaging): regulação só pelo sistema oficial",
                        "ServiceRequest.category"));
    Optional<SearchIndexer.Target> subject =
        SearchIndexer.parseReference(sr.getSubject().getReference());
    if (subject.isEmpty() || !"Patient".equals(subject.get().type())) {
      throw new FhirException(
          422,
          IssueType.REQUIRED,
          "ServiceRequest.subject deve referenciar Patient",
          "ServiceRequest.subject");
    }
    Coding code =
        sr.getCode().getCoding().stream()
            .filter(c -> c.hasSystem() && c.hasCode())
            .findFirst()
            .orElseThrow(
                () ->
                    new FhirException(
                        422,
                        IssueType.REQUIRED,
                        "ServiceRequest.code sem coding",
                        "ServiceRequest.code"));
    String codeSystem;
    if (settings.loincSystem().equals(code.getSystem())) {
      codeSystem = "LOINC";
    } else if (settings.sigtapSystem().equals(code.getSystem())) {
      codeSystem = "SIGTAP";
    } else {
      codeSystem = "LOCAL";
    }
    String requestingCnes = cnes(sr.getRequester(), cnesResolver);
    String professional = null;
    if (sr.hasRequester()
        && sr.getRequester().hasIdentifier()
        && FhirConstants.SYSTEM_MUNICIPAL_PROFESSIONAL_ID.equals(
            sr.getRequester().getIdentifier().getSystem())) {
      professional = sr.getRequester().getIdentifier().getValue();
    }
    String careLine =
        sr.getExtensionsByUrl(FhirConstants.EXT_CARE_LINE).stream()
            .filter(Extension::hasValue)
            .map(e -> e.getValue().primitiveValue())
            .findFirst()
            .orElse(null);
    Instant requestedAt =
        sr.hasAuthoredOn() ? sr.getAuthoredOnElement().getValue().toInstant() : Instant.now();
    return new ExamOrderRegistration(
        source,
        new ExamOrderRegistration.CitizenRef("cit_" + subject.get().id(), null, null),
        status(sr.getStatus()),
        requestedAt,
        code.getCode(),
        codeSystem,
        sr.getCode().hasText() ? sr.getCode().getText() : code.getDisplay(),
        category,
        requestingCnes,
        professional,
        null,
        careLine,
        priority(sr.getPriority()),
        requestedAt);
  }

  private static String cnes(Reference ref, Function<String, Optional<String>> resolver) {
    if (ref == null) {
      return null;
    }
    if (ref.hasIdentifier() && FhirConstants.SYSTEM_CNES.equals(ref.getIdentifier().getSystem())) {
      return ref.getIdentifier().getValue();
    }
    if (ref.hasReference()) {
      return SearchIndexer.parseReference(ref.getReference())
          .filter(t -> "Organization".equals(t.type()))
          .flatMap(t -> resolver.apply(t.id()))
          .orElse(null);
    }
    return null;
  }

  static String status(ServiceRequest.ServiceRequestStatus status) {
    if (status == null) {
      return "requested";
    }
    return switch (status) {
      case COMPLETED -> "performed";
      case REVOKED, ENTEREDINERROR -> "cancelled";
      default -> "requested";
    };
  }

  static String priority(ServiceRequest.ServiceRequestPriority priority) {
    if (priority == null) {
      return "routine";
    }
    return switch (priority) {
      case URGENT -> "priority";
      case ASAP, STAT -> "urgent";
      default -> "routine";
    };
  }
}
