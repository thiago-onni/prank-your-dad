package br.gov.sus.nexus.fhir.validation;

import java.util.List;
import org.hl7.fhir.r4.model.Resource;

/**
 * Modo {@code structural}: nenhuma StructureDefinition carregada. A validação se apoia no parser
 * estrito, no {@link CardinalityChecker}, na terminologia local e nos invariantes FHIRPath. Perfis
 * br-core ficam como ponto de extensão (veja {@code fhir/profiles/README.md}).
 */
public class StructuralProfileValidator implements ProfileValidator {

  @Override
  public List<ValidationIssue> validate(Resource resource, String json) {
    return List.of();
  }

  @Override
  public String describe() {
    return "structural: parser estrito + cardinalidade dos modelos + terminologia local + FHIRPath"
        + " (sem StructureDefinitions)";
  }

  @Override
  public boolean knowsProfile(String canonical) {
    return false;
  }
}
