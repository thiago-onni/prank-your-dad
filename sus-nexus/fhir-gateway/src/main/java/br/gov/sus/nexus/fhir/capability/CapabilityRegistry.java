package br.gov.sus.nexus.fhir.capability;

import br.gov.sus.nexus.fhir.config.FhirGatewayConfig;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Registro único de capacidades do gateway. Tudo o que o servidor expõe (tipos, interações,
 * parâmetros de busca, operações) está registrado aqui em código; o {@code CapabilityStatement} é
 * gerado a partir deste registro e o roteador de interações o consulta antes de executar qualquer
 * requisição. Assim é impossível anunciar o que não existe ou executar o que não foi anunciado.
 *
 * <p>Para adicionar um recurso: registre-o aqui (interações + parâmetros com expressão FHIRPath),
 * crie o mapper canônico (se for projeção) e, se necessário, invariantes municipais em {@code
 * MunicipalInvariants}.
 */
@ApplicationScoped
public class CapabilityRegistry {

  /** Parâmetros comuns a todo recurso, computados sobre a tabela de recursos. */
  public static final SearchParamDef PARAM_ID =
      new SearchParamDef("_id", SearchParamType.TOKEN, null, "Id lógico do recurso", List.of());

  public static final SearchParamDef PARAM_LAST_UPDATED =
      new SearchParamDef(
          "_lastUpdated",
          SearchParamType.DATE,
          null,
          "Data da última atualização (meta.lastUpdated)",
          List.of());

  /** Operações de sistema implementadas (nome sem {@code $}). */
  public static final List<String> SYSTEM_OPERATIONS = List.of("validate");

  /** Interações de nível de sistema implementadas ({@code POST [base]}, {@code GET _history}). */
  public static final List<String> SYSTEM_INTERACTIONS =
      List.of("transaction", "batch", "history-system");

  /**
   * Tipos clínicos incluídos por padrão em {@code Patient/$everything} (além do próprio Patient).
   * {@code Binary} fica fora por padrão (conteúdo potencialmente grande) e só entra com {@code
   * _type=Binary}.
   */
  public static final List<String> EVERYTHING_DEFAULT_TYPES =
      List.of(
          "Encounter",
          "Appointment",
          "ServiceRequest",
          "Task",
          "Condition",
          "CarePlan",
          "Observation",
          "DiagnosticReport",
          "DocumentReference");

  /** Tipos cuja leitura/escrita exige escopo explícito do tipo (nunca {@code *}). */
  public static final List<String> EXPLICIT_SCOPE_TYPES = List.of("DocumentReference", "Binary");

  /** Nome do parâmetro de referência ao paciente usado pelo compartimento {@code patient/}. */
  public static final String PATIENT_PARAM = "patient";

  /** Tipos projetados do core (alvos de Provenance/AuditEvent). */
  private static final String[] PROJECTED_TYPES = {
    "Patient",
    "Organization",
    "Location",
    "Practitioner",
    "PractitionerRole",
    "Encounter",
    "Appointment",
    "ServiceRequest",
    "Task",
    "Condition",
    "CarePlan",
    "Observation",
    "DiagnosticReport",
    "DocumentReference",
    "Binary"
  };

  private static final String PATIENT_ACTOR =
      "Appointment.participant.actor.where(reference.startsWith('Patient/'))";

  @Inject FhirGatewayConfig config;

  private final Map<String, ResourceCapability> resources = new LinkedHashMap<>();

  @PostConstruct
  void init() {
    FhirGatewayConfig.Profiles profiles = config.profiles();

    register(
        ResourceCapability.of("Patient")
            .readWrite()
            .profile(profiles.patient())
            .param(
                SearchParamDef.token(
                    "identifier", "Patient.identifier", "Identificador (CNS, CPF, id municipal)"))
            .param(SearchParamDef.string("name", "Patient.name", "Qualquer parte do nome"))
            .param(SearchParamDef.date("birthdate", "Patient.birthDate", "Data de nascimento"))
            .operation("everything")
            .build());

    register(
        ResourceCapability.of("Organization")
            .readWrite()
            .profile(profiles.organization())
            .param(
                SearchParamDef.token(
                    "identifier", "Organization.identifier", "Identificador (CNES, CNPJ)"))
            .param(SearchParamDef.string("name", "Organization.name", "Nome da organização"))
            .build());

    register(
        ResourceCapability.of("Location")
            .readWrite()
            .profile(profiles.location())
            .param(SearchParamDef.token("identifier", "Location.identifier", "Identificador"))
            .param(SearchParamDef.string("name", "Location.name", "Nome do local"))
            .param(
                SearchParamDef.reference(
                    "organization",
                    "Location.managingOrganization",
                    "Organização responsável",
                    "Organization"))
            .build());

    register(
        ResourceCapability.of("Practitioner")
            .readWrite()
            .profile(profiles.practitioner())
            .param(
                SearchParamDef.token(
                    "identifier", "Practitioner.identifier", "Identificador (CNS, CPF, conselho)"))
            .param(SearchParamDef.string("name", "Practitioner.name", "Nome do profissional"))
            .build());

    register(
        ResourceCapability.of("PractitionerRole")
            .readWrite()
            .profile(profiles.practitionerRole())
            .param(
                SearchParamDef.reference(
                    "practitioner",
                    "PractitionerRole.practitioner",
                    "Profissional",
                    "Practitioner"))
            .param(
                SearchParamDef.reference(
                    "organization", "PractitionerRole.organization", "Organização", "Organization"))
            .param(SearchParamDef.token("role", "PractitionerRole.code", "Papel (CBO)"))
            .build());

    // ---- FHIR-2: recursos clínicos/operacionais projetados do core ------------------------
    register(
        ResourceCapability.of("Encounter")
            .readWrite()
            .profile(profiles.encounter())
            .param(
                SearchParamDef.reference(
                    "patient", "Encounter.subject", "Paciente (Encounter.subject)", "Patient"))
            .param(SearchParamDef.date("date", "Encounter.period", "Período do atendimento"))
            .param(SearchParamDef.token("status", "Encounter.status", "Situação"))
            .param(SearchParamDef.token("class", "Encounter.class", "Classe (AMB/EMER/IMP/HH)"))
            .param(
                SearchParamDef.reference(
                    "service-provider",
                    "Encounter.serviceProvider",
                    "Unidade prestadora",
                    "Organization"))
            .include("patient")
            .build());

    register(
        ResourceCapability.of("Appointment")
            .readWrite()
            .profile(profiles.appointment())
            .param(
                SearchParamDef.reference(
                    "patient",
                    PATIENT_ACTOR,
                    "Paciente participante (Appointment.participant.actor)",
                    "Patient"))
            .param(SearchParamDef.date("date", "Appointment.start", "Início do agendamento"))
            .param(SearchParamDef.token("status", "Appointment.status", "Situação"))
            .param(
                SearchParamDef.token(
                    "service-type", "Appointment.serviceType", "Serviço (SIGTAP ou local)"))
            .param(
                SearchParamDef.reference(
                    "location",
                    "Appointment.participant.actor.where(reference.startsWith('Location/'))",
                    "Local participante",
                    "Location"))
            .param(
                SearchParamDef.reference(
                    "actor",
                    "Appointment.participant.actor",
                    "Qualquer participante",
                    "Patient",
                    "Practitioner",
                    "PractitionerRole",
                    "Location",
                    "Organization"))
            .include("patient")
            .build());

    register(
        ResourceCapability.of("ServiceRequest")
            .readWrite()
            .profile(profiles.serviceRequest())
            .param(
                SearchParamDef.reference(
                    "subject", "ServiceRequest.subject", "Sujeito (Patient)", "Patient"))
            .param(
                SearchParamDef.reference(
                    "patient", "ServiceRequest.subject", "Paciente", "Patient"))
            .param(SearchParamDef.token("status", "ServiceRequest.status", "Situação"))
            .param(
                SearchParamDef.token(
                    "code", "ServiceRequest.code", "Procedimento (SIGTAP/LOINC/local)"))
            .param(SearchParamDef.date("authored", "ServiceRequest.authoredOn", "Data do pedido"))
            .param(
                SearchParamDef.reference(
                    "requester",
                    "ServiceRequest.requester",
                    "Solicitante",
                    "Organization",
                    "PractitionerRole",
                    "Practitioner"))
            .param(
                SearchParamDef.reference(
                    "performer",
                    "ServiceRequest.performer",
                    "Executante/prestador",
                    "Organization",
                    "PractitionerRole",
                    "Practitioner"))
            .param(
                SearchParamDef.token(
                    "category",
                    "ServiceRequest.category",
                    "Categoria (tipo de regulação, laboratory/imaging)"))
            .param(SearchParamDef.token("priority", "ServiceRequest.priority", "Prioridade"))
            .include("patient")
            .build());

    register(
        ResourceCapability.of("Task")
            .readWrite()
            .profile(profiles.task())
            .param(SearchParamDef.reference("for", "Task.for", "Beneficiário (Patient)", "Patient"))
            .param(SearchParamDef.reference("patient", "Task.for", "Paciente", "Patient"))
            .param(SearchParamDef.token("status", "Task.status", "Situação"))
            .param(SearchParamDef.token("code", "Task.code", "Tipo de tarefa (task-type)"))
            .param(
                SearchParamDef.reference(
                    "owner",
                    "Task.owner",
                    "Responsável",
                    "PractitionerRole",
                    "CareTeam",
                    "Organization"))
            .param(SearchParamDef.date("authored-on", "Task.authoredOn", "Data de criação"))
            .param(
                SearchParamDef.token(
                    "business-status", "Task.businessStatus", "Status de negócio (canônico)"))
            .param(SearchParamDef.token("priority", "Task.priority", "Prioridade"))
            .param(
                SearchParamDef.reference(
                    "based-on",
                    "Task.basedOn",
                    "Origem da tarefa",
                    "ServiceRequest",
                    "Appointment",
                    "Encounter",
                    "Patient",
                    "Task"))
            .include("patient")
            .include("based-on")
            .build());

    register(
        ResourceCapability.of("Condition")
            .readWrite()
            .profile(profiles.condition())
            .param(
                SearchParamDef.reference(
                    "subject", "Condition.subject", "Sujeito (Patient)", "Patient"))
            .param(SearchParamDef.reference("patient", "Condition.subject", "Paciente", "Patient"))
            .param(SearchParamDef.token("code", "Condition.code", "Condição (CID-10/CIAP-2)"))
            .param(
                SearchParamDef.token(
                    "clinical-status", "Condition.clinicalStatus", "Status clínico"))
            .param(SearchParamDef.token("category", "Condition.category", "Categoria"))
            .param(SearchParamDef.date("onset-date", "Condition.onset", "Início (dateTime/Period)"))
            .build());

    register(
        ResourceCapability.of("CarePlan")
            .readWrite()
            .profile(profiles.carePlan())
            .param(
                SearchParamDef.reference(
                    "subject", "CarePlan.subject", "Sujeito (Patient)", "Patient"))
            .param(SearchParamDef.reference("patient", "CarePlan.subject", "Paciente", "Patient"))
            .param(SearchParamDef.token("status", "CarePlan.status", "Situação"))
            .param(
                SearchParamDef.token("category", "CarePlan.category", "Categoria/linha de cuidado"))
            .param(SearchParamDef.date("date", "CarePlan.period", "Período do plano"))
            .build());

    // ---- FHIR-3: resultados de exame, laudos e documentos ----------------------------------
    register(
        ResourceCapability.of("Observation")
            .readWrite()
            .profile(profiles.observation())
            .param(
                SearchParamDef.reference(
                    "subject", "Observation.subject", "Sujeito (Patient)", "Patient"))
            .param(
                SearchParamDef.reference("patient", "Observation.subject", "Paciente", "Patient"))
            .param(SearchParamDef.token("code", "Observation.code", "Código (LOINC/SIGTAP/local)"))
            .param(SearchParamDef.date("date", "Observation.effective", "Data/período efetivo"))
            .param(SearchParamDef.token("status", "Observation.status", "Situação"))
            .param(SearchParamDef.token("category", "Observation.category", "Categoria"))
            .param(
                SearchParamDef.quantity(
                    "value-quantity",
                    "Observation.value",
                    "Valor numérico (prefixos eq/ne/gt/lt/ge/le, [system|]code opcional)"))
            .param(
                SearchParamDef.reference(
                    "based-on", "Observation.basedOn", "Pedido de origem", "ServiceRequest"))
            .param(
                SearchParamDef.reference(
                    "encounter", "Observation.encounter", "Atendimento", "Encounter"))
            .include("patient")
            .include("based-on")
            .build());

    register(
        ResourceCapability.of("DiagnosticReport")
            .readWrite()
            .profile(profiles.diagnosticReport())
            .param(
                SearchParamDef.reference(
                    "subject", "DiagnosticReport.subject", "Sujeito (Patient)", "Patient"))
            .param(
                SearchParamDef.reference(
                    "patient", "DiagnosticReport.subject", "Paciente", "Patient"))
            .param(
                SearchParamDef.date("date", "DiagnosticReport.effective", "Data/período efetivo"))
            .param(SearchParamDef.token("status", "DiagnosticReport.status", "Situação"))
            .param(SearchParamDef.token("code", "DiagnosticReport.code", "Código do exame"))
            .param(
                SearchParamDef.token(
                    "category", "DiagnosticReport.category", "Categoria (LAB/RAD/OTH)"))
            .param(
                SearchParamDef.reference(
                    "based-on", "DiagnosticReport.basedOn", "Pedido de origem", "ServiceRequest"))
            .param(
                SearchParamDef.reference(
                    "result", "DiagnosticReport.result", "Observações do laudo", "Observation"))
            .param(
                SearchParamDef.reference(
                    "performer",
                    "DiagnosticReport.performer",
                    "Executante",
                    "Organization",
                    "PractitionerRole",
                    "Practitioner"))
            .include("patient")
            .include("result")
            .include("based-on")
            .build());

    register(
        ResourceCapability.of("DocumentReference")
            .readWrite()
            .profile(profiles.documentReference())
            .param(
                SearchParamDef.reference(
                    "subject", "DocumentReference.subject", "Sujeito (Patient)", "Patient"))
            .param(
                SearchParamDef.reference(
                    "patient", "DocumentReference.subject", "Paciente", "Patient"))
            .param(SearchParamDef.date("date", "DocumentReference.date", "Data do documento"))
            .param(SearchParamDef.token("status", "DocumentReference.status", "Situação"))
            .param(SearchParamDef.token("type", "DocumentReference.type", "Tipo (LOINC)"))
            .param(SearchParamDef.token("category", "DocumentReference.category", "Categoria"))
            .param(
                SearchParamDef.reference(
                    "author",
                    "DocumentReference.author",
                    "Autor",
                    "Organization",
                    "PractitionerRole",
                    "Practitioner"))
            .param(
                SearchParamDef.reference(
                    "related",
                    "DocumentReference.context.related",
                    "Recursos relacionados (pedido, laudo)",
                    "ServiceRequest",
                    "DiagnosticReport",
                    "Encounter"))
            .include("patient")
            .include("subject")
            .build());

    // Binary: conteúdo no object storage; apenas create/read, sem busca (compartimento via
    // securityContext → Patient)
    register(
        ResourceCapability.of("Binary")
            .interactions(Interaction.READ, Interaction.CREATE)
            .param(
                SearchParamDef.reference(
                    "patient",
                    "Binary.securityContext",
                    "Paciente do contexto de segurança (compartimento)",
                    "Patient"))
            .build());

    // Recursos de auditoria/proveniência: somente leitura (gerados pelo próprio gateway)
    register(
        ResourceCapability.of("AuditEvent")
            .interactions(Interaction.READ, Interaction.SEARCH_TYPE)
            .param(
                SearchParamDef.reference(
                    "entity", "AuditEvent.entity.what", "Recurso afetado", PROJECTED_TYPES))
            .param(SearchParamDef.date("date", "AuditEvent.recorded", "Data de registro"))
            .param(SearchParamDef.token("action", "AuditEvent.action", "Ação (C/R/U/D/E)"))
            .param(
                SearchParamDef.reference(
                    "agent",
                    "AuditEvent.agent.who",
                    "Agente (identificador do sujeito)",
                    "Practitioner",
                    "PractitionerRole",
                    "Device"))
            .param(
                SearchParamDef.reference(
                    "patient",
                    "AuditEvent.entity.what.where(reference.startsWith('Patient/'))",
                    "Paciente afetado",
                    "Patient"))
            .build());

    register(
        ResourceCapability.of("Provenance")
            .interactions(Interaction.READ, Interaction.SEARCH_TYPE)
            .param(
                SearchParamDef.reference(
                    "target", "Provenance.target", "Recurso derivado", PROJECTED_TYPES))
            .param(SearchParamDef.date("recorded", "Provenance.recorded", "Data de registro"))
            .param(
                SearchParamDef.reference(
                    "agent",
                    "Provenance.agent.who",
                    "Agente (identificador)",
                    "Practitioner",
                    "PractitionerRole",
                    "Organization",
                    "Device"))
            .build());
  }

  private void register(ResourceCapability capability) {
    if (resources.containsKey(capability.type())) {
      throw new IllegalStateException("Tipo registrado em duplicidade: " + capability.type());
    }
    resources.put(capability.type(), capability);
  }

  public Collection<ResourceCapability> all() {
    return Collections.unmodifiableCollection(resources.values());
  }

  public Optional<ResourceCapability> resource(String type) {
    return Optional.ofNullable(resources.get(type));
  }

  public boolean supports(String type, Interaction interaction) {
    return resource(type).map(r -> r.supports(interaction)).orElse(false);
  }

  /** Resolve um parâmetro de busca (comum ou específico do tipo). */
  public Optional<SearchParamDef> searchParam(String type, String name) {
    if (PARAM_ID.name().equals(name)) {
      return Optional.of(PARAM_ID);
    }
    if (PARAM_LAST_UPDATED.name().equals(name)) {
      return Optional.of(PARAM_LAST_UPDATED);
    }
    return resource(type).flatMap(r -> r.searchParam(name));
  }

  public List<SearchParamDef> commonParams() {
    return List.of(PARAM_ID, PARAM_LAST_UPDATED);
  }
}
