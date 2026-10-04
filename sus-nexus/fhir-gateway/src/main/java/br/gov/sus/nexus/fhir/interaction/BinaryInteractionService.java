package br.gov.sus.nexus.fhir.interaction;

import br.gov.sus.nexus.fhir.FhirConstants;
import br.gov.sus.nexus.fhir.binary.BinaryStorage;
import br.gov.sus.nexus.fhir.capability.Interaction;
import br.gov.sus.nexus.fhir.capability.ResourceCapability;
import br.gov.sus.nexus.fhir.codec.FhirCodec;
import br.gov.sus.nexus.fhir.config.FhirGatewayConfig;
import br.gov.sus.nexus.fhir.persistence.FhirResourceRepository;
import br.gov.sus.nexus.fhir.persistence.FhirResourceRepository.BinaryMeta;
import br.gov.sus.nexus.fhir.persistence.SearchIndexer;
import br.gov.sus.nexus.fhir.persistence.StoredResource;
import br.gov.sus.nexus.fhir.persistence.TenantTransaction;
import br.gov.sus.nexus.fhir.validation.ValidationService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.hl7.fhir.r4.model.Binary;
import org.hl7.fhir.r4.model.IntegerType;
import org.hl7.fhir.r4.model.OperationOutcome.IssueType;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.StringType;

/**
 * {@code Binary}: o conteúdo ({@code data}) vai para o {@link BinaryStorage}; no banco ficam o
 * recurso sem {@code data} (contentType, securityContext → Patient, extensões sha256/tamanho) e os
 * metadados em {@code fhir.fhir_binary}. Leitura exige escopo explícito {@code Binary.read},
 * respeita o compartimento do paciente ({@code securityContext}) e gera {@code AuditEvent}.
 */
@ApplicationScoped
public class BinaryInteractionService {

  @Inject FhirInteractionService interactions;
  @Inject BinaryStorage storage;
  @Inject TenantTransaction tx;
  @Inject FhirResourceRepository repository;
  @Inject SearchIndexer indexer;
  @Inject FhirCodec codec;
  @Inject ValidationService validation;
  @Inject FhirGatewayConfig config;

  /** Resultado de leitura: recurso com {@code data} preenchido e conteúdo bruto para streaming. */
  public record BinaryRead(
      Binary resource, byte[] content, String contentType, String etag, java.time.Instant lastModified) {}

  public InteractionResult create(String json, String baseUrl) {
    ResourceCapability cap = interactions.requireSupported("Binary", Interaction.CREATE);
    interactions.authorize(Interaction.CREATE, "Binary", null);
    Resource parsed = validation.parseOrThrow(json);
    validation.validateOrThrow(parsed, "Binary");
    Binary binary = (Binary) parsed;
    if (!binary.hasData() || binary.getData().length == 0) {
      throw new FhirException(422, IssueType.REQUIRED, "Binary.data obrigatório", "Binary.data");
    }
    if (!binary.hasContentType()) {
      throw new FhirException(
          422, IssueType.REQUIRED, "Binary.contentType obrigatório", "Binary.contentType");
    }
    byte[] content = binary.getData();
    if (content.length > config.binary().maxBytes()) {
      throw new FhirException(
          422,
          IssueType.TOOCOSTLY,
          "Binary excede o tamanho máximo (" + config.binary().maxBytes() + " bytes)",
          "Binary.data");
    }
    String id = IdGenerator.ulid();
    String tenant = interactions.tenant();
    String sha256 = sha256(content);
    String key = storage.put(tenant, id, binary.getContentType(), content);

    Binary stored = binary.copy();
    stored.setData(null);
    stored.addExtension(FhirConstants.EXT_BINARY_SHA256, new StringType(sha256));
    stored.addExtension(FhirConstants.EXT_BINARY_SIZE, new IntegerType(content.length));
    StoredResource row = interactions.prepare(stored, id, 1);
    var index = indexer.index(stored, cap);
    tx.execute(
        tenant,
        c -> {
          repository.insert(c, row, index);
          repository.insertBinary(
              c,
              new BinaryMeta(id, tenant, binary.getContentType(), content.length, sha256, key));
          return null;
        });
    interactions.recordAudit(Interaction.CREATE, "Binary", id, 1, null, true, null);
    return new InteractionResult(
        201,
        stored,
        row.etag(),
        row.lastUpdated(),
        FhirInteractionService.location(baseUrl, "Binary", id, 1));
  }

  public BinaryRead read(String id) {
    interactions.requireSupported("Binary", Interaction.READ);
    interactions.authorize(Interaction.READ, "Binary", id);
    String tenant = interactions.tenant();
    StoredResource row =
        tx.execute(tenant, c -> repository.findCurrent(c, "Binary", id))
            .orElseThrow(() -> FhirException.notFound("Binary", id));
    if (row.deleted()) {
      throw FhirException.gone("Binary", id);
    }
    Binary binary = (Binary) codec.parse(row.content());
    interactions.enforceReadRestrictions(Interaction.READ, "Binary", id, binary);
    Optional<BinaryMeta> meta = tx.execute(tenant, c -> repository.findBinary(c, id));
    byte[] content =
        meta.flatMap(m -> storage.get(m.key()))
            .orElseThrow(
                () ->
                    new FhirException(
                        500, IssueType.EXCEPTION, "Conteúdo do Binary indisponível no storage"));
    Binary out = binary.copy();
    out.setData(content);
    interactions.recordAudit(Interaction.READ, "Binary", id, row.versionId(), null, true, null);
    return new BinaryRead(out, content, binary.getContentType(), row.etag(), row.lastUpdated());
  }

  static String sha256(byte[] content) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Lista imutável vazia (sem índice) para recursos sem parâmetros registrados. */
  static List<Object> none() {
    return List.of();
  }
}
