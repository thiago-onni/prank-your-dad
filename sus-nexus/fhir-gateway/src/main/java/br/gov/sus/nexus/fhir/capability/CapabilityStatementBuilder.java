package br.gov.sus.nexus.fhir.capability;

import br.gov.sus.nexus.fhir.FhirConstants;
import br.gov.sus.nexus.fhir.validation.ProfileValidator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Date;
import org.hl7.fhir.r4.model.CapabilityStatement;
import org.hl7.fhir.r4.model.CapabilityStatement.CapabilityStatementKind;
import org.hl7.fhir.r4.model.CapabilityStatement.CapabilityStatementRestComponent;
import org.hl7.fhir.r4.model.CapabilityStatement.CapabilityStatementRestResourceComponent;
import org.hl7.fhir.r4.model.CapabilityStatement.CapabilityStatementRestResourceSearchParamComponent;
import org.hl7.fhir.r4.model.CapabilityStatement.ConditionalDeleteStatus;
import org.hl7.fhir.r4.model.CapabilityStatement.ConditionalReadStatus;
import org.hl7.fhir.r4.model.CapabilityStatement.ResourceVersionPolicy;
import org.hl7.fhir.r4.model.CapabilityStatement.RestfulCapabilityMode;
import org.hl7.fhir.r4.model.CapabilityStatement.TypeRestfulInteraction;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Enumerations.FHIRVersion;
import org.hl7.fhir.r4.model.Enumerations.PublicationStatus;
import org.hl7.fhir.r4.model.Enumerations.SearchParamType;

/** Gera o {@code CapabilityStatement} exclusivamente a partir do {@link CapabilityRegistry}. */
@ApplicationScoped
public class CapabilityStatementBuilder {

  @Inject CapabilityRegistry registry;
  @Inject ProfileValidator profileValidator;

  public CapabilityStatement build(String baseUrl) {
    CapabilityStatement cs = new CapabilityStatement();
    cs.setId("sus-nexus-fhir-gateway");
    cs.setUrl(FhirConstants.SUS_NEXUS_BASE + "/CapabilityStatement/sus-nexus-fhir-gateway");
    cs.setName("SUSNexusFhirGateway");
    cs.setTitle("SUS Nexus — FHIR Gateway municipal");
    cs.setStatus(PublicationStatus.ACTIVE);
    cs.setExperimental(false);
    cs.setDate(new Date());
    cs.setPublisher("SUS Nexus");
    cs.setDescription(
        "Gateway FHIR R4 próprio (ADR-003). Esta declaração é gerada a partir do registro de"
            + " capacidades do código: tudo o que está listado é implementado e vice-versa.");
    cs.setKind(CapabilityStatementKind.INSTANCE);
    cs.getSoftware().setName("sus-nexus-fhir-gateway").setVersion("0.1.0");
    cs.getSoftware()
        .addExtension(
            FhirConstants.SUS_NEXUS_BASE + "/StructureDefinition/validator-mode",
            new org.hl7.fhir.r4.model.StringType(profileValidator.describe()));
    cs.getImplementation().setDescription("Instância municipal").setUrl(baseUrl);
    cs.setFhirVersion(FHIRVersion._4_0_1);
    cs.addFormat("application/fhir+json");
    cs.addFormat("json");

    CapabilityStatementRestComponent rest = cs.addRest();
    rest.setMode(RestfulCapabilityMode.SERVER);
    rest.setDocumentation(
        "Autenticação OIDC (Keycloak) com escopos SMART-like (patient/*.read, user/*.read,"
            + " user/*.write, system/*.read, system/*.write). Tenant pelo claim municipality_id.");
    rest.getSecurity()
        .setCors(false)
        .addService(
            new CodeableConcept()
                .addCoding(
                    new Coding()
                        .setSystem("http://terminology.hl7.org/CodeSystem/restful-security-service")
                        .setCode("SMART-on-FHIR")))
        .setDescription("Bearer JWT; escopos SMART-like no claim 'scope'.");

    for (ResourceCapability cap : registry.all()) {
      CapabilityStatementRestResourceComponent res = rest.addResource();
      res.setType(cap.type());
      cap.profile().ifPresent(res::setProfile);
      for (Interaction i : cap.interactions()) {
        res.addInteraction().setCode(TypeRestfulInteraction.fromCode(i.code()));
      }
      res.setVersioning(ResourceVersionPolicy.VERSIONED);
      res.setReadHistory(cap.supports(Interaction.HISTORY_INSTANCE));
      res.setUpdateCreate(cap.supports(Interaction.UPDATE));
      res.setConditionalCreate(false);
      res.setConditionalRead(ConditionalReadStatus.NOTSUPPORTED);
      res.setConditionalUpdate(false);
      res.setConditionalDelete(ConditionalDeleteStatus.NOTSUPPORTED);
      if (cap.supports(Interaction.SEARCH_TYPE)) {
        for (SearchParamDef p : registry.commonParams()) {
          addParam(res, p);
        }
        for (SearchParamDef p : cap.searchParams().values()) {
          addParam(res, p);
        }
      }
    }
    for (String op : CapabilityRegistry.SYSTEM_OPERATIONS) {
      rest.addOperation()
          .setName(op)
          .setDefinition("http://hl7.org/fhir/OperationDefinition/Resource-" + op);
    }
    return cs;
  }

  private static void addParam(CapabilityStatementRestResourceComponent res, SearchParamDef p) {
    CapabilityStatementRestResourceSearchParamComponent sp = res.addSearchParam();
    sp.setName(p.name());
    sp.setType(SearchParamType.fromCode(p.type().code()));
    sp.setDocumentation(p.documentation());
    if (p.name().startsWith("_")) {
      sp.setDefinition("http://hl7.org/fhir/SearchParameter/Resource-" + p.name().substring(1));
    }
  }
}
