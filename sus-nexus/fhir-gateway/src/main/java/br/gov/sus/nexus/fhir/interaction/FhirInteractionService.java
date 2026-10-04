package br.gov.sus.nexus.fhir.interaction;

import br.gov.sus.nexus.fhir.audit.AuditEventFactory.AuditInput;
import br.gov.sus.nexus.fhir.audit.AuditRecorder;
import br.gov.sus.nexus.fhir.capability.CapabilityRegistry;
import br.gov.sus.nexus.fhir.capability.Interaction;
import br.gov.sus.nexus.fhir.capability.ResourceCapability;
import br.gov.sus.nexus.fhir.codec.FhirCodec;
import br.gov.sus.nexus.fhir.config.FhirGatewayConfig;
import br.gov.sus.nexus.fhir.directwrite.DirectWriteService;
import br.gov.sus.nexus.fhir.persistence.FhirDates;
import br.gov.sus.nexus.fhir.persistence.FhirResourceRepository;
import br.gov.sus.nexus.fhir.persistence.FhirResourceRepository.HistoryCursor;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Bundle.BundleEntryComponent;
import org.hl7.fhir.r4.model.Bundle.BundleType;
import org.hl7.fhir.r4.model.Bundle.HTTPVerb;
import org.hl7.fhir.r4.model.Bundle.SearchEntryMode;
import org.hl7.fhir.r4.model.CanonicalType;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.OperationOutcome.IssueSeverity;
import org.hl7.fhir.r4.model.OperationOutcome.IssueType;
import org.hl7.fhir.r4.model.Parameters;
import org.hl7.fhir.r4.model.Resource;

/**
 * Roteador/executor das interações FHIR. Para cada interação: verifica o registro de capacidades,
 * aplica a {@link AccessPolicy}, executa em transação por tenant (RLS), aplica a {@link
 * RedactionPolicy} e registra {@code AuditEvent}.
 *
 * <p>FHIR-3: {@code delete} lógico, {@code _history} de tipo/sistema, {@code Patient/$everything},
 * create condicional ({@code If-None-Exist}) para Bundles, {@code $validate} com {@code Parameters}
 * e escrita direta → canônico ({@link DirectWriteService}) para parceiros com escopo de sistema.
 */
@ApplicationScoped
public class FhirInteractionService {

  static final String EVERYTHING_CURSOR_TYPE = "$everything";
  static final String HISTORY_CURSOR_TYPE = "_history";

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
  @Inject FhirGatewayConfig config;
  @Inject DirectWriteService directWrite;

  // ---- read / vread / history
  // --------------------------------------------------------------------

  public InteractionResult read(String type, String id, String ifNoneMatch) {
    requireSupported(type, Interaction.READ);
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
    // o pertencimento ao compartimento é decidido pela versão corrente (ou pela última com
    // conteúdo)
    StoredResource current = versions.get(0);
    StoredResource reference =
        current.deleted()
            ? versions.stream().filter(v -> !v.deleted()).findFirst().orElse(current)
            : current;
    if (!reference.deleted()) {
      enforceReadRestrictions(
          Interaction.HISTORY_INSTANCE, type, id, codec.parse(reference.content()));
    }
    Bundle bundle = new Bundle();
    bundle.setType(BundleType.HISTORY);
    bundle.setTotal(versions.size());
    bundle.setTimestamp(new Date());
    bundle.addLink().setRelation("self").setUrl(baseUrl + "/" + type + "/" + id + "/_history");
    for (StoredResource v : versions) {
      addHistoryEntry(bundle, baseUrl, v);
    }
    recordAudit(Interaction.HISTORY_INSTANCE, type, id, null, null, true, null);
    return InteractionResult.ok(bundle);
  }

  /** Entrada de histórico: versão excluída vira {@code DELETE}/204 sem recurso. */
  private void addHistoryEntry(Bundle bundle, String baseUrl, StoredResource v) {
    BundleEntryComponent entry = bundle.addEntry();
    entry.setFullUrl(baseUrl + "/" + v.resourceType() + "/" + v.id());
    if (v.deleted()) {
      entry.getRequest().setMethod(HTTPVerb.DELETE).setUrl(v.resourceType() + "/" + v.id());
      entry
          .getResponse()
          .setStatus("204 No Content")
          .setEtag(v.etag())
          .setLastModified(Date.from(v.lastUpdated()));
      return;
    }
    entry
        .getRequest()
        .setMethod(v.versionId() == 1 ? HTTPVerb.POST : HTTPVerb.PUT)
        .setUrl(v.versionId() == 1 ? v.resourceType() : v.resourceType() + "/" + v.id());
    entry
        .getResponse()
        .setStatus(v.versionId() == 1 ? "201 Created" : "200 OK")
        .setEtag(v.etag())
        .setLastModified(Date.from(v.lastUpdated()));
    Resource version = codec.parse(v.content());
    if (!redaction.withhold(context.identity(), version)) {
      entry.setResource(redact(version));
    }
  }

  /**
   * {@code GET [type]/_history} ({@code type != null}) ou {@code GET _history} (sistema): versões
   * mais recentes primeiro, {@code _since}, {@code _count} e cursor keyset. No contexto {@code
   * patient/} só entram versões com conteúdo pertencente ao compartimento.
   */
  public InteractionResult historyList(
      String type, Map<String, List<String>> rawParams, String baseUrl) {
    Identity identity = context.identity();
    if (type != null) {
      requireSupported(type, Interaction.HISTORY_TYPE);
      authorize(Interaction.HISTORY_TYPE, type, null);
    } else {
      if (!identity.isAuthenticated()) {
        throw FhirException.unauthorized("Autenticação requerida");
      }
      if (!identity.hasTenant()) {
        throw FhirException.forbidden(
            "Tenant não identificado (claim municipality_id / X-Tenant-Id)");
      }
    }
    Map<String, List<String>> params = new TreeMap<>();
    HistoryCursor after = null;
    int count = config.search().defaultCount();
    List<String> cursorValues = rawParams.get(SearchRequestParser.CURSOR_PARAM);
    if (cursorValues != null && !cursorValues.isEmpty()) {
      SearchCursor.Payload payload = cursor.decode(cursorValues.get(0));
      String expected = HISTORY_CURSOR_TYPE + (type == null ? "" : ":" + type);
      if (!expected.equals(payload.type())) {
        throw FhirException.invalid("Cursor não pertence a esta listagem", "_cursor");
      }
      params.putAll(payload.params());
      count = payload.count();
      String[] parts = payload.afterId().split("\\|");
      after =
          new HistoryCursor(
              Instant.parse(payload.afterSortKey()), parts[0], Integer.parseInt(parts[1]));
    } else {
      for (Map.Entry<String, List<String>> e : rawParams.entrySet()) {
        switch (e.getKey()) {
          case "_count" -> count = parseCount(e.getValue().get(0), config.search().maxCount());
          case "_since", "_format", "_pretty" -> params.put(e.getKey(), e.getValue());
          default ->
              throw FhirException.invalid(
                  "Parâmetro não suportado em _history: " + e.getKey(), e.getKey());
        }
      }
    }
    Instant since = parseSince(params.get("_since"));
    List<String> types = new ArrayList<>();
    if (type != null) {
      types.add(type);
    } else {
      for (ResourceCapability cap : registry.all()) {
        if (cap.supports(Interaction.HISTORY_TYPE)
            && accessPolicy
                .evaluate(
                    new AccessRequest(
                        identity,
                        Interaction.HISTORY_TYPE,
                        cap.type(),
                        null,
                        context.purposeOfUse()))
                .allowed()) {
          types.add(cap.type());
        }
      }
    }
    final HistoryCursor afterFinal = after;
    final int limit = count;
    List<StoredResource> rows =
        types.isEmpty()
            ? List.of()
            : tx.execute(
                tenant(),
                c -> repository.historyPage(c, tenant(), types, afterFinal, since, limit + 1));
    boolean hasNext = rows.size() > limit;
    List<StoredResource> page = hasNext ? rows.subList(0, limit) : rows;

    Bundle bundle = new Bundle();
    bundle.setType(BundleType.HISTORY);
    bundle.setTimestamp(new Date());
    bundle
        .addLink()
        .setRelation("self")
        .setUrl(baseUrl + (type == null ? "" : "/" + type) + "/_history");
    for (StoredResource v : page) {
      // contexto patient/: só versões com conteúdo pertencente ao compartimento
      if (identity.onlyPatientContext(v.resourceType(), Permission.SEARCH)) {
        if (v.deleted()) {
          continue;
        }
        Resource r = codec.parse(v.content());
        if (!identity.patientId().map(pid -> compartment.belongsTo(r, pid)).orElse(false)) {
          continue;
        }
      }
      addHistoryEntry(bundle, baseUrl, v);
    }
    if (hasNext) {
      StoredResource last = page.get(page.size() - 1);
      String next =
          cursor.encode(
              new SearchCursor.Payload(
                  HISTORY_CURSOR_TYPE + (type == null ? "" : ":" + type),
                  params,
                  last.id() + "|" + last.versionId(),
                  limit,
                  last.lastUpdated().toString()));
      bundle
          .addLink()
          .setRelation("next")
          .setUrl(baseUrl + (type == null ? "" : "/" + type) + "/_history?_cursor=" + next);
    }
    audit.record(
        new AuditInput(
            identity,
            Interaction.HISTORY_TYPE,
            type,
            null,
            null,
            context.purposeOfUse(),
            context.correlationId(),
            queryText(params),
            true,
            null,
            type == null ? "history-system" : null,
            List.of()));
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
    recordAudit(Interaction.SEARCH_TYPE, type, null, null, queryText(parsed.params()), true, null);
    return InteractionResult.ok(bundle);
  }

  private static String queryText(Map<String, List<String>> params) {
    return params.entrySet().stream()
        .map(e -> e.getKey() + "=" + String.join(",", e.getValue()))
        .collect(Collectors.joining("&"));
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
      loadVisible(targetType, ref.targetId())
          .ifPresent(
              resource -> {
                BundleEntryComponent entry = bundle.addEntry();
                entry.setFullUrl(baseUrl + "/" + key);
                entry.setResource(redact(resource));
                entry.getSearch().setMode(SearchEntryMode.INCLUDE);
              });
    }
  }

  /** Carrega um recurso somente se a identidade pode lê-lo (política, retenção, compartimento). */
  private Optional<Resource> loadVisible(String type, String id) {
    if (!registry.supports(type, Interaction.READ)) {
      return Optional.empty();
    }
    Decision decision =
        accessPolicy.evaluate(
            new AccessRequest(
                context.identity(), Interaction.READ, type, id, context.purposeOfUse()));
    if (!decision.allowed()) {
      return Optional.empty();
    }
    Optional<StoredResource> stored =
        tx.execute(tenant(), c -> repository.findCurrent(c, type, id));
    if (stored.isEmpty() || stored.get().deleted()) {
      return Optional.empty();
    }
    Resource resource = codec.parse(stored.get().content());
    if (redaction.withhold(context.identity(), resource)
        || !allowedInPatientContext(type, resource)) {
      return Optional.empty();
    }
    return Optional.of(resource);
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
  void enforceReadRestrictions(Interaction interaction, String type, String id, Resource resource) {
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

  // ---- $everything
  // ---------------------------------------------------------------------------------

  /**
   * {@code GET Patient/[id]/$everything}: o Patient e todos os recursos do compartimento (tipos de
   * {@link CapabilityRegistry#EVERYTHING_DEFAULT_TYPES} ou {@code _type}), filtrados por política
   * de acesso, retenção e redação, paginados por cursor ({@code _count}, {@code _since}) com limite
   * de páginas configurável. Um {@code AuditEvent} por página com uma entidade por recurso
   * devolvido.
   */
  public InteractionResult everything(
      String patientId, Map<String, List<String>> rawParams, String baseUrl) {
    requireSupported("Patient", Interaction.READ);
    authorize(Interaction.READ, "Patient", patientId);
    Identity identity = context.identity();

    Map<String, List<String>> params = new TreeMap<>();
    String afterType = null;
    String afterId = null;
    int count = config.everything().defaultCount();
    int page = 1;
    List<String> cursorValues = rawParams.get(SearchRequestParser.CURSOR_PARAM);
    if (cursorValues != null && !cursorValues.isEmpty()) {
      SearchCursor.Payload payload = cursor.decode(cursorValues.get(0));
      if (!(EVERYTHING_CURSOR_TYPE + ":" + patientId).equals(payload.type())) {
        throw FhirException.invalid("Cursor não pertence a esta operação", "_cursor");
      }
      params.putAll(payload.params());
      count = payload.count();
      String[] parts = payload.afterId().split("/", 2);
      afterType = parts[0];
      afterId = parts[1];
      page = Integer.parseInt(params.getOrDefault("_page", List.of("1")).get(0));
      int maxPages = config.everything().maxPages();
      if (maxPages > 0 && page > maxPages) {
        throw new FhirException(
            400,
            IssueType.TOOCOSTLY,
            "Limite de páginas de $everything excedido (" + maxPages + ")",
            "_cursor");
      }
    } else {
      for (Map.Entry<String, List<String>> e : rawParams.entrySet()) {
        switch (e.getKey()) {
          case "_count" -> count = parseCount(e.getValue().get(0), config.everything().maxCount());
          case "_since", "_type" -> params.put(e.getKey(), e.getValue());
          case "_format", "_pretty" -> {
            // ignorados
          }
          default ->
              throw FhirException.invalid(
                  "Parâmetro não suportado em $everything: " + e.getKey(), e.getKey());
        }
      }
    }
    Instant since = parseSince(params.get("_since"));
    List<String> types = everythingTypes(params.get("_type"));

    // o Patient precisa existir e ser visível (403 no contexto patient/ de outro paciente)
    StoredResource patient =
        tx.execute(tenant(), c -> repository.findCurrent(c, "Patient", patientId))
            .orElseThrow(() -> FhirException.notFound("Patient", patientId));
    if (patient.deleted()) {
      throw FhirException.gone("Patient", patientId);
    }
    enforceReadRestrictions(Interaction.READ, "Patient", patientId, codec.parse(patient.content()));

    final String aType = afterType;
    final String aId = afterId;
    final int limit = count;
    List<StoredResource> rows =
        tx.execute(
            tenant(),
            c ->
                repository.compartmentPage(
                    c, tenant(), patientId, types, since, aType, aId, limit + 1));
    boolean hasNext = rows.size() > limit;
    List<StoredResource> pageRows = hasNext ? rows.subList(0, limit) : rows;

    Bundle bundle = new Bundle();
    bundle.setType(BundleType.SEARCHSET);
    bundle.setTimestamp(new Date());
    bundle.addLink().setRelation("self").setUrl(baseUrl + "/Patient/" + patientId + "/$everything");
    List<String> touched = new ArrayList<>();
    Map<String, Boolean> typeAllowed = new LinkedHashMap<>();
    for (StoredResource r : pageRows) {
      boolean allowed =
          typeAllowed.computeIfAbsent(
              r.resourceType(),
              t ->
                  registry.supports(t, Interaction.READ)
                      && accessPolicy
                          .evaluate(
                              new AccessRequest(
                                  identity, Interaction.READ, t, null, context.purposeOfUse()))
                          .allowed());
      if (!allowed) {
        continue;
      }
      Resource resource = codec.parse(r.content());
      if (redaction.withhold(identity, resource)) {
        continue;
      }
      BundleEntryComponent entry = bundle.addEntry();
      entry.setFullUrl(baseUrl + "/" + r.resourceType() + "/" + r.id());
      entry.setResource(redact(resource));
      entry.getSearch().setMode(SearchEntryMode.MATCH);
      touched.add(r.resourceType() + "/" + r.id() + "/_history/" + r.versionId());
    }
    if (hasNext) {
      StoredResource last = pageRows.get(pageRows.size() - 1);
      Map<String, List<String>> nextParams = new TreeMap<>(params);
      nextParams.put("_page", List.of(String.valueOf(page + 1)));
      String next =
          cursor.encode(
              new SearchCursor.Payload(
                  EVERYTHING_CURSOR_TYPE + ":" + patientId,
                  nextParams,
                  last.resourceType() + "/" + last.id(),
                  limit,
                  null));
      bundle
          .addLink()
          .setRelation("next")
          .setUrl(baseUrl + "/Patient/" + patientId + "/$everything?_cursor=" + next);
    }
    audit.record(
        new AuditInput(
            identity,
            Interaction.READ,
            "Patient",
            patientId,
            null,
            context.purposeOfUse(),
            context.correlationId(),
            queryText(params),
            true,
            null,
            "everything",
            touched));
    return InteractionResult.ok(bundle);
  }

  private List<String> everythingTypes(List<String> typeParam) {
    List<String> types = new ArrayList<>();
    types.add("Patient");
    if (typeParam == null || typeParam.isEmpty()) {
      types.addAll(CapabilityRegistry.EVERYTHING_DEFAULT_TYPES);
      return types;
    }
    for (String raw : typeParam) {
      for (String t : raw.split(",")) {
        String type = t.trim();
        if (type.isEmpty()) {
          continue;
        }
        boolean compartmentType =
            "Patient".equals(type)
                || registry.searchParam(type, CapabilityRegistry.PATIENT_PARAM).isPresent();
        if (!registry.resource(type).isPresent() || !compartmentType) {
          throw FhirException.invalid("_type fora do compartimento do paciente: " + type, "_type");
        }
        if (!types.contains(type)) {
          types.add(type);
        }
      }
    }
    return types;
  }

  // ---- create / update / delete
  // -----------------------------------------------------------------------------

  public InteractionResult create(String type, String json, String baseUrl) {
    requireSupported(type, Interaction.CREATE);
    authorize(Interaction.CREATE, type, null);
    Resource resource = validation.parseOrThrow(json);
    validation.validateOrThrow(resource, type);
    return createValidated(type, resource, baseUrl, null);
  }

  /**
   * Cria um recurso já validado. {@code presetId} (Bundles: ids atribuídos antes da resolução de
   * {@code urn:uuid}) ou ULID novo. Parceiros com escopo de sistema em {@code Patient}/{@code
   * ServiceRequest} passam pela escrita direta → canônico antes de gravar.
   */
  public InteractionResult createValidated(
      String type, Resource resource, String baseUrl, String presetId) {
    ResourceCapability cap = requireSupported(type, Interaction.CREATE);
    authorize(Interaction.CREATE, type, null);
    if (directWrite.applies(type, context.identity())) {
      DirectWriteService.Outcome outcome = directWrite.register(type, resource, tenant());
      return storeDirect(type, cap, outcome, baseUrl);
    }
    String id = presetId != null ? presetId : IdGenerator.ulid();
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

  /**
   * Create condicional ({@code If-None-Exist: identifier=system|value}): nenhum existente → cria;
   * exatamente um → 200 com o existente; mais de um → 412.
   */
  public InteractionResult conditionalCreate(
      String type, Resource resource, String ifNoneExist, String baseUrl, String presetId) {
    requireSupported(type, Interaction.CREATE);
    authorize(Interaction.CREATE, type, null);
    Map<String, List<String>> params = parseQuery(ifNoneExist);
    if (params.size() != 1 || !params.containsKey("identifier")) {
      throw FhirException.invalid(
          "If-None-Exist suporta apenas identifier=system|value", "If-None-Exist");
    }
    SearchQuery query = applyPatientCompartment(type, searchParser.parse(type, params).query());
    List<StoredResource> matches = tx.execute(tenant(), c -> repository.search(c, query, tenant()));
    if (matches.size() > 1) {
      throw FhirException.conflict("If-None-Exist corresponde a mais de um recurso");
    }
    if (matches.size() == 1) {
      StoredResource existing = matches.get(0);
      Resource loaded = codec.parse(existing.content());
      enforceReadRestrictions(Interaction.READ, type, existing.id(), loaded);
      recordAudit(Interaction.READ, type, existing.id(), existing.versionId(), null, true, null);
      return new InteractionResult(
          200,
          redact(loaded),
          existing.etag(),
          existing.lastUpdated(),
          location(baseUrl, type, existing.id(), existing.versionId()));
    }
    return createValidated(type, resource, baseUrl, presetId);
  }

  static Map<String, List<String>> parseQuery(String query) {
    Map<String, List<String>> params = new TreeMap<>();
    if (query == null || query.isBlank()) {
      return params;
    }
    String q = query.startsWith("?") ? query.substring(1) : query;
    for (String pair : q.split("&")) {
      int eq = pair.indexOf('=');
      if (eq <= 0) {
        continue;
      }
      String key =
          java.net.URLDecoder.decode(
              pair.substring(0, eq), java.nio.charset.StandardCharsets.UTF_8);
      String value =
          java.net.URLDecoder.decode(
              pair.substring(eq + 1), java.nio.charset.StandardCharsets.UTF_8);
      params.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
    }
    return params;
  }

  public InteractionResult update(
      String type, String id, String json, String ifMatch, String baseUrl) {
    requireSupported(type, Interaction.UPDATE);
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
    return updateValidated(type, id, resource, ifMatch, baseUrl);
  }

  public InteractionResult updateValidated(
      String type, String id, Resource resource, String ifMatch, String baseUrl) {
    ResourceCapability cap = requireSupported(type, Interaction.UPDATE);
    authorize(Interaction.UPDATE, type, id);
    if (directWrite.applies(type, context.identity())) {
      DirectWriteService.Outcome outcome = directWrite.register(type, resource, tenant());
      if (!id.equals(outcome.id())) {
        throw new FhirException(
            422,
            IssueType.BUSINESSRULE,
            "O core resolveu o registro para outro id ("
                + type
                + "/"
                + outcome.id()
                + "); use POST ou o id resolvido",
            type + ".id");
      }
      return storeDirect(type, cap, outcome, baseUrl);
    }
    Stored outcome = upsert(type, cap, id, resource, ifMatch);
    recordAudit(
        outcome.created() ? Interaction.CREATE : Interaction.UPDATE,
        type,
        id,
        outcome.stored().versionId(),
        null,
        true,
        null);
    return new InteractionResult(
        outcome.created() ? 201 : 200,
        resource,
        outcome.stored().etag(),
        outcome.stored().lastUpdated(),
        location(baseUrl, type, id, outcome.stored().versionId()));
  }

  private record Stored(StoredResource stored, boolean created) {}

  private Stored upsert(
      String type, ResourceCapability cap, String id, Resource resource, String ifMatch) {
    return tx.execute(
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
            return new Stored(next, current.get().deleted());
          }
          if (ifMatch != null) {
            throw FhirException.conflict("If-Match informado para recurso inexistente");
          }
          StoredResource created = prepare(resource, id, 1);
          repository.insert(c, created, indexer.index(resource, cap));
          return new Stored(created, true);
        });
  }

  /**
   * {@code PATCH} (JSON Patch, RFC 6902) sobre a versão corrente: aplica o patch ao JSON armazenado
   * e segue o mesmo caminho do {@code PUT} (validação completa, {@code If-Match}, escrita direta
   * para parceiros). O patch não pode alterar {@code id} nem {@code resourceType}.
   */
  public InteractionResult patch(
      String type, String id, String patchJson, String ifMatch, String baseUrl) {
    requireSupported(type, Interaction.PATCH);
    authorize(Interaction.PATCH, type, id);
    StoredResource current =
        tx.execute(tenant(), c -> repository.findCurrent(c, type, id))
            .orElseThrow(() -> FhirException.notFound(type, id));
    if (current.deleted()) {
      throw FhirException.gone(type, id);
    }
    if (!allowedInPatientContext(type, codec.parse(current.content()))) {
      throw FhirException.forbidden("Acesso negado: patient-context-other-patient");
    }
    com.fasterxml.jackson.databind.ObjectMapper mapper =
        new com.fasterxml.jackson.databind.ObjectMapper();
    com.fasterxml.jackson.databind.JsonNode patched;
    try {
      patched = JsonPatch.apply(mapper.readTree(patchJson), mapper.readTree(current.content()));
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw FhirException.invalid("JSON Patch malformado");
    }
    if (!type.equals(patched.path("resourceType").asText())
        || !id.equals(patched.path("id").asText())) {
      throw new FhirException(
          422, IssueType.PROCESSING, "JSON Patch não pode alterar resourceType nem id");
    }
    if (patched instanceof com.fasterxml.jackson.databind.node.ObjectNode obj
        && obj.get("meta") instanceof com.fasterxml.jackson.databind.node.ObjectNode meta) {
      meta.remove("versionId");
      meta.remove("lastUpdated");
    }
    String effectiveIfMatch = ifMatch != null ? ifMatch : current.etag();
    return update(type, id, patched.toString(), effectiveIfMatch, baseUrl);
  }

  /** Grava o resultado da escrita direta (id resolvido pelo core) e responde conforme o core. */
  private InteractionResult storeDirect(
      String type, ResourceCapability cap, DirectWriteService.Outcome outcome, String baseUrl) {
    Resource resource = outcome.resource();
    Stored stored = upsert(type, cap, outcome.id(), resource, null);
    recordAudit(
        stored.created() ? Interaction.CREATE : Interaction.UPDATE,
        type,
        outcome.id(),
        stored.stored().versionId(),
        null,
        true,
        null);
    String location = location(baseUrl, type, outcome.id(), stored.stored().versionId());
    if (outcome.status() == 202) {
      OperationOutcome info =
          OperationOutcomes.single(
              IssueSeverity.INFORMATION,
              IssueType.INFORMATIONAL,
              "Identidade pendente de revisão no MPI; "
                  + type
                  + "/"
                  + outcome.id()
                  + " gravado com a tag pending-identity",
              null);
      return new InteractionResult(
          202, info, stored.stored().etag(), stored.stored().lastUpdated(), location);
    }
    return new InteractionResult(
        stored.created() ? 201 : 200,
        resource,
        stored.stored().etag(),
        stored.stored().lastUpdated(),
        location);
  }

  /**
   * Exclusão lógica: nova versão marcada como {@code deleted}; leituras passam a responder 410, o
   * histórico permanece e nada é apagado fisicamente (anonimização LGPD é processo separado).
   */
  public InteractionResult delete(String type, String id, String baseUrl) {
    requireSupported(type, Interaction.DELETE);
    authorize(Interaction.DELETE, type, id);
    Optional<StoredResource> deleted =
        tx.execute(
            tenant(),
            c -> {
              Optional<StoredResource> current = repository.findCurrentForUpdate(c, type, id);
              if (current.isEmpty()) {
                return Optional.empty();
              }
              StoredResource cur = current.get();
              if (cur.deleted()) {
                return current;
              }
              Resource loaded = codec.parse(cur.content());
              if (!allowedInPatientContext(type, loaded)) {
                throw FhirException.forbidden("Acesso negado: patient-context-other-patient");
              }
              Instant now = Instant.now();
              loaded.getMeta().setVersionId(String.valueOf(cur.versionId() + 1));
              loaded.getMeta().setLastUpdated(Date.from(now));
              StoredResource next =
                  new StoredResource(
                      id,
                      tenant(),
                      type,
                      cur.versionId() + 1,
                      now,
                      cur.profiles(),
                      codec.encode(loaded),
                      true);
              repository.markDeleted(c, next);
              return Optional.of(next);
            });
    if (deleted.isEmpty()) {
      recordAudit(Interaction.DELETE, type, id, null, null, false, "not-found");
      throw FhirException.notFound(type, id);
    }
    recordAudit(Interaction.DELETE, type, id, deleted.get().versionId(), null, true, null);
    return new InteractionResult(
        204, null, deleted.get().etag(), deleted.get().lastUpdated(), null);
  }

  // ---- $validate
  // -----------------------------------------------------------------------------------

  /**
   * {@code $validate}: aceita o recurso diretamente ou um {@code Parameters} com {@code resource}
   * e, opcionalmente, {@code profile} (canônico acrescentado a {@code meta.profile} antes da
   * validação; perfis desconhecidos são reportados como erro).
   */
  public OperationOutcome validate(String json, String expectedType) {
    if (expectedType != null) {
      requireKnownType(expectedType);
    }
    Resource parsed = validation.parseOrThrow(json);
    Resource resource = parsed;
    if (parsed instanceof Parameters params) {
      resource =
          params.getParameter().stream()
              .filter(p -> "resource".equals(p.getName()) && p.hasResource())
              .map(Parameters.ParametersParameterComponent::getResource)
              .findFirst()
              .orElseThrow(
                  () ->
                      FhirException.invalid(
                          "Parameters sem o parâmetro 'resource'", "Parameters.parameter"));
      for (var p : params.getParameter()) {
        if ("profile".equals(p.getName()) && p.hasValue()) {
          String profile = p.getValue().primitiveValue();
          boolean declared =
              resource.getMeta().getProfile().stream()
                  .map(CanonicalType::getValue)
                  .anyMatch(profile::equals);
          if (!declared) {
            resource.getMeta().addProfile(profile);
          }
        }
      }
    }
    List<ValidationIssue> issues = validation.validate(resource, expectedType);
    return issues.isEmpty() ? OperationOutcomes.allOk() : OperationOutcomes.fromIssues(issues);
  }

  // ---- helpers
  // -------------------------------------------------------------------------------------

  String tenant() {
    return context.tenantId();
  }

  ResourceCapability requireSupported(String type, Interaction interaction) {
    ResourceCapability cap = requireKnownType(type);
    if (!cap.supports(interaction)) {
      throw FhirException.methodNotAllowed(
          "Interação " + interaction.code() + " não suportada para " + type);
    }
    return cap;
  }

  ResourceCapability requireKnownType(String type) {
    return registry
        .resource(type)
        .orElseThrow(() -> FhirException.notSupported("Tipo de recurso não suportado: " + type));
  }

  void authorize(Interaction interaction, String type, String id) {
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

  Resource redact(Resource resource) {
    return redaction.apply(context.identity(), resource);
  }

  StoredResource prepare(Resource resource, String id, int version) {
    Instant now = Instant.now();
    resource.setId(id);
    resource.getMeta().setVersionId(String.valueOf(version));
    resource.getMeta().setLastUpdated(Date.from(now));
    List<String> profiles =
        resource.getMeta().getProfile().stream().map(CanonicalType::getValue).toList();
    return new StoredResource(
        id, tenant(), resource.fhirType(), version, now, profiles, codec.encode(resource), false);
  }

  static String location(String baseUrl, String type, String id, int version) {
    return baseUrl + "/" + type + "/" + id + "/_history/" + version;
  }

  static int parseCount(String raw, int max) {
    try {
      int n = Integer.parseInt(raw);
      if (n < 1) {
        throw FhirException.invalid("_count deve ser maior que zero", "_count");
      }
      return Math.min(n, max);
    } catch (NumberFormatException e) {
      throw FhirException.invalid("_count inválido", "_count");
    }
  }

  static Instant parseSince(List<String> values) {
    if (values == null || values.isEmpty()) {
      return null;
    }
    return FhirDates.parse(values.get(0))
        .map(FhirDates.Range::low)
        .orElseThrow(() -> FhirException.invalid("_since inválido", "_since"));
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

  void recordAudit(
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

  /** Auditoria de uma operação/Bundle com uma entidade por recurso tocado. */
  void recordOperationAudit(
      String operation,
      String type,
      String id,
      List<String> touched,
      boolean success,
      String reason) {
    audit.record(
        new AuditInput(
            context.identity(),
            Interaction.SEARCH_TYPE,
            type,
            id,
            null,
            context.purposeOfUse(),
            context.correlationId(),
            null,
            success,
            reason,
            operation,
            touched));
  }
}
