package br.gov.sus.nexus.fhir.validation;

import br.gov.sus.nexus.fhir.codec.FhirCodec;
import br.gov.sus.nexus.fhir.codec.FhirParseException;
import br.gov.sus.nexus.fhir.interaction.FhirException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.hl7.fhir.r4.model.OperationOutcome.IssueType;
import org.hl7.fhir.r4.model.Resource;

/** Fachada: parse estrito + pipeline, convertendo falhas em {@link FhirException} (400/422). */
@ApplicationScoped
public class ValidationService {

  @Inject FhirCodec codec;
  @Inject FhirValidator validator;

  /** Faz o parse estrito ou lança 400 com {@code OperationOutcome}. */
  public Resource parseOrThrow(String json) {
    try {
      return codec.parse(json);
    } catch (FhirParseException e) {
      throw new FhirException(400, IssueType.STRUCTURE, "JSON FHIR inválido: " + e.getMessage());
    }
  }

  /** Valida sem lançar (usado por {@code $validate}). */
  public List<ValidationIssue> validate(Resource resource, String expectedType) {
    return validator.validate(resource, expectedType);
  }

  /** Valida e lança 422 quando houver erros (avisos são ignorados para gravação). */
  public List<ValidationIssue> validateOrThrow(Resource resource, String expectedType) {
    List<ValidationIssue> issues = validator.validate(resource, expectedType);
    List<ValidationIssue> errors = issues.stream().filter(ValidationIssue::isError).toList();
    if (!errors.isEmpty()) {
      boolean typeMismatch =
          errors.stream()
              .anyMatch(
                  i ->
                      i.code() == IssueType.INVALID
                          && i.message().startsWith(ValidationPipeline.TYPE_MISMATCH_PREFIX));
      throw typeMismatch ? FhirException.badRequest(errors) : FhirException.unprocessable(errors);
    }
    return issues;
  }
}
