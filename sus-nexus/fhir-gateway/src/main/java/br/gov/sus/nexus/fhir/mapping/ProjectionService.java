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
import java.util.List;
import java.util.Optional;
import org.hl7.fhir.r4.model.CanonicalType;
import org.hl7.fhir.r4.model.DomainResource;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Provenance;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;

/**
 * Projeção canônico → FHIR com {@code Provenance}. Nesta entrega é acionada pelo endpoint interno
 * {@code /internal/projections}; em produção, pelo consumidor Kafka de {@code
 * sus.identity.citizen.v1} (que busca o detalhe no core). Idempotente: conteúdo idêntico não gera
 * nova versão.
 */
@ApplicationScoped
public class ProjectionService {

  @Inject CitizenToPatientMapper patientMapper;
  @Inject HealthUnitToOrganizationMapper organizationMapper;
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

  private void resolveManagingOrganization(String tenantId, Patient patient) {
    Reference ref = patient.getManagingOrganization();
    if (ref == null || !ref.hasIdentifier() || ref.hasReference()) {
      return;
    }
    var def = registry.searchParam("Organization", "identifier").orElseThrow();
    SearchQuery q =
        new SearchQuery(
            "Organization",
            List.of(
                new SearchFilter(
                    def,
                    "",
                    List.of(FhirConstants.SYSTEM_CNES + "|" + ref.getIdentifier().getValue()))),
            null,
            1);
    List<StoredResource> found = tx.execute(tenantId, c -> repository.search(c, q, tenantId));
    if (!found.isEmpty()) {
      ref.setReference("Organization/" + found.get(0).id());
    }
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
