package br.gov.sus.nexus.fhir;

/** Constantes compartilhadas do gateway (sistemas de identificação, extensões, mídia). */
public final class FhirConstants {

  private FhirConstants() {}

  public static final String FHIR_VERSION = "4.0.1";
  public static final String MEDIA_TYPE_FHIR_JSON = "application/fhir+json";
  public static final String MEDIA_TYPE_FHIR_JSON_UTF8 = "application/fhir+json; charset=UTF-8";

  // Sistemas de identificação nacionais (RNDS)
  public static final String SYSTEM_CNS = "http://rnds.saude.gov.br/fhir/r4/NamingSystem/cns";
  public static final String SYSTEM_CPF = "http://rnds.saude.gov.br/fhir/r4/NamingSystem/cpf";
  public static final String SYSTEM_CNES = "http://rnds.saude.gov.br/fhir/r4/NamingSystem/cnes";
  public static final String SYSTEM_INE = "http://rnds.saude.gov.br/fhir/r4/NamingSystem/ine";

  // Sistemas próprios do SUS Nexus
  public static final String SUS_NEXUS_BASE = "http://sus-nexus.gov.br/fhir";
  public static final String SYSTEM_MUNICIPAL_CITIZEN_ID =
      SUS_NEXUS_BASE + "/NamingSystem/municipal-citizen-id";
  public static final String SYSTEM_MUNICIPAL_HEALTH_UNIT_ID =
      SUS_NEXUS_BASE + "/NamingSystem/municipal-health-unit-id";
  public static final String SYSTEM_SOURCE_LOCAL_ID = SUS_NEXUS_BASE + "/NamingSystem/local-id";
  public static final String SYSTEM_CNES_UNIT_KIND =
      SUS_NEXUS_BASE + "/CodeSystem/cnes-tipo-unidade";
  public static final String SYSTEM_PURPOSE_OF_USE = SUS_NEXUS_BASE + "/CodeSystem/purpose-of-use";
  public static final String EXT_MASKED_IDENTIFIER =
      SUS_NEXUS_BASE + "/StructureDefinition/masked-identifier";
  public static final String EXT_MASKED_MOTHERS_NAME =
      SUS_NEXUS_BASE + "/StructureDefinition/masked-mothers-name";
  public static final String EXT_CITY_IBGE = SUS_NEXUS_BASE + "/StructureDefinition/city-ibge";
  public static final String EXT_MOTHERS_NAME =
      "http://hl7.org/fhir/StructureDefinition/patient-mothersMaidenName";

  // Terminologia HL7 usada em AuditEvent/Provenance
  public static final String CS_AUDIT_EVENT_TYPE =
      "http://terminology.hl7.org/CodeSystem/audit-event-type";
  public static final String CS_RESTFUL_INTERACTION = "http://hl7.org/fhir/restful-interaction";
  public static final String CS_PARTICIPATION_TYPE =
      "http://terminology.hl7.org/CodeSystem/v3-ParticipationType";
  public static final String CS_PROVENANCE_PARTICIPANT_TYPE =
      "http://terminology.hl7.org/CodeSystem/provenance-participant-type";
  public static final String CS_DATA_OPERATION =
      "http://terminology.hl7.org/CodeSystem/v3-DataOperation";
  public static final String CS_OBJECT_ROLE = "http://terminology.hl7.org/CodeSystem/object-role";
  public static final String CS_AUDIT_SOURCE_TYPE =
      "http://terminology.hl7.org/CodeSystem/security-source-type";

  // Cabeçalhos
  public static final String HEADER_TENANT = "X-Tenant-Id";
  public static final String HEADER_PURPOSE_OF_USE = "X-Purpose-Of-Use";
  public static final String HEADER_CORRELATION_ID = "X-Correlation-Id";
  public static final String HEADER_TEST_USER = "X-Test-User";
  public static final String HEADER_TEST_SCOPES = "X-Test-Scopes";
  public static final String HEADER_TEST_PATIENT = "X-Test-Patient";

  public static final String FHIR_ID_PATTERN = "[A-Za-z0-9\\-\\.]{1,64}";
}
