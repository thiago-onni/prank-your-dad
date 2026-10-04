package br.gov.sus.nexus.fhir.security;

import br.gov.sus.nexus.fhir.capability.CapabilityRegistry;
import br.gov.sus.nexus.fhir.capability.SearchParamDef;
import br.gov.sus.nexus.fhir.fhirpath.FhirPathEvaluator;
import br.gov.sus.nexus.fhir.persistence.SearchIndexer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import org.hl7.fhir.r4.model.Base;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;

/**
 * Pertencimento ao compartimento de um paciente, avaliado com a mesma expressão FHIRPath do
 * parâmetro {@code patient} registrado para o tipo ({@code Patient} pertence a si mesmo).
 */
@ApplicationScoped
public class PatientCompartment {

  @Inject CapabilityRegistry registry;
  @Inject FhirPathEvaluator fhirPath;

  public boolean belongsTo(Resource resource, String patientId) {
    if (resource instanceof org.hl7.fhir.r4.model.Patient) {
      return patientId.equals(resource.getIdElement().getIdPart());
    }
    Optional<SearchParamDef> def =
        registry.searchParam(resource.fhirType(), CapabilityRegistry.PATIENT_PARAM);
    if (def.isEmpty() || def.get().isComputed()) {
      return false;
    }
    for (Base b : fhirPath.evaluate(resource, def.get().expression())) {
      if (b instanceof Reference ref && ref.hasReference()) {
        Optional<SearchIndexer.Target> t = SearchIndexer.parseReference(ref.getReference());
        if (t.isPresent() && "Patient".equals(t.get().type()) && patientId.equals(t.get().id())) {
          return true;
        }
      }
    }
    return false;
  }
}
