package br.gov.sus.nexus.fhir.mapping;

import br.gov.sus.nexus.fhir.FhirConstants;
import br.gov.sus.nexus.fhir.config.FhirGatewayConfig;
import br.gov.sus.nexus.fhir.mapping.CanonicalCitizen.CanonicalAddress;
import br.gov.sus.nexus.fhir.mapping.CanonicalCitizen.CanonicalContact;
import br.gov.sus.nexus.fhir.mapping.CanonicalCitizen.CanonicalIdentifier;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Locale;
import org.hl7.fhir.r4.model.Address;
import org.hl7.fhir.r4.model.BooleanType;
import org.hl7.fhir.r4.model.ContactPoint;
import org.hl7.fhir.r4.model.ContactPoint.ContactPointSystem;
import org.hl7.fhir.r4.model.ContactPoint.ContactPointUse;
import org.hl7.fhir.r4.model.DateType;
import org.hl7.fhir.r4.model.Enumerations.AdministrativeGender;
import org.hl7.fhir.r4.model.HumanName;
import org.hl7.fhir.r4.model.HumanName.NameUse;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Identifier.IdentifierUse;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.StringType;

/**
 * Canônico municipal → {@code Patient} (perfil BR configurado). Identificadores apenas mascarados
 * recebem a extensão {@code masked-identifier=true}; o id municipal é mantido como identifier
 * próprio; mãe via extensão {@code patient-mothersMaidenName}; município IBGE via extensão.
 */
@ApplicationScoped
public class CitizenToPatientMapper {

  @Inject FhirGatewayConfig config;

  public Patient map(CanonicalCitizen c) {
    Patient p = new Patient();
    p.setId(CanonicalIds.toFhirId(c.id()));
    p.getMeta().addProfile(config.profiles().patient());
    p.setActive(!"duplicate".equals(c.registrationState()));

    Identifier municipal = p.addIdentifier();
    municipal.setUse(IdentifierUse.OFFICIAL);
    municipal.setSystem(FhirConstants.SYSTEM_MUNICIPAL_CITIZEN_ID);
    municipal.setValue(c.id());

    if (c.identifiers() != null) {
      for (CanonicalIdentifier ci : c.identifiers()) {
        mapIdentifier(ci).ifPresent(p::addIdentifier);
      }
    }

    String legal = firstNonBlank(c.legalName(), c.displayName());
    if (legal != null) {
      p.addName(humanName(legal, NameUse.OFFICIAL));
    }
    if (c.socialName() != null && !c.socialName().isBlank() && !c.socialName().equals(legal)) {
      p.addName(humanName(c.socialName(), NameUse.USUAL));
    }

    if (c.sex() != null) {
      p.setGender(
          switch (c.sex().toLowerCase(Locale.ROOT)) {
            case "female" -> AdministrativeGender.FEMALE;
            case "male" -> AdministrativeGender.MALE;
            default -> AdministrativeGender.UNKNOWN;
          });
    }
    if (c.birthdate() != null && !c.birthdate().isBlank()) {
      p.setBirthDateElement(new DateType(c.birthdate()));
    }
    if (Boolean.TRUE.equals(c.deceased())) {
      p.setDeceased(new BooleanType(true));
    }
    String mother = firstNonBlank(c.motherName(), c.motherNameMasked());
    if (mother != null) {
      // ext-1: uma extensão com value[x] não pode ter sub-extensões; o valor mascarado é
      // reconhecível pelos asteriscos e a extensão própria abaixo marca o mascaramento.
      p.addExtension().setUrl(FhirConstants.EXT_MOTHERS_NAME).setValue(new StringType(mother));
      if (c.motherName() == null || c.motherName().isBlank()) {
        p.addExtension()
            .setUrl(FhirConstants.EXT_MASKED_MOTHERS_NAME)
            .setValue(new BooleanType(true));
      }
    }
    if (c.address() != null) {
      p.addAddress(mapAddress(c.address()));
    }
    if (c.contacts() != null) {
      for (CanonicalContact contact : c.contacts()) {
        mapContact(contact).ifPresent(p::addTelecom);
      }
    }
    if (c.healthUnitCnes() != null && !c.healthUnitCnes().isBlank()) {
      p.setManagingOrganization(
          new Reference()
              .setIdentifier(
                  new Identifier()
                      .setSystem(FhirConstants.SYSTEM_CNES)
                      .setValue(c.healthUnitCnes()))
              .setDisplay("CNES " + c.healthUnitCnes()));
    }
    return p;
  }

  private static java.util.Optional<Identifier> mapIdentifier(CanonicalIdentifier ci) {
    String system = identifierSystem(ci.system());
    if (system == null) {
      return java.util.Optional.empty();
    }
    boolean clear = ci.value() != null && !ci.value().isBlank();
    String value = clear ? ci.value() : ci.valueMasked();
    if (value == null || value.isBlank()) {
      return java.util.Optional.empty();
    }
    Identifier id = new Identifier().setSystem(system).setValue(value);
    id.setUse("deprecated".equals(ci.status()) ? IdentifierUse.OLD : IdentifierUse.OFFICIAL);
    if (!clear) {
      id.addExtension(FhirConstants.EXT_MASKED_IDENTIFIER, new BooleanType(true));
    }
    return java.util.Optional.of(id);
  }

  /** Mapeia {@code IdentifierSystem} do core para o system FHIR. */
  public static String identifierSystem(String canonicalSystem) {
    if (canonicalSystem == null) {
      return null;
    }
    return switch (canonicalSystem.toUpperCase(Locale.ROOT)) {
      case "CNS" -> FhirConstants.SYSTEM_CNS;
      case "CPF" -> FhirConstants.SYSTEM_CPF;
      case "PEC", "SISREG", "ESUS_REGULACAO", "HIS", "AIH", "APAC", "LOCAL" ->
          FhirConstants.SUS_NEXUS_BASE
              + "/NamingSystem/source-"
              + canonicalSystem.toLowerCase(Locale.ROOT).replace('_', '-');
      default -> null;
    };
  }

  private static HumanName humanName(String full, NameUse use) {
    HumanName name = new HumanName().setUse(use).setText(full);
    String[] parts = full.trim().split("\\s+");
    if (parts.length > 1) {
      name.setFamily(parts[parts.length - 1]);
      for (int i = 0; i < parts.length - 1; i++) {
        name.addGiven(parts[i]);
      }
    } else {
      name.addGiven(parts[0]);
    }
    return name;
  }

  private static Address mapAddress(CanonicalAddress a) {
    Address address = new Address().setUse(Address.AddressUse.HOME);
    StringBuilder line = new StringBuilder();
    if (a.street() != null) {
      line.append(a.street());
    }
    if (a.number() != null) {
      line.append(line.isEmpty() ? "" : ", ").append(a.number());
    }
    if (!line.isEmpty()) {
      address.addLine(line.toString());
    }
    if (a.complement() != null && !a.complement().isBlank()) {
      address.addLine(a.complement());
    }
    if (a.district() != null) {
      address.setDistrict(a.district());
    }
    if (a.postalCode() != null) {
      address.setPostalCode(a.postalCode());
    }
    if (a.cityIbge() != null) {
      address.addExtension(FhirConstants.EXT_CITY_IBGE, new StringType(a.cityIbge()));
    }
    address.setCountry("BR");
    return address;
  }

  private static java.util.Optional<ContactPoint> mapContact(CanonicalContact c) {
    boolean clear = c.value() != null && !c.value().isBlank();
    String value = clear ? c.value() : c.valueMasked();
    if (value == null || value.isBlank() || c.kind() == null) {
      return java.util.Optional.empty();
    }
    ContactPoint cp = new ContactPoint().setValue(value);
    switch (c.kind().toLowerCase(Locale.ROOT)) {
      case "email" -> cp.setSystem(ContactPointSystem.EMAIL);
      case "mobile" -> cp.setSystem(ContactPointSystem.PHONE).setUse(ContactPointUse.MOBILE);
      default -> cp.setSystem(ContactPointSystem.PHONE);
    }
    if (Boolean.TRUE.equals(c.preferred())) {
      cp.setRank(1);
    }
    if (!clear) {
      cp.addExtension(FhirConstants.EXT_MASKED_IDENTIFIER, new BooleanType(true));
    }
    return java.util.Optional.of(cp);
  }

  private static String firstNonBlank(String a, String b) {
    if (a != null && !a.isBlank()) {
      return a;
    }
    return b != null && !b.isBlank() ? b : null;
  }
}
