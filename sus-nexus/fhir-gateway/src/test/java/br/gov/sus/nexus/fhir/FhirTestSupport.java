package br.gov.sus.nexus.fhir;

import io.restassured.RestAssured;
import io.restassured.specification.RequestSpecification;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ThreadLocalRandom;

/** Utilitários de teste: identidade fake por headers, fixtures e geração de identificadores. */
public final class FhirTestSupport {

  private FhirTestSupport() {}

  public static final String TENANT_A = "ibge_3143302";
  public static final String TENANT_B = "ibge_3106200";
  public static final String FHIR = "/fhir/r4";
  public static final String PROFILE_PATIENT =
      "https://br-core.saude.gov.br/fhir/StructureDefinition/BRCorePatient";

  public static RequestSpecification as(String user, String scopes, String tenant) {
    return RestAssured.given()
        .header(FhirConstants.HEADER_TEST_USER, user)
        .header(FhirConstants.HEADER_TEST_SCOPES, scopes)
        .header(FhirConstants.HEADER_TENANT, tenant)
        // REST-assured codifica corpos em ISO-8859-1 por padrão; declaramos UTF-8 explicitamente
        .contentType(FhirConstants.MEDIA_TYPE_FHIR_JSON_UTF8)
        .accept(FhirConstants.MEDIA_TYPE_FHIR_JSON);
  }

  /** Usuário clínico com leitura/escrita completa no tenant A. */
  public static RequestSpecification clinician() {
    return as("user-clinician", "user/*.read user/*.write", TENANT_A);
  }

  public static String fixture(String name) {
    try (InputStream in = FhirTestSupport.class.getResourceAsStream("/fixtures/" + name)) {
      if (in == null) {
        throw new IllegalArgumentException("Fixture não encontrada: " + name);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  /** CNS sintético único (15 dígitos). */
  public static String randomCns() {
    return "7" + String.format("%014d", ThreadLocalRandom.current().nextLong(100_000_000_000_000L));
  }

  /** Patient conforme fixture com CNS único e nome parametrizado. */
  public static String patientJson(String cns, String family, String given, String birthDate) {
    return fixture("patient-brcore.json")
        .replace("898001234567890", cns)
        .replace("\"family\": \"Silva\"", "\"family\": \"" + family + "\"")
        .replace("\"given\": [\"Maria\", \"José\"]", "\"given\": [\"" + given + "\"]")
        .replace("\"text\": \"Maria José da Silva\"", "\"text\": \"" + given + " " + family + "\"")
        .replace("1980-05-12", birthDate);
  }
}
