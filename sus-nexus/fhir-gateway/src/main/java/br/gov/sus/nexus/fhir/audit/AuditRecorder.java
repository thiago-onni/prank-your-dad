package br.gov.sus.nexus.fhir.audit;

import br.gov.sus.nexus.fhir.capability.CapabilityRegistry;
import br.gov.sus.nexus.fhir.codec.FhirCodec;
import br.gov.sus.nexus.fhir.interaction.IdGenerator;
import br.gov.sus.nexus.fhir.persistence.FhirResourceRepository;
import br.gov.sus.nexus.fhir.persistence.SearchIndexer;
import br.gov.sus.nexus.fhir.persistence.StoredResource;
import br.gov.sus.nexus.fhir.persistence.TenantTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.hl7.fhir.r4.model.AuditEvent;
import org.hl7.fhir.r4.model.Resource;
import org.jboss.logging.Logger;

/**
 * Persiste {@link AuditEvent} (e outros recursos gerados pelo sistema, como Provenance) na mesma
 * tabela de recursos, em transação própria. Falhas de auditoria são registradas em log (sem PII) e
 * não interrompem a interação principal.
 */
@ApplicationScoped
public class AuditRecorder {

  private static final Logger LOG = Logger.getLogger(AuditRecorder.class);

  @Inject AuditEventFactory factory;
  @Inject TenantTransaction tx;
  @Inject FhirResourceRepository repository;
  @Inject SearchIndexer indexer;
  @Inject CapabilityRegistry registry;
  @Inject FhirCodec codec;

  public void record(AuditEventFactory.AuditInput input) {
    if (!input.identity().hasTenant()) {
      // sem tenant não há partição onde gravar; a negação já foi respondida com 403
      return;
    }
    try {
      AuditEvent event = factory.build(input);
      storeSystemResource(input.identity().tenantId(), event);
    } catch (RuntimeException e) {
      LOG.errorf(
          "Falha ao gravar AuditEvent (interaction=%s type=%s correlation=%s): %s",
          input.interaction(),
          input.resourceType(),
          input.correlationId(),
          e.getClass().getSimpleName());
    }
  }

  /** Grava um recurso gerado pelo gateway (AuditEvent, Provenance) como versão 1. */
  public StoredResource storeSystemResource(String tenantId, Resource resource) {
    String id = resource.hasId() ? resource.getIdElement().getIdPart() : IdGenerator.ulid();
    Instant now = Instant.now();
    resource.setId(id);
    resource.getMeta().setVersionId("1");
    resource.getMeta().setLastUpdated(Date.from(now));
    StoredResource stored =
        new StoredResource(
            id, tenantId, resource.fhirType(), 1, now, List.of(), codec.encode(resource), false);
    var index =
        registry
            .resource(resource.fhirType())
            .map(c -> indexer.index(resource, c))
            .orElse(List.of());
    // transação própria: a auditoria sobrevive ao rollback de um Bundle transaction
    tx.executeIsolated(
        tenantId,
        c -> {
          repository.insert(c, stored, index);
          return null;
        });
    return stored;
  }
}
