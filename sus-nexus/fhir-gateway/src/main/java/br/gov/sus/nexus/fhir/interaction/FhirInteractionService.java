package br.gov.sus.nexus.fhir.interaction;

import br.gov.sus.nexus.fhir.audit.AuditEventFactory.AuditInput;
import br.gov.sus.nexus.fhir.audit.AuditRecorder;
import br.gov.sus.nexus.fhir.capability.CapabilityRegistry;
import br.gov.sus.nexus.fhir.capability.Interaction;
import br.gov.sus.nexus.fhir.capability.ResourceCapability;
import br.gov.sus.nexus.fhir.codec.FhirCodec;
import br.gov.sus.nexus.fhir.persistence.FhirResourceRepository;
import br.gov.sus.nexus.fhir.persistence.FhirResourceRepository.SearchHit;
import br.gov.sus.nexus.fhir.persistence.IndexEntry;
import br.gov.sus.nexus.fhir.persistence.SearchIndexer;
import br.gov.sus.nexus.fhir.persistence.SearchQuery;
import br.gov.sus.nexus.fhir.persistence.SearchQuery.SearchFilter;
import br.gov.sus.nexus.fhir.persistence.StoredResource;
import br.gov.sus.nexus.fhir.persistence.TenantTransaction;
import br.gov.sus.nexus.fhir.security.AccessPolicy;
import br.gov.sus.nexus.fhir.security.AccessPolicy.AccessRequest;
import br.gov.sus.nexus.fhir.security.AccessPolicy.Decision;
import br.gov.sus.nexus.fhir.security.Identity;
import br.gov.sus.nexus.fhir.security.PatientCompartment;
import br.gov.sus.nexus.fhir.security.Permission;
import br.gov.sus.nexus.fhir.security.RedactionPolicy;
import br.gov.sus.nexus.fhir.security.RequestContext;
import br.gov.sus.nexus.fhir.validation.ValidationIssue;
import br.gov.sus.nexus.fhir.validation.ValidationService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Bundle.BundleEntryComponent;
import org.hl7.fhir.r4.model.Bundle.BundleType;
import org.hl7.fhir.r4.model.Bundle.HTTPVerb;
import org.hl7.fhir.r4.model.Bundle.SearchEntryMode;
import org.hl7.fhir.r4.model.CanonicalType;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.Resource;

/**
 * Roteador/executor das interações FHIR. Para cada interação: verifica o registro de capacidades,
 * aplica a {@link AccessPolicy}, executa em transação por tenant (RLS), aplica a {@link
 * RedactionPolicy} e registra {@code AuditEvent}.
 */
@ApplicationScoped
public class FhirInteractionService {

  @Inject CapabilityRegistry registry;
  @Inject TenantTransaction tx;
  @Inject FhirResourceRepository repository;
  @Inject SearchIndexer indexer;
  @Inject FhirCodec codec;
  @Inject ValidationService validation;
  @Inject AccessPolicy accessPolicy;
  @Inject RedactionPolicy redaction;
  @Inject RequestContext context;
  @Inject AuditRecorder audit;
  @Inject SearchRequestParser searchParser;
  @Inject SearchCursor cursor;
  @Inject PatientCompartment compartment;

  // ---- read / vread / history
  // --------------------------------------------------------------------

  public InteractionResult read(String type, String id, String ifNoneMatch) {
    ResourceCapability cap = requireSupported(type, Interaction.READ);
    authorize(Interaction.READ, type, id);
    StoredResource stored =
        tx.execute(tenant(), c -> repository.findCurrent(c, type, id))
            .orElseThrow(() -> FhirException.notFound(type, id));
    if (stored.deleted()) {
      throw FhirException.gone(type, id);
    }
    Resource loaded = codec.parse(stored.content());
    enforceReadRestrictions(Interaction.READ, type, id, loaded);
    if (etagMatches(ifNoneMatch, stored.versionId())) {
      recordAudit(Interaction.READ, type, id, stored.versionId(), null, true, null);
      return InteractionResult.notModified(stored.etag());
    }
    Resource resource = redact(loaded);
    recordAudit(Interaction.READ, type, id, stored.versionId(), null, true, null);
    return new InteractionResult(200, resource, stored.etag(), stored.lastUpdated(), null);
  }

  public InteractionResult vread(String type, String id, String versionId) {
    requireSupported(type, Interaction.VREAD);
    authorize(Interaction.VREAD, type, id);
    int vid;
    try {
      vid = Integer.parseInt(versionId);
    } catch (NumberFormatException e) {
      throw FhirException.versionNotFound(type, id, versionId);
    }
    StoredResource stored =
        tx.execute(tenant(), c -> repository.findVersion(c, type, id, vid))
            .orElseThrow(() -> FhirException.versionNotFound(type, id, versionId));
    if (stored.deleted()) {
      throw FhirException.gone(type, id);
    }
    Resource loaded = codec.parse(stored.content());
    enforceReadRestrictions(Interaction.VREAD, type, id, loaded);
    Resource resource = redact(loaded);
    recordAudit(Interaction.VREAD, type, id, vid, null, true, null);
    return new InteractionResult(200, resource, stored.etag(), stored.lastUpdated(), null);
  }

  public InteractionResult history(String type, String id, String baseUrl) {
    requireSupported(type, Interaction.HISTORY_INSTANCE);
    authorize(Interaction.HISTORY_INSTANCE, type, id);
    List<StoredResource> versions = tx.execute(tenant(), c -> repository.history(c, type, id));
    if (versions.isEmpty()) {
      throw FhirException.notFound(type, id);
    }
    // o pertencimento ao compartimento é decidido pela versão corrente
    StoredResource current = versions.get(0);
    if (!current.deleted()) {
      enforceReadRestrictions(
          Interaction.HISTORY_INSTANCE, type, id, codec.parse(current.content()));
    }
    Bundle bundle = new Bundle();
    bundle.setType(BundleType.HISTORY);
    bundle.setTotal(versions.size());
    bundle.setTimestamp(new Date());
    bundle.addLink().setRelation("self").setUrl(baseUrl + "/" + type + "/" + id + "/_history");
    for (StoredResource v : versions) {
      BundleEntryComponent entry = bundle.addEntry();
      entry.setFullUrl(baseUrl + "/" + type + "/" + id);
      entry
          .getRequest()
          .setMethod(v.versionId() == 1 ? HTTPVerb.POST : HTTPVerb.PUT)
          .setUrl(v.versionId() == 1 ? type : type + "/" + id);
      entry
          .getResponse()
          .setStatus(v.versionId() == 1 ? "201 Created" : "200 OK")
          .setEtag(v.etag())
          .setLastModified(Date.from(v.lastUpdated()));
      if (!v.deleted()) {
        Resource version = codec.parse(v.content());
        if (!redaction.withhold(context.identity(), version)) {
          entry.setResource(redact(version));
        }
      }
    }
    recordAudit(Interaction.HISTORY_INSTANCE, type, id, null, null, true, null);
    return InteractionResult.ok(bundle);
  }

  // ---- search
  // ------------------------------------------------------------------------------------

  public InteractionResult search(
      String type, Map<String, List<String>> rawParams, String baseUrl, String selfUrl) {
    requireSupported(type, Interaction.SEARCH_TYPE);
    authorize(Interaction.SEARCH_TYPE, type, null);
    SearchRequestParser.Parsed parsed = searchParser.parse(type, rawParams);
    SearchQuery query = applyPatientCompartment(type, parsed.query());

    List<SearchHit> rows = tx.execute(tenant(), c -> repository.searchHits(c, query, tenant()));
    boolean hasNext = rows.size() > query.count();
    List<SearchHit> page = hasNext ? rows.subList(0, query.count()) : rows;

    Bundle bundle = new Bundle();
    bundle.setType(BundleType.SEARCHSET);
    bundle.setTimestamp(new Date());
    bundle.addLink().setRelation("self").setUrl(selfUrl);
    if (parsed.totalAccurate()) {
      long total = tx.execute(tenant(), c -> repository.count(c, query, tenant()));
      bundle.setTotal((int) Math.min(total, Integer.MAX_VALUE));
    }
    if (hasNext) {
      SearchHit last = page.get(page.size() - 1);
      String next =
          cursor.encode(
              new SearchCursor.Payload(
                  type,
                  parsed.params(),
                  last.resource().id(),
                  query.count(),
                  query.sort() == null || last.sortKey() == null
                      ? null
                      : last.sortKey().toString()));
      bundle.addLink().setRelation("next").setUrl(baseUrl + "/" + type + "?_cursor=" + next);
    }
    Set<String> present = new LinkedHashSet<>();
    List<String> pageIds = new ArrayList<>();
    for (SearchHit hit : page) {
      StoredResource r = hit.resource();
      Resource resource = codec.parse(r.content());
      if (redaction.withhold(context.identity(), resource)) {
        continue;
      }
      pageIds.add(r.id());
      present.add(type + "/" + r.id());
      BundleEntryComponent entry = bundle.addEntry();
      entry.setFullUrl(baseUrl + "/" + type + "/" + r.id());
      entry.setResource(redact(resource));
      entry.getSearch().setMode(SearchEntryMode.MATCH);
    }
    for (SearchRequestParser.Include include : parsed.includes()) {
      addIncludes(bundle, baseUrl, include, pageIds, present);
    }
    String queryText =
        parsed.params().entrySet().stream()
            .map(e -> e.getKey() + "=" + String.join(",", e.getValue()))
            .collect(Collectors.joining("&"));
    recordAudit(Interaction.SEARCH_TYPE, type, null, null, queryText, true, null);
    return InteractionResult.ok(bundle);
  }

  /**
   * {@code _include}: alvos vêm do índice de referências da página; cada alvo passa pela política
   * de acesso de leitura (negados são omitidos silenciosamente), pela retenção e pela redação.
   */
  private void addIncludes(
      Bundle bundle,
      String baseUrl,
      SearchRequestParser.Include include,
      List<String> pageIds,
      Set<String> present) {
    List<IndexEntry.Ref> targets =
        tx.execute(tenant(), c -> repository.referenceTargets(c, pageIds, include.param().name()));
    for (IndexEntry.Ref ref : targets) {
      String targetType = ref.targetType();
      if (targetType == null || !include.param().targets().contains(targetType)) {
        continue;
      }
      if (include.targetType() != null && !include.targetType().equals(targetType)) {
        continue;
      }
      String key = targetType + "/" + ref.targetId();
      if (!present.add(key)) {
        continue;
      }
      if (!registry.supports(targetType, Interaction.READ)) {
        continue;
      }
      Decision decision =
          accessPolicy.evaluate(
              new AccessRequest(
                  context.identity(),
                  Interaction.READ,
                  targetType,
                  ref.targetId(),
                  context.purposeOfUse()));
      if (!decision.allowed()) {
        continue;
      }
      Optional<StoredResource> stored =
          tx.execute(tenant(), c -> repository.findCurrent(c, targetType, ref.targetId()));
      if (stored.isEmpty() || stored.get().deleted()) {
        continue;
      }
      Resource resource = codec.parse(stored.get().content());
      if (redaction.withhold(context.identity(), resource)
          || !allowedInPatientContext(targetType, resource)) {
        continue;
      }
      BundleEntryComponent entry = bundle.addEntry();
      entry.setFullUrl(baseUrl + "/" + key);
      entry.setResource(redact(resource));
      entry.getSearch().setMode(SearchEntryMode.INCLUDE);
    }
  }

  /**
   * No contexto {@code patient/}, a busca fica restrita ao compartimento do paciente: {@code
   * Patient} por {@code _id}; os demais tipos pelo parâmetro {@code patient} registrado.
   */
  private SearchQuery applyPatientCompartment(String type, SearchQuery query) {
    Identity identity = context.identity();
    if (!identity.onlyPatientContext(type, Permission.SEARCH)) {
      return query;
    }
    String patientId = identity.patientId().orElseThrow();
    List<SearchFilter> filters = new ArrayList<>(query.filters());
    if ("Patient".equals(type)) {
      filters.add(new SearchFilter(CapabilityRegistry.PARAM_ID, "", List.of(patientId)));
    } else {
      var def =
          registry
              .searchParam(type, CapabilityRegistry.PATIENT_PARAM)
              .orElseThrow(
                  () -> FhirException.forbidden("Tipo sem compartimento de paciente: " + type));
      filters.add(new SearchFilter(def, "", List.of("Patient/" + patientId)));
    }
    return query.withFilters(filters);
  }

  /** Leituras por id: pertencimento ao compartimento (contexto patient/) e retenção total. */
  private void enforceReadRestrictions(
      Interaction interaction, String type, String id, Resource resource) {
    Identity identity = context.identity();
    if (!allowedInPatientContext(type, resource)) {
      recordAudit(interaction, type, id, null, null, false, "patient-context-other-patient");
      throw FhirException.forbidden("Acesso negado: patient-context-other-patient");
    }
    if (redaction.withhold(identity, resource)) {
      recordAudit(interaction, type, id, null, null, false, "highly-restricted");
      throw FhirException.forbidden(
          "Acesso negado: recurso highly_restricted exige escopo de leitura completo");
    }
  }

  private boolean allowedInPatientContext(String type, Resource resource) {
    Identity identity = context.identity();
    if (!identity.onlyPatientContext(type, Permission.READ)) {
      return true;
    }
    return identity.patientId().map(pid -> compartment.belongsTo(resource, pid)).orElse(false);
  }

  // ---- create / update
  // -----------------------------------------------------------------------------

  public InteractionResult create(String type, String json, String baseUrl) {
    ResourceCapability cap = requireSupported(type, Interaction.CREATE);
    authorize(Interaction.CREATE, type, null);
    Resource resource = validation.parseOrThrow(json);
    validation.validateOrThrow(resource, type);

    String id = IdGenerator.ulid();
    StoredResource stored = prepare(resource, id, 1);
    List<IndexEntry> index = indexer.index(resource, cap);
    tx.execute(
        tenant(),
        c -> {
          repository.insert(c, stored, index);
          return null;
        });
    recordAudit(Interaction.CREATE, type, id, 1, null, true, null);
    return new InteractionResult(
        201, resource, stored.etag(), stored.lastUpdated(), location(baseUrl, type, id, 1));
  }

  public InteractionResult update(
      String type, String id, String json, String ifMatch, String baseUrl) {
    ResourceCapability cap = requireSupported(type, Interaction.UPDATE);
    authorize(Interaction.UPDATE, type, id);
    Resource resource = validation.parseOrThrow(json);
    if (resource.hasId() && !id.equals(resource.getIdElement().getIdPart())) {
      throw FhirException.invalid(
          "O id do recurso ("
              + resource.getIdElement().getIdPart()
              + ") difere do id da URL ("
              + id
              + ")",
          type + ".id");
    }
    validation.validateOrThrow(resource, type);

    record Outcome(StoredResource stored, boolean created) {}
    Outcome outcome =
        tx.execute(
            tenant(),
            c -> {
              Optional<StoredResource> current = repository.findCurrentForUpdate(c, type, id);
              if (current.isPresent()) {
                int currentVersion = current.get().versionId();
                if (ifMatch != null && !etagMatches(ifMatch, currentVersion)) {
                  throw FhirException.conflict(
                      "Versão informada em If-Match não corresponde à versão atual ("
                          + current.get().etag()
                          + ")");
                }
                StoredResource next = prepare(resource, id, currentVersion + 1);
                repository.update(c, next, indexer.index(resource, cap));
                return new Outcome(next, false);
              }
              if (ifMatch != null) {
                throw FhirException.conflict("If-Match informado para recurso inexistente");
              }
              StoredResource created = prepare(resource, id, 1);
              repository.insert(c, created, indexer.index(resource, cap));
              return new Outcome(created, true);
            });
    StoredResource stored = outcome.stored();
    recordAudit(
        outcome.created() ? Interaction.CREATE : Interaction.UPDATE,
        type,
        id,
        stored.versionId(),
        null,
        true,
        null);
    return new InteractionResult(
        outcome.created() ? 201 : 200,
        resource,
        stored.etag(),
        stored.lastUpdated(),
        location(baseUrl, type, id, stored.versionId()));
  }

  // ---- $validate
  // -----------------------------------------------------------------------------------

  public OperationOutcome validate(String json, String expectedType) {
    if (expectedType != null) {
      requireKnownType(expectedType);
    }
    Resource resource = validation.parseOrThrow(json);
    List<ValidationIssue> issues = validation.validate(resource, expectedType);
    return issues.isEmpty() ? OperationOutcomes.allOk() : OperationOutcomes.fromIssues(issues);
  }

  // ---- helpers
  // -------------------------------------------------------------------------------------

  private String tenant() {
    return context.tenantId();
  }

  private ResourceCapability requireSupported(String type, Interaction interaction) {
    ResourceCapability cap = requireKnownType(type);
    if (!cap.supports(interaction)) {
      throw FhirException.methodNotAllowed(
          "Interação " + interaction.code() + " não suportada para " + type);
    }
    return cap;
  }

  private ResourceCapability requireKnownType(String type) {
    return registry
        .resource(type)
        .orElseThrow(() -> FhirException.notSupported("Tipo de recurso não suportado: " + type));
  }

  private void authorize(Interaction interaction, String type, String id) {
    Identity identity = context.identity();
    if (!identity.isAuthenticated()) {
      throw FhirException.unauthorized("Autenticação requerida");
    }
    Decision decision =
        accessPolicy.evaluate(
            new AccessRequest(identity, interaction, type, id, context.purposeOfUse()));
    if (!decision.allowed()) {
      recordAudit(interaction, type, id, null, null, false, decision.reason());
      if ("tenant-missing".equals(decision.reason())) {
        throw FhirException.forbidden(
            "Tenant não identificado (claim municipality_id / X-Tenant-Id)");
      }
      throw FhirException.forbidden("Acesso negado: " + decision.reason());
    }
  }

  private Resource redact(Resource resource) {
    return redaction.apply(context.identity(), resource);
  }

  private StoredResource prepare(Resource resource, String id, int version) {
    Instant now = Instant.now();
    resource.setId(id);
    resource.getMeta().setVersionId(String.valueOf(version));
    resource.getMeta().setLastUpdated(Date.from(now));
    List<String> profiles =
        resource.getMeta().getProfile().stream().map(CanonicalType::getValue).toList();
    return new StoredResource(
        id, tenant(), resource.fhirType(), version, now, profiles, codec.encode(resource), false);
  }

  private static String location(String baseUrl, String type, String id, int version) {
    return baseUrl + "/" + type + "/" + id + "/_history/" + version;
  }

  /** Compara um header If-Match/If-None-Match ({@code W/"3"}, {@code "3"} ou {@code *}). */
  static boolean etagMatches(String header, int version) {
    if (header == null || header.isBlank()) {
      return false;
    }
    for (String part : header.split(",")) {
      String v = part.trim();
      if ("*".equals(v)) {
        return true;
      }
      if (v.startsWith("W/")) {
        v = v.substring(2);
      }
      v = v.replace("\"", "");
      if (v.equals(String.valueOf(version))) {
        return true;
      }
    }
    return false;
  }

  private void recordAudit(
      Interaction interaction,
      String type,
      String id,
      Integer version,
      String query,
      boolean success,
      String denyReason) {
    audit.record(
        new AuditInput(
            context.identity(),
            interaction,
            type,
            id,
            version,
            context.purposeOfUse(),
            context.correlationId(),
            query,
            success,
            denyReason));
  }
}
