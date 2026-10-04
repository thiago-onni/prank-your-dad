package br.gov.sus.nexus.core.identity.application;

import br.gov.sus.nexus.core.audit.api.AccessLogService;
import br.gov.sus.nexus.core.audit.api.AccessRecord;
import br.gov.sus.nexus.core.audit.api.AuditEntry;
import br.gov.sus.nexus.core.audit.api.AuditService;
import br.gov.sus.nexus.core.identity.api.CitizenDetail;
import br.gov.sus.nexus.core.identity.api.CitizenRegistration;
import br.gov.sus.nexus.core.identity.api.CitizenService;
import br.gov.sus.nexus.core.identity.api.CitizenSummary;
import br.gov.sus.nexus.core.identity.api.IdentifierSystem;
import br.gov.sus.nexus.core.identity.api.IdentityResolution;
import br.gov.sus.nexus.core.identity.api.RegistrationState;
import br.gov.sus.nexus.core.identity.api.Requests;
import br.gov.sus.nexus.core.identity.domain.Citizen;
import br.gov.sus.nexus.core.identity.domain.CitizenIdentifier;
import br.gov.sus.nexus.core.identity.domain.NameNormalizer;
import br.gov.sus.nexus.core.identity.infrastructure.CitizenIdentifierRepository;
import br.gov.sus.nexus.core.identity.infrastructure.CitizenRepository;
import br.gov.sus.nexus.core.platform.correlation.CorrelationId;
import br.gov.sus.nexus.core.platform.errors.DomainValidationException;
import br.gov.sus.nexus.core.platform.errors.NotFoundException;
import br.gov.sus.nexus.core.platform.pagination.Cursor;
import br.gov.sus.nexus.core.platform.pagination.Page;
import br.gov.sus.nexus.core.platform.security.AuthorizationPolicy;
import br.gov.sus.nexus.core.platform.security.CurrentActor;
import br.gov.sus.nexus.core.platform.tenant.TenantContext;
import br.gov.sus.nexus.core.platform.tenant.TenantTransactional;
import io.quarkus.security.ForbiddenException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Consulta, busca e revelação de identificadores; registro delega ao pipeline do MPI. */
@ApplicationScoped
public class CitizenServiceImpl implements CitizenService {

  private static final String OFFSET_PREFIX = "off:";

  @Inject IdentityResolutionService resolution;
  @Inject CitizenRepository citizens;
  @Inject CitizenIdentifierRepository identifiers;
  @Inject CitizenMapper mapper;
  @Inject IdentifierCodec codec;
  @Inject TenantContext tenantContext;
  @Inject CurrentActor currentActor;
  @Inject CorrelationId correlationId;
  @Inject AccessLogService accessLog;
  @Inject AuditService audit;
  @Inject AuthorizationPolicy policy;

  @Override
  public IdentityResolution register(CitizenRegistration registration) {
    return resolution.register(registration);
  }

  @Override
  @TenantTransactional
  public CitizenDetail get(String citizenId) {
    Citizen c = citizens.findById(citizenId);
    if (c == null) {
      throw new NotFoundException("cidadão", citizenId);
    }
    return mapper.detail(c);
  }

  @Override
  @TenantTransactional
  public Page<CitizenSummary> search(
      String q,
      String identifier,
      LocalDate birthdate,
      RegistrationState registrationState,
      String cursor,
      Integer limit) {
    int size = Cursor.limit(limit);
    String state = registrationState == null ? null : registrationState.wire();

    if (identifier != null && !identifier.isBlank()) {
      return searchByIdentifier(identifier, size);
    }
    String normalized = NameNormalizer.normalize(q);
    if (normalized != null) {
      int offset = Cursor.decode(cursor).map(CitizenServiceImpl::offsetOf).orElse(0);
      List<Citizen> rows = citizens.searchByText(normalized, birthdate, state, offset, size + 1);
      List<CitizenSummary> items = rows.stream().limit(size).map(mapper::summary).toList();
      String next = rows.size() > size ? Cursor.encode(OFFSET_PREFIX + (offset + size)) : null;
      return new Page<>(items, next);
    }
    String afterId = Cursor.decode(cursor).orElse(null);
    List<CitizenSummary> rows =
        citizens.listKeyset(birthdate, state, afterId, size + 1).stream()
            .map(mapper::summary)
            .toList();
    return Page.of(rows, size, CitizenSummary::id);
  }

  private Page<CitizenSummary> searchByIdentifier(String identifier, int size) {
    int bar = identifier.indexOf('|');
    if (bar <= 0 || bar == identifier.length() - 1) {
      throw DomainValidationException.field("identifier", "formato esperado SISTEMA|valor");
    }
    String system = identifier.substring(0, bar).trim().toUpperCase();
    String value = identifier.substring(bar + 1).trim();
    try {
      IdentifierSystem.valueOf(system);
    } catch (IllegalArgumentException e) {
      throw DomainValidationException.field("identifier", "sistema desconhecido: " + system);
    }
    String hash = codec.hash(tenantContext.require(), system, value);
    Map<String, Citizen> found = new LinkedHashMap<>();
    for (CitizenIdentifier ci : identifiers.findByHash(tenantContext.require(), system, hash)) {
      Citizen c = citizens.findById(ci.citizenId);
      if (c != null) {
        found.putIfAbsent(c.id, c);
      }
    }
    List<CitizenSummary> items = found.values().stream().limit(size).map(mapper::summary).toList();
    return new Page<>(items, null);
  }

  @Override
  @TenantTransactional
  public Requests.Revealed reveal(String citizenId, String identifierId, Requests.Reveal req) {
    Citizen c = citizens.findById(citizenId);
    if (c == null) {
      throw new NotFoundException("cidadão", citizenId);
    }
    CitizenIdentifier ci =
        identifiers
            .findByIdAndCitizen(identifierId, citizenId)
            .orElseThrow(() -> new NotFoundException("identificador", identifierId));

    AuthorizationPolicy.Decision decision =
        policy.evaluate(
            new AuthorizationPolicy.Input(
                tenantContext.require(),
                currentActor.actorId(),
                currentActor.roles(),
                "citizen:reveal_identifier",
                "citizen_identifier",
                identifierId,
                req.purpose(),
                Map.of("citizen_id", citizenId, "system", ci.system)));
    accessLog.record(
        new AccessRecord(
            currentActor.actorId(),
            currentActor.roles(),
            "reveal_identifier",
            "citizen_identifier",
            identifierId,
            citizenId,
            req.purpose(),
            decision.allowed(),
            currentActor.breakGlass(),
            req.justification(),
            correlationId.get()));
    if (!decision.allowed()) {
      throw new ForbiddenException(decision.reason());
    }
    audit.record(
        AuditEntry.of("identifier.revealed", "citizen_identifier", identifierId, citizenId)
            .withReason(req.justification())
            .withDetails(Map.of("system", ci.system, "purpose", req.purpose().wire())));
    return new Requests.Revealed(IdentifierSystem.valueOf(ci.system), codec.decrypt(ci));
  }

  private static int offsetOf(String decoded) {
    if (!decoded.startsWith(OFFSET_PREFIX)) {
      throw DomainValidationException.field("cursor", "cursor inválido para busca textual");
    }
    try {
      return Math.max(0, Integer.parseInt(decoded.substring(OFFSET_PREFIX.length())));
    } catch (NumberFormatException e) {
      throw DomainValidationException.field("cursor", "cursor inválido");
    }
  }
}
