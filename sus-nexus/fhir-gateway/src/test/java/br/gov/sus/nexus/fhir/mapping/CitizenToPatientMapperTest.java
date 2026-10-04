package br.gov.sus.nexus.fhir.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.gov.sus.nexus.fhir.FhirConstants;
import br.gov.sus.nexus.fhir.FhirTestSupport;
import br.gov.sus.nexus.fhir.validation.FhirValidator;
import br.gov.sus.nexus.fhir.validation.ValidationIssue;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import org.hl7.fhir.r4.model.ContactPoint;
import org.hl7.fhir.r4.model.HumanName;
import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.Patient;
import org.junit.jupiter.api.Test;

@QuarkusTest
class CitizenToPatientMapperTest {

  @Inject CitizenToPatientMapper mapper;
  @Inject HealthUnitToOrganizationMapper orgMapper;
  @Inject ObjectMapper json;
  @Inject FhirValidator validator;

  @Test
  void mapsClearCanonicalToValidPatient() throws Exception {
    CanonicalCitizen c =
        json.readValue(FhirTestSupport.fixture("canonical-citizen.json"), CanonicalCitizen.class);
    Patient p = mapper.map(c);

    assertThat(p.getId()).isEqualTo("01HZX4Y5K6M7N8P9Q0R1S2T3U4");
    assertThat(p.getMeta().getProfile().get(0).getValue())
        .isEqualTo(FhirTestSupport.PROFILE_PATIENT);
    assertThat(p.getIdentifier()).hasSize(3);
    assertThat(
            p.getIdentifier().stream()
                .noneMatch(i -> i.hasExtension(FhirConstants.EXT_MASKED_IDENTIFIER)))
        .isTrue();
    HumanName name = p.getNameFirstRep();
    assertThat(name.getUse()).isEqualTo(HumanName.NameUse.OFFICIAL);
    assertThat(name.getGiven().stream().map(g -> g.getValue()).toList())
        .containsExactly("Ana", "Paula");
    assertThat(name.getFamily()).isEqualTo("Ferreira");
    assertThat(p.getBirthDateElement().getValueAsString()).isEqualTo("1992-11-03");
    assertThat(p.getTelecom()).hasSize(2);
    ContactPoint mobile = p.getTelecom().get(0);
    assertThat(mobile.getUse()).isEqualTo(ContactPoint.ContactPointUse.MOBILE);
    assertThat(mobile.getRank()).isEqualTo(1);
    assertThat(
            p.getAddressFirstRep()
                .getExtensionByUrl(FhirConstants.EXT_CITY_IBGE)
                .getValue()
                .primitiveValue())
        .isEqualTo("3143302");
    assertThat(p.getManagingOrganization().getIdentifier().getValue()).isEqualTo("2112345");

    List<ValidationIssue> issues = validator.validate(p, "Patient");
    assertThat(issues.stream().filter(ValidationIssue::isError)).isEmpty();
  }

  @Test
  void mapsMaskedCanonical() throws Exception {
    CanonicalCitizen c =
        json.readValue(
            FhirTestSupport.fixture("canonical-citizen-masked.json"), CanonicalCitizen.class);
    Patient p = mapper.map(c);
    var cns =
        p.getIdentifier().stream()
            .filter(i -> FhirConstants.SYSTEM_CNS.equals(i.getSystem()))
            .findFirst()
            .orElseThrow();
    assertThat(cns.getValue()).isEqualTo("701********0002");
    assertThat(
            cns.getExtensionByUrl(FhirConstants.EXT_MASKED_IDENTIFIER).getValue().primitiveValue())
        .isEqualTo("true");
    assertThat(p.hasExtension(FhirConstants.EXT_MASKED_MOTHERS_NAME)).isTrue();
    assertThat(p.getGender().toCode()).isEqualTo("male");
    assertThat(validator.validate(p, "Patient").stream().filter(ValidationIssue::isError))
        .isEmpty();
  }

  @Test
  void mapsHealthUnit() throws Exception {
    CanonicalHealthUnit u =
        json.readValue(
            FhirTestSupport.fixture("canonical-health-unit.json"), CanonicalHealthUnit.class);
    Organization o = orgMapper.map(u);
    assertThat(o.getId()).isEqualTo("01HZX4Y5K6M7N8P9Q0R1S2T3W1");
    assertThat(o.getName()).isEqualTo("UBS Major Prates");
    assertThat(o.getTypeFirstRep().getCodingFirstRep().getCode()).isEqualTo("02");
    assertThat(validator.validate(o, "Organization").stream().filter(ValidationIssue::isError))
        .isEmpty();
  }

  @Test
  void canonicalIdConversion() {
    assertThat(CanonicalIds.toFhirId("cit_01HZX4")).isEqualTo("01HZX4");
    assertThat(CanonicalIds.toFhirId("01HZX4")).isEqualTo("01HZX4");
    assertThatThrownBy(() -> CanonicalIds.toFhirId("cit_bad id"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(CitizenToPatientMapper.identifierSystem("cpf")).isEqualTo(FhirConstants.SYSTEM_CPF);
    assertThat(CitizenToPatientMapper.identifierSystem("XYZ")).isNull();
  }
}
