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

  // FHIR-2: identificadores municipais de recursos projetados
  public static final String SYSTEM_MUNICIPAL_APPOINTMENT_ID =
      SUS_NEXUS_BASE + "/NamingSystem/municipal-appointment-id";
  public static final String SYSTEM_MUNICIPAL_TASK_ID =
      SUS_NEXUS_BASE + "/NamingSystem/municipal-task-id";
  public static final String SYSTEM_MUNICIPAL_REGULATION_REQUEST_ID =
      SUS_NEXUS_BASE + "/NamingSystem/municipal-regulation-request-id";
  public static final String SYSTEM_MUNICIPAL_EXAM_ORDER_ID =
      SUS_NEXUS_BASE + "/NamingSystem/municipal-exam-order-id";
  public static final String SYSTEM_MUNICIPAL_ENCOUNTER_ID =
      SUS_NEXUS_BASE + "/NamingSystem/municipal-encounter-id";
  public static final String SYSTEM_MUNICIPAL_PROFESSIONAL_ID =
      SUS_NEXUS_BASE + "/NamingSystem/municipal-professional-id";
  public static final String SYSTEM_MUNICIPAL_USER_ID =
      SUS_NEXUS_BASE + "/NamingSystem/municipal-user-id";
  public static final String SYSTEM_MUNICIPAL_TEAM_ID =
      SUS_NEXUS_BASE + "/NamingSystem/municipal-team-id";
  public static final String SYSTEM_MUNICIPAL_QUEUE_ID =
      SUS_NEXUS_BASE + "/NamingSystem/municipal-queue-id";
  public static final String SYSTEM_SOURCE_RECORD_ID =
      SUS_NEXUS_BASE + "/NamingSystem/source-record-id";
  public static final String SYSTEM_SUBJECT = SUS_NEXUS_BASE + "/NamingSystem/subject";

  // FHIR-2: CodeSystems municipais
  public static final String CS_TASK_TYPE = "https://sus-nexus.gov.br/fhir/CodeSystem/task-type";
  public static final String CS_TASK_BUSINESS_STATUS =
      SUS_NEXUS_BASE + "/CodeSystem/task-business-status";
  public static final String CS_REGULATION_KIND = SUS_NEXUS_BASE + "/CodeSystem/regulation-kind";
  public static final String CS_EXAM_CATEGORY = SUS_NEXUS_BASE + "/CodeSystem/exam-category";
  public static final String CS_ENCOUNTER_CLASS = SUS_NEXUS_BASE + "/CodeSystem/encounter-class";
  public static final String CS_SENSITIVITY = SUS_NEXUS_BASE + "/CodeSystem/sensitivity";
  public static final String CS_ORIGIN_KIND = SUS_NEXUS_BASE + "/CodeSystem/origin-kind";
  public static final String CS_SNOMED = "http://snomed.info/sct";
  public static final String CS_ICD10 = "http://hl7.org/fhir/sid/icd-10";
  public static final String CS_ICPC2 = "http://hl7.org/fhir/sid/icpc-2";
  public static final String CS_V3_ACT_CODE = "http://terminology.hl7.org/CodeSystem/v3-ActCode";
  public static final String CS_V3_CONFIDENTIALITY =
      "http://terminology.hl7.org/CodeSystem/v3-Confidentiality";

  // FHIR-2: extensões municipais
  public static final String EXT_APPOINTMENT_KIND =
      SUS_NEXUS_BASE + "/StructureDefinition/appointment-kind";
  public static final String EXT_APPOINTMENT_STATUS =
      SUS_NEXUS_BASE + "/StructureDefinition/appointment-status";
  public static final String EXT_CARE_LINE = SUS_NEXUS_BASE + "/StructureDefinition/care-line";
  public static final String EXT_TASK_STATUS = SUS_NEXUS_BASE + "/StructureDefinition/task-status";
  public static final String EXT_TASK_PRIORITY =
      SUS_NEXUS_BASE + "/StructureDefinition/task-priority";
  public static final String EXT_TASK_OUTCOME =
      SUS_NEXUS_BASE + "/StructureDefinition/task-outcome";
  public static final String EXT_TASK_OVERDUE =
      SUS_NEXUS_BASE + "/StructureDefinition/task-overdue";
  public static final String EXT_SLA_POLICY_ID =
      SUS_NEXUS_BASE + "/StructureDefinition/sla-policy-id";
  public static final String EXT_SLA_BREACHED_AT =
      SUS_NEXUS_BASE + "/StructureDefinition/sla-breached-at";
  public static final String EXT_SLA_DUE_AT = SUS_NEXUS_BASE + "/StructureDefinition/sla-due-at";
  public static final String EXT_REGULATION_STATUS =
      SUS_NEXUS_BASE + "/StructureDefinition/regulation-status";
  public static final String EXT_REGULATION_AUTHORIZED =
      SUS_NEXUS_BASE + "/StructureDefinition/regulation-authorized";
  public static final String EXT_REGULATION_PRIORITY =
      SUS_NEXUS_BASE + "/StructureDefinition/regulation-priority";
  public static final String EXT_WAITING_DAYS =
      SUS_NEXUS_BASE + "/StructureDefinition/waiting-days";
  public static final String EXT_JUSTIFICATION_PRESENT =
      SUS_NEXUS_BASE + "/StructureDefinition/justification-present";
  public static final String EXT_SCHEDULED_APPOINTMENT =
      SUS_NEXUS_BASE + "/StructureDefinition/scheduled-appointment";
  public static final String EXT_EXAM_ORDER_STATUS =
      SUS_NEXUS_BASE + "/StructureDefinition/exam-order-status";
  public static final String EXT_EXAM_ISSUE = SUS_NEXUS_BASE + "/StructureDefinition/exam-issue";
  public static final String EXT_TEAM_INE = SUS_NEXUS_BASE + "/StructureDefinition/team-ine";
  public static final String EXT_PROFESSIONAL_CBO =
      SUS_NEXUS_BASE + "/StructureDefinition/professional-cbo";
  public static final String EXT_REFERRALS_COUNT =
      SUS_NEXUS_BASE + "/StructureDefinition/referrals-count";
  public static final String EXT_EXAM_ORDERS_COUNT =
      SUS_NEXUS_BASE + "/StructureDefinition/exam-orders-count";
  public static final String EXT_CORRELATION_ID =
      SUS_NEXUS_BASE + "/StructureDefinition/correlation-id";
  public static final String EXT_EVENT_ID = SUS_NEXUS_BASE + "/StructureDefinition/event-id";

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
