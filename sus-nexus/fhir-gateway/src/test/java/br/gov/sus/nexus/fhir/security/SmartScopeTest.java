package br.gov.sus.nexus.fhir.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SmartScopeTest {

  @Test
  void parsesReadWriteAndWildcard() {
    SmartScope read = SmartScope.parse("user/*.read").orElseThrow();
    assertThat(read.grants("Patient", Permission.READ)).isTrue();
    assertThat(read.grants("Patient", Permission.SEARCH)).isTrue();
    assertThat(read.grants("Patient", Permission.CREATE)).isFalse();
    assertThat(read.granular()).isFalse();

    SmartScope write = SmartScope.parse("system/Patient.write").orElseThrow();
    assertThat(write.grants("Patient", Permission.CREATE)).isTrue();
    assertThat(write.grants("Organization", Permission.CREATE)).isFalse();
    assertThat(write.isSystemContext()).isTrue();

    SmartScope all = SmartScope.parse("patient/*.*").orElseThrow();
    assertThat(all.permissions()).containsExactlyInAnyOrder(Permission.values());
  }

  @Test
  void granularScopesAreRestricted() {
    SmartScope rs = SmartScope.parse("user/Patient.rs").orElseThrow();
    assertThat(rs.granular()).isTrue();
    assertThat(rs.permissions()).containsExactlyInAnyOrder(Permission.READ, Permission.SEARCH);
    Identity id = new Identity("u", "ibge_1", Set.of(rs), Optional.empty());
    assertThat(id.grants("Patient", Permission.READ)).isTrue();
    assertThat(id.hasFullRead("Patient")).isFalse();
  }

  @Test
  void rejectsMalformedScopes() {
    assertThat(SmartScope.parse("openid")).isEmpty();
    assertThat(SmartScope.parse("user/patient.read")).isEmpty();
    assertThat(SmartScope.parse("admin/*.read")).isEmpty();
    assertThat(SmartScope.parse("user/Patient.xyz")).isEmpty();
  }

  @Test
  void patientContextDetection() {
    Identity id =
        new Identity(
            "u",
            "ibge_1",
            Set.of(SmartScope.parse("patient/*.read").orElseThrow()),
            Optional.of("p1"));
    assertThat(id.onlyPatientContext("Patient", Permission.READ)).isTrue();
    Identity mixed =
        new Identity(
            "u",
            "ibge_1",
            Set.of(
                SmartScope.parse("patient/*.read").orElseThrow(),
                SmartScope.parse("user/Patient.read").orElseThrow()),
            Optional.of("p1"));
    assertThat(mixed.onlyPatientContext("Patient", Permission.READ)).isFalse();
  }
}
