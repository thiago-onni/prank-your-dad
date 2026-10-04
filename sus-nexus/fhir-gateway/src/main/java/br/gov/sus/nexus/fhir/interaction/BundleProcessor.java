package br.gov.sus.nexus.fhir.interaction;

import br.gov.sus.nexus.fhir.codec.FhirCodec;
import br.gov.sus.nexus.fhir.persistence.TenantTransaction;
import br.gov.sus.nexus.fhir.security.RequestContext;
import br.gov.sus.nexus.fhir.validation.ValidationService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.hl7.fhir.r4.model.Base;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Bundle.BundleEntryComponent;
import org.hl7.fhir.r4.model.Bundle.BundleEntryResponseComponent;
import org.hl7.fhir.r4.model.Bundle.BundleType;
import org.hl7.fhir.r4.model.Bundle.HTTPVerb;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.Property;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;

/**
 * {@code POST [base]} com {@code Bundle} {@code batch} ou {@code transaction}.
 *
 * <ul>
 *   <li>{@code transaction}: todas as entradas em uma única transação do banco ({@link
 *       TenantTransaction#atomic}); qualquer falha reverte tudo e a resposta é o {@code
 *       OperationOutcome} da entrada que falhou (com o status HTTP correspondente). Ordem de
 *       processamento conforme a especificação: DELETE, POST, PUT, GET.
 *   <li>{@code batch}: cada entrada é independente; falhas ficam em {@code response.status} com
 *       {@code response.outcome}.
 *   <li>{@code fullUrl} {@code urn:uuid:…} de entradas POST/PUT recebem id antes do processamento e
 *       toda {@code Reference} do Bundle que aponte para o {@code urn:uuid} é reescrita para {@code
 *       Tipo/id}; {@code If-None-Exist} em POST faz create condicional por {@code identifier}.
 * </ul>
 *
 * <p>Um {@code AuditEvent} por Bundle com uma entidade por recurso tocado (além da auditoria de
 * cada interação individual); a auditoria grava em transação própria e sobrevive ao rollback.
 * Limitação: o conteúdo de um {@code Binary} criado dentro de uma transação revertida permanece no
 * object storage sem metadados (órfão inacessível pela API).
 */
@ApplicationScoped
public class BundleProcessor {

  @Inject FhirInteractionService interactions;
  @Inject BinaryInteractionService binaries;
  @Inject TenantTransaction tx;
  @Inject ValidationService validation;
  @Inject FhirCodec codec;
  @Inject RequestContext context;

  /** {@code Tipo}, {@code Tipo/id} ou {@code Tipo?query}. */
  private static final java.util.regex.Pattern RELATIVE_URL =
      java.util.regex.Pattern.compile(
          "^(?<type>[A-Z][A-Za-z]+)(?:/(?<id>[A-Za-z0-9\\-\\.]{1,64}))?(?:\\?(?<query>.*))?$");

  public Bundle process(String json, String baseUrl) {
    if (!context.identity().isAuthenticated()) {
      throw FhirException.unauthorized("Autenticação requerida");
    }
    if (!context.identity().hasTenant()) {
      throw FhirException.forbidden(
          "Tenant não identificado (claim municipality_id / X-Tenant-Id)");
    }
    Resource parsed = validation.parseOrThrow(json);
    if (!(parsed instanceof Bundle bundle)) {
      throw FhirException.invalid("POST [base] exige um Bundle batch ou transaction", "Bundle");
    }
    BundleType type = bundle.getType();
    if (type != BundleType.BATCH && type != BundleType.TRANSACTION) {
      throw FhirException.invalid(
          "Bundle.type deve ser batch ou transaction (recebido: "
              + (type == null ? "nenhum" : type.toCode())
              + ")",
          "Bundle.type");
    }
    for (BundleEntryComponent e : bundle.getEntry()) {
      if (!e.hasRequest() || !e.getRequest().hasMethod() || !e.getRequest().hasUrl()) {
        throw FhirException.invalid(
            "Toda entrada deve ter request.method e request.url", "Bundle.entry.request");
      }
    }
    Map<String, String> urnMap = assignIds(bundle);
    rewriteReferences(bundle, urnMap);

    List<Integer> order = processingOrder(bundle, type == BundleType.TRANSACTION);
    Bundle response = new Bundle();
    response.setType(
        type == BundleType.TRANSACTION ? BundleType.TRANSACTIONRESPONSE : BundleType.BATCHRESPONSE);
    response.setTimestamp(new Date());
    List<BundleEntryComponent> out = new ArrayList<>();
    for (int i = 0; i < bundle.getEntry().size(); i++) {
      out.add(new BundleEntryComponent());
    }
    List<String> touched = new ArrayList<>();
    String operation = type == BundleType.TRANSACTION ? "transaction" : "batch";

    if (type == BundleType.TRANSACTION) {
      try {
        tx.atomic(
            context.tenantId(),
            c -> {
              for (int idx : order) {
                BundleEntryComponent entry = bundle.getEntry().get(idx);
                out.set(idx, execute(entry, urnMap, baseUrl, touched));
              }
              return null;
            });
      } catch (FhirException e) {
        interactions.recordOperationAudit(operation, null, null, List.of(), false, e.getMessage());
        throw e;
      }
    } else {
      for (int idx : order) {
        BundleEntryComponent entry = bundle.getEntry().get(idx);
        try {
          out.set(idx, execute(entry, urnMap, baseUrl, touched));
        } catch (FhirException e) {
          out.set(idx, failed(e.status(), e.outcome()));
        }
      }
    }
    response.setEntry(out);
    interactions.recordOperationAudit(operation, null, null, touched, true, null);
    return response;
  }

  /** POST/PUT com {@code fullUrl urn:uuid} ganham id definitivo antes do processamento. */
  private static Map<String, String> assignIds(Bundle bundle) {
    Map<String, String> urnMap = new HashMap<>();
    for (BundleEntryComponent entry : bundle.getEntry()) {
      if (!entry.hasFullUrl() || !entry.getFullUrl().startsWith("urn:uuid:")) {
        continue;
      }
      HTTPVerb method = entry.getRequest().getMethod();
      java.util.regex.Matcher m = RELATIVE_URL.matcher(entry.getRequest().getUrl());
      if (!m.matches()) {
        continue;
      }
      String type = m.group("type");
      if (method == HTTPVerb.POST) {
        urnMap.put(entry.getFullUrl(), type + "/" + IdGenerator.ulid());
      } else if (method == HTTPVerb.PUT && m.group("id") != null) {
        urnMap.put(entry.getFullUrl(), type + "/" + m.group("id"));
      }
    }
    return urnMap;
  }

  private static void rewriteReferences(Bundle bundle, Map<String, String> urnMap) {
    if (urnMap.isEmpty()) {
      return;
    }
    for (BundleEntryComponent entry : bundle.getEntry()) {
      if (entry.hasResource()) {
        walk(
            entry.getResource(),
            ref -> {
              if (ref.hasReference() && urnMap.containsKey(ref.getReference())) {
                ref.setReference(urnMap.get(ref.getReference()));
              }
            });
      }
    }
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

  private static List<Integer> processingOrder(Bundle bundle, boolean transaction) {
    List<Integer> order = new ArrayList<>();
    if (!transaction) {
      for (int i = 0; i < bundle.getEntry().size(); i++) {
        order.add(i);
      }
      return order;
    }
    for (HTTPVerb verb : List.of(HTTPVerb.DELETE, HTTPVerb.POST, HTTPVerb.PUT, HTTPVerb.GET)) {
      for (int i = 0; i < bundle.getEntry().size(); i++) {
        if (bundle.getEntry().get(i).getRequest().getMethod() == verb) {
          order.add(i);
        }
      }
    }
    for (int i = 0; i < bundle.getEntry().size(); i++) {
      if (!order.contains(i)) {
        order.add(i);
      }
    }
    return order;
  }

  private BundleEntryComponent execute(
      BundleEntryComponent entry,
      Map<String, String> urnMap,
      String baseUrl,
      List<String> touched) {
    HTTPVerb method = entry.getRequest().getMethod();
    java.util.regex.Matcher m = RELATIVE_URL.matcher(entry.getRequest().getUrl());
    if (!m.matches()) {
      throw FhirException.invalid(
          "request.url inválida (esperado Tipo, Tipo/id ou Tipo?query): "
              + entry.getRequest().getUrl(),
          "Bundle.entry.request.url");
    }
    String type = m.group("type");
    String id = m.group("id");
    String query = m.group("query");
    InteractionResult result;
    switch (method) {
      case GET -> {
        if (id != null) {
          result = interactions.read(type, id, null);
        } else {
          Map<String, List<String>> params =
              new TreeMap<>(FhirInteractionService.parseQuery(query));
          result =
              interactions.search(
                  type, params, baseUrl, baseUrl + "/" + type + "?" + (query == null ? "" : query));
        }
      }
      case DELETE -> {
        if (id == null) {
          throw FhirException.invalid("DELETE exige Tipo/id", "Bundle.entry.request.url");
        }
        result = interactions.delete(type, id, baseUrl);
      }
      case POST -> {
        Resource resource = requireResource(entry, type);
        if ("Binary".equals(type)) {
          result = binaries.create(codec.encode(resource), baseUrl);
        } else {
          validation.validateOrThrow(resource, type);
          String preset =
              Optional.ofNullable(entry.getFullUrl())
                  .map(urnMap::get)
                  .map(ref -> ref.substring(ref.indexOf('/') + 1))
                  .orElse(null);
          if (entry.getRequest().hasIfNoneExist()) {
            result =
                interactions.conditionalCreate(
                    type, resource, entry.getRequest().getIfNoneExist(), baseUrl, preset);
          } else {
            result = interactions.createValidated(type, resource, baseUrl, preset);
          }
        }
      }
      case PUT -> {
        if (id == null) {
          throw FhirException.invalid("PUT exige Tipo/id", "Bundle.entry.request.url");
        }
        Resource resource = requireResource(entry, type);
        if (resource.hasId() && !id.equals(resource.getIdElement().getIdPart())) {
          throw FhirException.invalid("id do recurso difere de request.url", type + ".id");
        }
        validation.validateOrThrow(resource, type);
        result =
            interactions.updateValidated(
                type, id, resource, entry.getRequest().getIfMatch(), baseUrl);
      }
      default ->
          throw FhirException.methodNotAllowed(
              "Método não suportado em Bundle: " + method.toCode());
    }
    BundleEntryComponent out = new BundleEntryComponent();
    BundleEntryResponseComponent resp = out.getResponse();
    resp.setStatus(statusLine(result.status()));
    if (result.location() != null) {
      resp.setLocation(result.location());
      touched.add(result.location().substring(baseUrl.length() + 1));
    } else if (id != null) {
      touched.add(type + "/" + id);
    }
    if (result.etag() != null) {
      resp.setEtag(result.etag());
    }
    if (result.lastModified() != null) {
      resp.setLastModified(Date.from(result.lastModified()));
    }
    if (result.body() != null) {
      if (result.body() instanceof OperationOutcome oo) {
        resp.setOutcome(oo);
      } else {
        out.setResource(result.body());
        if (result.location() != null) {
          out.setFullUrl(result.location().substring(0, result.location().indexOf("/_history/")));
        }
      }
    }
    return out;
  }

  private Resource requireResource(BundleEntryComponent entry, String type) {
    if (!entry.hasResource()) {
      throw FhirException.invalid("Entrada POST/PUT sem resource", "Bundle.entry.resource");
    }
    Resource resource = entry.getResource();
    if (!type.equals(resource.fhirType())) {
      throw FhirException.invalid(
          "Tipo do recurso (" + resource.fhirType() + ") difere de request.url (" + type + ")",
          "Bundle.entry.resource");
    }
    return resource;
  }

  private static BundleEntryComponent failed(int status, OperationOutcome outcome) {
    BundleEntryComponent out = new BundleEntryComponent();
    out.getResponse().setStatus(statusLine(status)).setOutcome(outcome);
    return out;
  }

  static String statusLine(int status) {
    return switch (status) {
      case 200 -> "200 OK";
      case 201 -> "201 Created";
      case 202 -> "202 Accepted";
      case 204 -> "204 No Content";
      case 304 -> "304 Not Modified";
      case 400 -> "400 Bad Request";
      case 401 -> "401 Unauthorized";
      case 403 -> "403 Forbidden";
      case 404 -> "404 Not Found";
      case 405 -> "405 Method Not Allowed";
      case 410 -> "410 Gone";
      case 412 -> "412 Precondition Failed";
      case 422 -> "422 Unprocessable Entity";
      default -> String.valueOf(status);
    };
  }
}
