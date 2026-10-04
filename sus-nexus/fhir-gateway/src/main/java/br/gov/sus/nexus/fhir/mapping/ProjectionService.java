package br.gov.sus.nexus.fhir.mapping;

import br.gov.sus.nexus.fhir.FhirConstants;
import br.gov.sus.nexus.fhir.audit.AuditRecorder;
import br.gov.sus.nexus.fhir.audit.ProvenanceFactory;
import br.gov.sus.nexus.fhir.audit.ProvenanceFactory.ProjectionSource;
import br.gov.sus.nexus.fhir.capability.CapabilityRegistry;
import br.gov.sus.nexus.fhir.capability.ResourceCapability;
import br.gov.sus.nexus.fhir.codec.FhirCodec;
import br.gov.sus.nexus.fhir.interaction.FhirException;
import br.gov.sus.nexus.fhir.persistence.FhirResourceRepository;
import br.gov.sus.nexus.fhir.persistence.SearchIndexer;
import br.gov.sus.nexus.fhir.persistence.SearchQuery;
import br.gov.sus.nexus.fhir.persistence.SearchQuery.SearchFilter;
import br.gov.sus.nexus.fhir.persistence.StoredResource;
import br.gov.sus.nexus.fhir.persistence.TenantTransaction;
import br.gov.sus.nexus.fhir.validation.ValidationService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.hl7.fhir.r4.model.Base;
import org.hl7.fhir.r4.model.CanonicalType;
import org.hl7.fhir.r4.model.DomainResource;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Property;
import org.hl7.fhir.r4.model.Provenance;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;

/**
 * Projeção canônico → FHIR com {@code Provenance}. Acionada pelo endpoint interno {@code
 * /internal/projections} e pelo consumidor Kafka ({@code projection/}). Idempotente: conteúdo
 * idêntico não gera nova versão. Referências lógicas por CNES ({@code Organization}/{@code
 * Location} com {@code identifier} CNES) são resolvidas para referências diretas quando o alvo já
 * foi projetado no tenant.
 */
@ApplicationScoped
public class ProjectionService {

  @Inject CitizenToPatientMapper patientMapper;
  @Inject HealthUnitToOrganizationMapper organizationMapper;
  @Inject AppointmentMapper appointmentMapper;
  @Inject TaskMapper taskMapper;
  @Inject RegulationRequestMapper regulationRequestMapper;
  @Inject ExamOrderMapper examOrderMapper;
  @Inject EncounterMapper encounterMapper;
  @Inject ExamResultMapper examResultMapper;
  @Inject HospitalEpisodeMapper hospitalEpisodeMapper;
  @Inject CarePlanMapper carePlanMapper;
  @Inject CareGapMapper careGapMapper;
  @Inject ValidationService validation;
  @Inject CapabilityRegistry registry;
  @Inject TenantTransaction tx;
  @Inject FhirResourceRepository repository;
  @Inject SearchIndexer indexer;
  @Inject FhirCodec codec;
  @Inject ProvenanceFactory provenanceFactory;
  @Inject AuditRecorder recorder;

  /** Resultado de uma projeção. */
  public record ProjectionResult(
      Resource resource,
      StoredResource stored,
      Optional<Provenance> provenance,
      boolean created,
      boolean changed) {}

  public ProjectionResult projectCitizen(
      String tenantId, CanonicalCitizen citizen, ProjectionSource source) {
    Patient patient = patientMapper.map(citizen);
    resolveManagingOrganization(tenantId, patient);
    return upsert(tenantId, patient, source);
  }

  public ProjectionResult projectHealthUnit(
      String tenantId, CanonicalHealthUnit unit, ProjectionSource source) {
    return upsert(tenantId, organizationMapper.map(unit), source);
  }

  public ProjectionResult projectAppointment(
      String tenantId, CanonicalAppointment appointment, ProjectionSource source) {
    return projectResolved(tenantId, appointmentMapper.map(appointment), source);
  }

  public ProjectionResult projectTask(
      String tenantId, CanonicalTask task, ProjectionSource source) {
    return projectResolved(tenantId, taskMapper.map(task), source);
  }

  public ProjectionResult projectRegulationRequest(
      String tenantId, CanonicalRegulationRequest request, ProjectionSource source) {
    return projectResolved(tenantId, regulationRequestMapper.map(request), source);
  }

  public ProjectionResult projectExamOrder(
      String tenantId, CanonicalExamOrder order, ProjectionSource source) {
    return projectResolved(tenantId, examOrderMapper.map(order), source);
  }

  public ProjectionResult projectEncounter(
      String tenantId, CanonicalEncounter encounter, ProjectionSource source) {
    return projectResolved(tenantId, encounterMapper.map(encounter), source);
  }

  // ---- FHIR-3 ---------------------------------------------------------------------------------

  /**
   * Projeção de um resultado de exame: Observations (uma por item), DiagnosticReport (com {@code
   * result} apontando para elas) e DocumentReference quando há documento. Cada recurso é
   * idempotente por conteúdo e recebe sua própria Provenance.
   */
  public record ExamResultProjection(
      ProjectionResult report,
      List<ProjectionResult> observations,
      Optional<ProjectionResult> document) {

    /** Todos os resultados na ordem de gravação (Observations, DiagnosticReport, Document). */
    public List<ProjectionResult> all() {
      List<ProjectionResult> list = new java.util.ArrayList<>(observations);
      list.add(report);
      document.ifPresent(list::add);
      return list;
    }

    public boolean changed() {
      return all().stream().anyMatch(ProjectionResult::changed);
    }
  }

  public ExamResultProjection projectExamResult(
      String tenantId,
      CanonicalExamOrder order,
      CanonicalExamResult result,
      ProjectionSource source) {
    ExamResultMapper.Mapped mapped = examResultMapper.map(order, result);
    List<ProjectionResult> observations = new java.util.ArrayList<>();
    for (var obs : mapped.observations()) {
      observations.add(projectResolved(tenantId, obs, source));
    }
    ProjectionResult report = projectResolved(tenantId, mapped.report(), source);
    Optional<ProjectionResult> document =
        mapped.document().map(doc -> projectResolved(tenantId, doc, source));
    return new ExamResultProjection(report, observations, document);
  }

  public ProjectionResult projectHospitalEpisode(
      String tenantId, CanonicalHospitalEpisode episode, ProjectionSource source) {
    return projectResolved(tenantId, hospitalEpisodeMapper.map(episode), source);
  }

  public ProjectionResult projectCarePlan(
      String tenantId, CanonicalCarePlan plan, ProjectionSource source) {
    return projectResolved(tenantId, carePlanMapper.map(plan), source);
  }

  public ProjectionResult projectCareGap(
      String tenantId, CanonicalCareGap gap, ProjectionSource source) {
    return projectResolved(tenantId, careGapMapper.map(gap), source);
  }

  private ProjectionResult projectResolved(
      String tenantId, DomainResource resource, ProjectionSource source) {
    resolveCnesReferences(tenantId, resource);
    return upsert(tenantId, resource, source);
  }

  private void resolveManagingOrganization(String tenantId, Patient patient) {
    Reference ref = patient.getManagingOrganization();
    if (ref == null || !ref.hasIdentifier() || ref.hasReference()) {
      return;
    }
    findByCnes(tenantId, "Organization", ref.getIdentifier().getValue())
        .ifPresent(id -> ref.setReference("Organization/" + id));
  }

  /**
   * Percorre todas as {@link Reference} do recurso; as lógicas com identifier CNES e tipo declarado
   * ({@code Organization}/{@code Location}) ganham referência direta quando o alvo existe no
   * tenant.
   */
  void resolveCnesReferences(String tenantId, Base root) {
    Map<String, Optional<String>> cache = new HashMap<>();
    walk(
        root,
        ref -> {
          if (ref.hasReference()
              || !ref.hasIdentifier()
              || !FhirConstants.SYSTEM_CNES.equals(ref.getIdentifier().getSystem())
              || !ref.hasType()) {
            return;
          }
          String type = ref.getType();
          if (!"Organization".equals(type) && !"Location".equals(type)) {
            return;
          }
          String cnes = ref.getIdentifier().getValue();
          cache
              .computeIfAbsent(type + "|" + cnes, k -> findByCnes(tenantId, type, cnes))
              .ifPresent(id -> ref.setReference(type + "/" + id));
        });
  }

  private static void walk(Base base, java.util.function.Consumer<Reference> visitor) {
    if (base instanceof Reference ref) {
      visitor.accept(ref);
      return;
    }
    for (Property p : base.children()) {
      for (Base child : p.getValues()) {
        if (child != null && !child.isPrimitive()) {
          walk(child, visitor);
        }
      }
    }
  }

  private Optional<String> findByCnes(String tenantId, String type, String cnes) {
    var def = registry.searchParam(type, "identifier").orElseThrow();
    SearchQuery q =
        new SearchQuery(
            type,
            List.of(new SearchFilter(def, "", List.of(FhirConstants.SYSTEM_CNES + "|" + cnes))),
            null,
            1);
    List<StoredResource> found = tx.execute(tenantId, c -> repository.search(c, q, tenantId));
    return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0).id());
  }

  private ProjectionResult upsert(
      String tenantId, DomainResource resource, ProjectionSource source) {
    String type = resource.fhirType();
    ResourceCapability cap =
        registry
            .resource(type)
            .orElseThrow(() -> FhirException.notSupported("Tipo não registrado: " + type));
    validation.validateOrThrow(resource, type);
    String id = resource.getIdElement().getIdPart();

    record Outcome(StoredResource stored, boolean created, boolean changed) {}
    Outcome outcome =
        tx.execute(
            tenantId,
            c -> {
              Optional<StoredResource> current = repository.findCurrentForUpdate(c, type, id);
              if (current.isPresent() && sameContent(current.get(), resource)) {
                return new Outcome(current.get(), false, false);
              }
              int version = current.map(s -> s.versionId() + 1).orElse(1);
              StoredResource next = prepare(tenantId, resource, id, version);
              List<br.gov.sus.nexus.fhir.persistence.IndexEntry> index =
                  indexer.index(resource, cap);
              if (current.isPresent()) {
                repository.update(c, next, index);
              } else {
                repository.insert(c, next, index);
              }
              return new Outcome(next, current.isEmpty(), true);
            });

    Optional<Provenance> provenance = Optional.empty();
    if (outcome.changed()) {
      Provenance prov =
          provenanceFactory.forProjection(type, id, outcome.stored().versionId(), source);
      recorder.storeSystemResource(tenantId, prov);
      provenance = Optional.of(prov);
    } else {
      // devolve o recurso armazenado (com meta) quando nada mudou
      resource.getMeta().setVersionId(String.valueOf(outcome.stored().versionId()));
      resource.getMeta().setLastUpdated(Date.from(outcome.stored().lastUpdated()));
    }
    return new ProjectionResult(
        resource, outcome.stored(), provenance, outcome.created(), outcome.changed());
  }

  private boolean sameContent(StoredResource current, Resource candidate) {
    Resource stored = codec.parse(current.content());
    Resource a = stored.copy();
    Resource b = candidate.copy();
    a.setMeta(null);
    b.setMeta(null);
    a.setId((String) null);
    b.setId((String) null);
    return codec.encode(a).equals(codec.encode(b));
  }

  private StoredResource prepare(String tenantId, Resource resource, String id, int version) {
    Instant now = Instant.now();
    resource.setId(id);
    resource.getMeta().setVersionId(String.valueOf(version));
    resource.getMeta().setLastUpdated(Date.from(now));
    List<String> profiles =
        resource.getMeta().getProfile().stream().map(CanonicalType::getValue).toList();
    return new StoredResource(
        id, tenantId, resource.fhirType(), version, now, profiles, codec.encode(resource), false);
  }
}
