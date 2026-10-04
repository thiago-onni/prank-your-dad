package br.gov.sus.nexus.fhir.validation;

import java.util.List;
import org.hl7.fhir.r4.model.Resource;

/**
 * Interface própria de validação (ADR-003). Implementações encadeiam: tipo esperado → estrutura e
 * cardinalidade → perfil → terminologia → invariantes municipais.
 */
public interface FhirValidator {

  /**
   * Valida um recurso já parseado.
   *
   * @param resource recurso
   * @param expectedType tipo exigido pela URL/interação, ou {@code null} para aceitar qualquer tipo
   * @return lista de problemas (vazia quando válido); avisos não impedem a gravação
   */
  List<ValidationIssue> validate(Resource resource, String expectedType);
}
