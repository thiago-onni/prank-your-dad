package br.gov.sus.nexus.fhir.validation;

import java.util.ArrayList;
import java.util.List;
import org.hl7.fhir.r4.model.Base;
import org.hl7.fhir.r4.model.OperationOutcome.IssueType;
import org.hl7.fhir.r4.model.Property;
import org.hl7.fhir.r4.model.Resource;

/**
 * Verificação estrutural usando os metadados dos próprios modelos {@code org.hl7.fhir.r4} ({@link
 * Base#children()}), sem StructureDefinitions. Os modelos gerados informam a cardinalidade máxima
 * (1 ou *), mas declaram mínimo 0 para todos os elementos; a cardinalidade mínima real (ex.: {@code
 * Patient.link.other} 1..1) é verificada pelo validador oficial ({@code ProfileValidator}). Esta
 * classe complementa o parser estrito como rede de segurança no modo {@code structural}.
 */
public final class CardinalityChecker {

  private CardinalityChecker() {}

  public static List<ValidationIssue> check(Resource resource) {
    List<ValidationIssue> issues = new ArrayList<>();
    walk(resource, resource.fhirType(), issues, 0);
    return issues;
  }

  private static void walk(Base element, String path, List<ValidationIssue> issues, int depth) {
    if (depth > 64) {
      return;
    }
    for (Property property : element.children()) {
      List<Base> values = property.getValues();
      int size = 0;
      for (Base v : values) {
        if (v != null && !v.isEmpty()) {
          size++;
        }
      }
      String childPath = path + "." + property.getName().replace("[x]", "");
      if (size < property.getMinCardinality()) {
        issues.add(
            ValidationIssue.error(
                IssueType.REQUIRED,
                "Elemento obrigatório ausente: "
                    + childPath
                    + " (mín. "
                    + property.getMinCardinality()
                    + ")",
                childPath));
      }
      if (property.getMaxCardinality() != Integer.MAX_VALUE
          && size > property.getMaxCardinality()) {
        issues.add(
            ValidationIssue.error(
                IssueType.STRUCTURE,
                "Cardinalidade excedida em "
                    + childPath
                    + " (máx. "
                    + property.getMaxCardinality()
                    + ")",
                childPath));
      }
      int index = 0;
      for (Base v : values) {
        if (v != null && !v.isEmpty() && !v.isPrimitive()) {
          String p = property.getMaxCardinality() == 1 ? childPath : childPath + "[" + index + "]";
          walk(v, p, issues, depth + 1);
        }
        index++;
      }
    }
  }
}
