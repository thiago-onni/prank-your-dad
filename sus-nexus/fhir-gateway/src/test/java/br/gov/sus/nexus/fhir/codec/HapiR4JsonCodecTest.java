package br.gov.sus.nexus.fhir.codec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.gov.sus.nexus.fhir.FhirTestSupport;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Resource;
import org.junit.jupiter.api.Test;

class HapiR4JsonCodecTest {

  private final HapiR4JsonCodec codec = new HapiR4JsonCodec();

  @Test
  void parsesValidPatientAndRoundTrips() {
    Resource r = codec.parse(FhirTestSupport.fixture("patient-brcore.json"));
    assertThat(r).isInstanceOf(Patient.class);
    Patient p = (Patient) r;
    assertThat(p.getIdentifier()).hasSize(2);
    assertThat(p.getBirthDateElement().getValueAsString()).isEqualTo("1980-05-12");

    String json = codec.encode(p);
    Patient again = (Patient) codec.parse(json);
    assertThat(codec.encode(again)).isEqualTo(json);
    assertThat(codec.encodePretty(again)).contains("\n");
  }

  @Test
  void rejectsUnknownProperty() {
    assertThatThrownBy(() -> codec.parse("{\"resourceType\":\"Patient\",\"foo\":1}"))
        .isInstanceOf(FhirParseException.class);
  }

  @Test
  void rejectsWrongCardinalityShape() {
    assertThatThrownBy(() -> codec.parse("{\"resourceType\":\"Patient\",\"gender\":[\"male\"]}"))
        .isInstanceOf(FhirParseException.class);
  }

  @Test
  void rejectsMalformedJsonAndEmptyBody() {
    assertThatThrownBy(() -> codec.parse("{not json")).isInstanceOf(FhirParseException.class);
    assertThatThrownBy(() -> codec.parse("   ")).isInstanceOf(FhirParseException.class);
  }

  @Test
  void rejectsInvalidPrimitiveType() {
    assertThatThrownBy(() -> codec.parse("{\"resourceType\":\"Patient\",\"active\":\"yes\"}"))
        .isInstanceOf(FhirParseException.class);
  }
}
