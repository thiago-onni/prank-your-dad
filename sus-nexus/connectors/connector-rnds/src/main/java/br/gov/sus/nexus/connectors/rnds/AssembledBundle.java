package br.gov.sus.nexus.connectors.rnds;

import java.util.List;
import java.util.Map;
import org.hl7.fhir.r4.model.Bundle;

/**
 * Bundle pronto para envio + fatos extraídos para a pré-validação ({@code facts}, ex.: {@code
 * patient.cns}) e avisos de montagem. Vive só em memória (contém PII); nunca é logado.
 */
public record AssembledBundle(
    String model,
    String mappingVersion,
    String stableId,
    Bundle bundle,
    Map<String, String> facts,
    List<String> warnings) {

  public AssembledBundle {
    facts = facts == null ? Map.of() : Map.copyOf(facts);
    warnings = warnings == null ? List.of() : List.copyOf(warnings);
  }

  public String fact(String name) {
    return facts.get(name);
  }
}
