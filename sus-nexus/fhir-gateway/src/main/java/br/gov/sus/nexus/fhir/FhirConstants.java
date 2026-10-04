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

  // FHIR-3: identificadores, CodeSystems e extensões de resultados, hospital e cuidado
  public static final String SYSTEM_MUNICIPAL_EXAM_RESULT_ID =
      SUS_NEXUS_BASE + "/NamingSystem/municipal-exam-result-id";
  public static final String SYSTEM_MUNICIPAL_HOSPITAL_EPISODE_ID =
      SUS_NEXUS_BASE + "/NamingSystem/municipal-hospital-episode-id";
  public static final String SYSTEM_MUNICIPAL_CARE_PLAN_ID =
      SUS_NEXUS_BASE + "/NamingSystem/municipal-care-plan-id";
  public static final String SYSTEM_MUNICIPAL_CARE_GAP_ID =
      SUS_NEXUS_BASE + "/NamingSystem/municipal-care-gap-id";
  public static final String SYSTEM_AIH = "http://www.saude.gov.br/fhir/r4/NamingSystem/aih";
  public static final String CS_CARE_LINE = SUS_NEXUS_BASE + "/CodeSystem/care-line";
  public static final String CS_CARE_GAP_KIND = SUS_NEXUS_BASE + "/CodeSystem/care-gap-kind";
  public static final String CS_CARE_PLAN_ITEM_KIND =
      SUS_NEXUS_BASE + "/CodeSystem/care-plan-item-kind";
  public static final String CS_V2_0074 = "http://terminology.hl7.org/CodeSystem/v2-0074";
  public static final String CS_V3_OBSERVATION_INTERPRETATION =
      "http://terminology.hl7.org/CodeSystem/v3-ObservationInterpretation";
  public static final String CS_OBSERVATION_CATEGORY =
      "http://terminology.hl7.org/CodeSystem/observation-category";
  public static final String CS_DISCHARGE_DISPOSITION =
      "http://terminology.hl7.org/CodeSystem/discharge-disposition";
  public static final String CS_ADMIT_SOURCE = "http://terminology.hl7.org/CodeSystem/admit-source";
  public static final String CS_LOINC = "http://loinc.org";
  public static final String LOINC_LAB_REPORT = "11502-2";
  public static final String LOINC_IMAGING_REPORT = "18748-4";
  public static final String EXT_EXAM_RESULT_STATUS =
      SUS_NEXUS_BASE + "/StructureDefinition/exam-result-status";
  public static final String EXT_EXAM_RESULT_CRITICAL =
      SUS_NEXUS_BASE + "/StructureDefinition/exam-result-critical";
  public static final String EXT_DOCUMENT_REF =
      SUS_NEXUS_BASE + "/StructureDefinition/document-ref";
  public static final String EXT_DOCUMENT_SHA256 =
      SUS_NEXUS_BASE + "/StructureDefinition/document-sha256";
  public static final String EXT_HOSPITAL_EPISODE_STATUS =
      SUS_NEXUS_BASE + "/StructureDefinition/hospital-episode-status";
  public static final String EXT_HOSPITAL_WARD = SUS_NEXUS_BASE + "/StructureDefinition/ward";
  public static final String EXT_HOSPITAL_BED = SUS_NEXUS_BASE + "/StructureDefinition/bed";
  public static final String EXT_RISK_LEVEL = SUS_NEXUS_BASE + "/StructureDefinition/risk-level";
  public static final String EXT_READMISSION_30D =
      SUS_NEXUS_BASE + "/StructureDefinition/readmission-within-30d";
  public static final String EXT_LENGTH_OF_STAY =
      SUS_NEXUS_BASE + "/StructureDefinition/length-of-stay-days";
  public static final String EXT_ADMISSION_SOURCE =
      SUS_NEXUS_BASE + "/StructureDefinition/admission-source";
  public static final String EXT_PROTOCOL_ID =
      SUS_NEXUS_BASE + "/StructureDefinition/protocol-id";
  public static final String EXT_PROTOCOL_VERSION =
      SUS_NEXUS_BASE + "/StructureDefinition/protocol-version";
  public static final String EXT_CARE_PLAN_STATUS =
      SUS_NEXUS_BASE + "/StructureDefinition/care-plan-status";
  public static final String EXT_CARE_PLAN_ITEM_STATUS =
      SUS_NEXUS_BASE + "/StructureDefinition/care-plan-item-status";
  public static final String EXT_CARE_PLAN_ITEM_ID =
      SUS_NEXUS_BASE + "/StructureDefinition/care-plan-item-id";
  public static final String EXT_ITEM_OVERDUE =
      SUS_NEXUS_BASE + "/StructureDefinition/item-overdue";
  public static final String EXT_OPEN_GAPS = SUS_NEXUS_BASE + "/StructureDefinition/open-gaps";
  public static final String EXT_DAYS_OVERDUE =
      SUS_NEXUS_BASE + "/StructureDefinition/days-overdue";
  public static final String EXT_GAP_RESOLUTION =
      SUS_NEXUS_BASE + "/StructureDefinition/gap-resolution";
  public static final String EXT_CONTACT_VALID =
      SUS_NEXUS_BASE + "/StructureDefinition/contact-valid";
  public static final String EXT_IDENTITY_RESOLUTION =
      SUS_NEXUS_BASE + "/StructureDefinition/identity-resolution";
  public static final String EXT_BINARY_SHA256 =
      SUS_NEXUS_BASE + "/StructureDefinition/binary-sha256";
  public static final String EXT_BINARY_SIZE = SUS_NEXUS_BASE + "/StructureDefinition/binary-size";
  public static final String CS_MUNICIPAL_TAG = SUS_NEXUS_BASE + "/CodeSystem/resource-tag";
  public static final String TAG_PENDING_IDENTITY = "pending-identity";
  public static final String TAG_DIRECT_WRITE = "direct-write";
  public static final String TASK_TYPE_CARE_GAP = "care_gap";
  public static final String MEDIA_TYPE_JSON_PATCH = "application/json-patch+json";

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
