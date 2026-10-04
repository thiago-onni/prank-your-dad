package br.gov.sus.nexus.core.consent.application;

import br.gov.sus.nexus.core.consent.api.CommunicationPreferenceDto;
import br.gov.sus.nexus.core.consent.api.ConsentDto;
import br.gov.sus.nexus.core.consent.api.ConsentRecord;
import br.gov.sus.nexus.core.consent.api.ConsentService;
import br.gov.sus.nexus.core.consent.api.PreferenceUpsert;
import br.gov.sus.nexus.core.consent.domain.CommunicationPreference;
import br.gov.sus.nexus.core.consent.domain.Consent;
import br.gov.sus.nexus.core.consent.infrastructure.ConsentRepositories;
import br.gov.sus.nexus.core.identity.api.CitizenDetail;
import br.gov.sus.nexus.core.identity.api.CitizenService;
import br.gov.sus.nexus.core.platform.ids.Ulid;
import br.gov.sus.nexus.core.platform.security.CurrentActor;
import br.gov.sus.nexus.core.platform.tenant.TenantContext;
import br.gov.sus.nexus.core.platform.tenant.TenantTransactional;
import br.gov.sus.nexus.core.sharedkernel.IdentifierHash;
import br.gov.sus.nexus.core.sharedkernel.Masks;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/** Consentimentos e preferências de comunicação (sem valores em claro). */
@ApplicationScoped
public class ConsentServiceImpl implements ConsentService {

  @Inject ConsentRepositories.Consents consents;
  @Inject ConsentRepositories.Preferences preferences;
  @Inject CitizenService citizens;
  @Inject IdentifierHash hasher;
  @Inject TenantContext tenantContext;
  @Inject CurrentActor currentActor;

  @Override
  @TenantTransactional
  public ConsentDto record(ConsentRecord r) {
    Consent c = new Consent();
    c.id = Ulid.generate(Ulid.CONSENT);
    c.tenantId = tenantContext.require();
    c.citizenId = r.citizenId();
    c.purpose = r.purpose().trim().toLowerCase();
    c.status = r.status();
    c.channel = r.channel();
    c.recordedAt = r.recordedAt() == null ? Instant.now() : r.recordedAt().toInstant();
    c.source = r.source();
    c.actorId = currentActor.actorId();
    consents.persist(c);
    return toDto(c);
  }

  @Override
  @TenantTransactional
  public CommunicationPreferenceDto setPreference(PreferenceUpsert up) {
    String tenant = tenantContext.require();
    String value = up.value() == null || up.value().isBlank() ? null : up.value().trim();
    String hash = value == null ? null : hasher.hash(tenant, "CONTACT:" + up.channel(), value);
    CommunicationPreference p =
        preferences
            .lookup(up.citizenId(), up.channel(), hash)
            .orElseGet(
                () -> {
                  CommunicationPreference n = new CommunicationPreference();
                  n.id = Ulid.generate(Ulid.COMMUNICATION_PREFERENCE);
                  n.tenantId = tenant;
                  n.citizenId = up.citizenId();
                  n.channel = up.channel();
                  n.valueHash = hash;
                  n.valueMasked = value == null ? null : mask(up.channel(), value);
                  n.createdAt = Instant.now();
                  preferences.persist(n);
                  return n;
                });
    p.preferred = up.preferred();
    p.allowed = up.allowed();
    p.source = up.source();
    p.updatedAt = Instant.now();
    return toDto(p);
  }

  @Override
  @TenantTransactional
  public List<ConsentDto> consents(String citizenId) {
    return consents.byCitizen(citizenId).stream().map(ConsentServiceImpl::toDto).toList();
  }

  @Override
  @TenantTransactional
  public List<CommunicationPreferenceDto> preferences(String citizenId) {
    return preferences.byCitizen(citizenId).stream().map(ConsentServiceImpl::toDto).toList();
  }

  @Override
  @TenantTransactional
  public boolean contactValid(String citizenId) {
    Optional<Consent> communication =
        consents.latest(citizenId, ConsentRecord.PURPOSE_COMMUNICATION);
    if (communication.isPresent() && "revoked".equals(communication.get().status)) {
      return false;
    }
    List<CommunicationPreference> prefs = preferences.byCitizen(citizenId);
    if (!prefs.isEmpty()) {
      return prefs.stream().anyMatch(p -> p.allowed && p.valueHash != null);
    }
    try {
      CitizenDetail c = citizens.get(citizenId);
      return c.contacts() != null && !c.contacts().isEmpty();
    } catch (RuntimeException e) {
      return false;
    }
  }

  private static String mask(String channel, String value) {
    return "email".equals(channel) ? Masks.email(value) : Masks.phone(value);
  }

  private static ConsentDto toDto(Consent c) {
    return new ConsentDto(
        c.id, c.citizenId, c.purpose, c.status, c.channel, offset(c.recordedAt), c.source);
  }

  private static CommunicationPreferenceDto toDto(CommunicationPreference p) {
    return new CommunicationPreferenceDto(
        p.id,
        p.citizenId,
        p.channel,
        p.valueMasked,
        p.preferred,
        p.allowed,
        p.source,
        offset(p.updatedAt));
  }

  private static OffsetDateTime offset(Instant i) {
    return i == null ? null : i.atOffset(ZoneOffset.UTC);
  }
}
