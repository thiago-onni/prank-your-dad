package br.gov.sus.nexus.fhir.mapping;

import br.gov.sus.nexus.fhir.FhirConstants;
import br.gov.sus.nexus.fhir.config.FhirGatewayConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.hl7.fhir.r4.model.Address;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Identifier.IdentifierUse;
import org.hl7.fhir.r4.model.Organization;

/** Canônico {@code HealthUnit} → {@code Organization} (CNES como identifier oficial). */
@ApplicationScoped
public class HealthUnitToOrganizationMapper {

  @Inject FhirGatewayConfig config;

  public Organization map(CanonicalHealthUnit u) {
    Organization org = new Organization();
    org.setId(CanonicalIds.toFhirId(u.id()));
    org.getMeta().addProfile(config.profiles().organization());
    org.setActive(u.active() == null || u.active());
    org.addIdentifier(
        new Identifier()
            .setUse(IdentifierUse.OFFICIAL)
            .setSystem(FhirConstants.SYSTEM_MUNICIPAL_HEALTH_UNIT_ID)
            .setValue(u.id()));
    if (u.cnes() != null && !u.cnes().isBlank()) {
      org.addIdentifier(
          new Identifier()
              .setUse(IdentifierUse.OFFICIAL)
              .setSystem(FhirConstants.SYSTEM_CNES)
              .setValue(u.cnes()));
    }
    org.setName(u.name());
    if (u.kindCode() != null && !u.kindCode().isBlank()) {
      CodeableConcept type = new CodeableConcept();
      type.addCoding(
          new Coding()
              .setSystem(FhirConstants.SYSTEM_CNES_UNIT_KIND)
              .setCode(u.kindCode())
              .setDisplay(u.kindDescription()));
      type.setText(u.kindDescription());
      org.addType(type);
    }
    if (u.address() != null && !u.address().isBlank()) {
      org.addAddress(new Address().setText(u.address()).setCountry("BR"));
    }
    return org;
  }
}
