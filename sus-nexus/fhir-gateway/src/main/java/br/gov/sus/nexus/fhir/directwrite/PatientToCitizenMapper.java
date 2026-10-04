package br.gov.sus.nexus.fhir.directwrite;

import br.gov.sus.nexus.fhir.FhirConstants;
import br.gov.sus.nexus.fhir.interaction.FhirException;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.hl7.fhir.r4.model.Address;
import org.hl7.fhir.r4.model.ContactPoint;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.HumanName;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.OperationOutcome.IssueType;
import org.hl7.fhir.r4.model.Patient;

/**
 * FHIR {@code Patient} (parceiro externo) → {@code CitizenRegistration} do core (seção 6.2 do
 * plano). Só identificadores reconhecidos (CNS/CPF e sistemas de origem conhecidos) são enviados;
 * nome legal = {@code name[use=official]} (ou o primeiro), nome social = {@code use=usual};
 * mãe pela extensão {@code patient-mothersMaidenName}; município IBGE pela extensão municipal.
 */
@ApplicationScoped
public class PatientToCitizenMapper {

  public CitizenRegistration map(Patient p, CitizenRegistration.Source source) {
    List<CitizenRegistration.Identifier> identifiers = new ArrayList<>();
    for (Identifier id : p.getIdentifier()) {
      String system = canonicalSystem(id.getSystem());
      if (system != null && id.hasValue()) {
        identifiers.add(new CitizenRegistration.Identifier(system, id.getValue()));
      }
    }
    HumanName legal =
        p.getName().stream()
            .filter(n -> n.getUse() == HumanName.NameUse.OFFICIAL)
            .findFirst()
            .orElseGet(() -> p.hasName() ? p.getNameFirstRep() : null);
    if (legal == null) {
      throw new FhirException(422, IssueType.REQUIRED, "Patient.name obrigatório", "Patient.name");
    }
    if (!p.hasBirthDate()) {
      throw new FhirException(
          422,
          IssueType.REQUIRED,
          "Patient.birthDate obrigatório para registro no core",
          "Patient.birthDate");
    }
    String social =
        p.getName().stream()
            .filter(n -> n.getUse() == HumanName.NameUse.USUAL && n != legal)
            .map(PatientToCitizenMapper::fullName)
            .findFirst()
            .orElse(null);
    String mother =
        p.getExtensionsByUrl(FhirConstants.EXT_MOTHERS_NAME).stream()
            .filter(Extension::hasValue)
            .map(e -> e.getValue().primitiveValue())
            .findFirst()
            .orElse(null);
    String sex =
        p.hasGender()
            ? switch (p.getGender()) {
              case MALE -> "male";
              case FEMALE -> "female";
              default -> "unknown";
            }
            : null;
    CitizenRegistration.Demographics demographics =
        new CitizenRegistration.Demographics(
            fullName(legal),
            social,
            mother,
            p.getBirthDateElement().getValueAsString(),
            sex,
            p.hasDeceasedBooleanType() && p.getDeceasedBooleanType().booleanValue() ? true : null,
            p.hasDeceasedDateTimeType()
                ? p.getDeceasedDateTimeType().getValueAsString().substring(0, 10)
                : null);

    CitizenRegistration.Address address = null;
    if (p.hasAddress()) {
      Address a = p.getAddressFirstRep();
      String line = a.hasLine() ? a.getLine().get(0).getValue() : null;
      String street = line;
      String number = null;
      if (line != null && line.contains(",")) {
        int comma = line.lastIndexOf(',');
        street = line.substring(0, comma).trim();
        number = line.substring(comma + 1).trim();
      }
      String complement = a.getLine().size() > 1 ? a.getLine().get(1).getValue() : null;
      String ibge =
          a.getExtensionsByUrl(FhirConstants.EXT_CITY_IBGE).stream()
              .filter(Extension::hasValue)
              .map(e -> e.getValue().primitiveValue())
              .findFirst()
              .orElse(null);
      address =
          new CitizenRegistration.Address(
              street, number, complement, a.getDistrict(), ibge, a.getPostalCode());
    }
    List<CitizenRegistration.Contact> contacts = new ArrayList<>();
    for (ContactPoint cp : p.getTelecom()) {
      if (!cp.hasValue() || !cp.hasSystem()) {
        continue;
      }
      switch (cp.getSystem()) {
        case EMAIL -> contacts.add(new CitizenRegistration.Contact("email", cp.getValue()));
        case PHONE, SMS ->
            contacts.add(
                new CitizenRegistration.Contact(
                    cp.getUse() == ContactPoint.ContactPointUse.MOBILE ? "mobile" : "phone",
                    cp.getValue()));
        default -> {
          // outros sistemas não têm equivalente canônico
        }
      }
    }
    CitizenRegistration.Territory territory = null;
    if (p.hasManagingOrganization()
        && p.getManagingOrganization().hasIdentifier()
        && FhirConstants.SYSTEM_CNES.equals(p.getManagingOrganization().getIdentifier().getSystem())) {
      territory =
          new CitizenRegistration.Territory(
              p.getManagingOrganization().getIdentifier().getValue(), null, null);
    }
    return new CitizenRegistration(
        source,
        identifiers,
        demographics,
        address,
        contacts.isEmpty() ? null : contacts,
        territory);
  }

  static String fullName(HumanName n) {
    if (n.hasText()) {
      return n.getText();
    }
    StringBuilder sb = new StringBuilder();
    for (var g : n.getGiven()) {
      sb.append(g.getValue()).append(' ');
    }
    if (n.hasFamily()) {
      sb.append(n.getFamily());
    }
    return sb.toString().trim();
  }

  /** Inverso de {@code CitizenToPatientMapper.identifierSystem}. */
  public static String canonicalSystem(String fhirSystem) {
    if (fhirSystem == null) {
      return null;
    }
    if (FhirConstants.SYSTEM_CNS.equals(fhirSystem)) {
      return "CNS";
    }
    if (FhirConstants.SYSTEM_CPF.equals(fhirSystem)) {
      return "CPF";
    }
    String prefix = FhirConstants.SUS_NEXUS_BASE + "/NamingSystem/source-";
    if (fhirSystem.startsWith(prefix)) {
      return fhirSystem.substring(prefix.length()).toUpperCase(Locale.ROOT).replace('-', '_');
    }
    return null;
  }
}
