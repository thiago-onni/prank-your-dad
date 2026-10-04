package br.gov.sus.nexus.core.identity.application;

import br.gov.sus.nexus.core.identity.api.CitizenRegistration;
import br.gov.sus.nexus.core.identity.api.Sex;
import br.gov.sus.nexus.core.identity.domain.Citizen;
import br.gov.sus.nexus.core.identity.domain.CitizenContact;
import br.gov.sus.nexus.core.identity.domain.CitizenIdentifier;
import br.gov.sus.nexus.core.identity.domain.ContactNormalizer;
import br.gov.sus.nexus.core.identity.domain.NameNormalizer;
import br.gov.sus.nexus.core.identity.domain.PersonFeatures;
import br.gov.sus.nexus.core.identity.infrastructure.CitizenContactRepository;
import br.gov.sus.nexus.core.identity.infrastructure.CitizenIdentifierRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Extrai {@link PersonFeatures} do registro de entrada e de cidadãos persistidos. */
@ApplicationScoped
public class FeatureExtractor {

  @Inject CitizenIdentifierRepository identifierRepository;
  @Inject CitizenContactRepository contactRepository;

  public PersonFeatures fromRegistration(
      CitizenRegistration reg, List<IdentifierCodec.Parsed> validIdentifiers) {
    CitizenRegistration.Demographics d = reg.demographics();
    Map<String, String> hashes = new HashMap<>();
    for (IdentifierCodec.Parsed p : validIdentifiers) {
      hashes.putIfAbsent(p.systemName(), p.hash());
    }
    Set<String> phones = new HashSet<>();
    if (reg.contacts() != null) {
      for (CitizenRegistration.ContactInput c : reg.contacts()) {
        if (c != null && !"email".equalsIgnoreCase(c.kind())) {
          String n = ContactNormalizer.phone(c.value());
          if (n != null) {
            phones.add(n);
          }
        }
      }
    }
    Sex sex = d.sex() == null ? Sex.UNKNOWN : d.sex();
    return new PersonFeatures(
        null,
        NameNormalizer.normalize(d.legalName()),
        NameNormalizer.normalize(d.motherName()),
        d.birthdate(),
        sex.wire(),
        phones,
        hashes);
  }

  public PersonFeatures fromCitizen(Citizen c) {
    Map<String, String> hashes = new HashMap<>();
    for (CitizenIdentifier ci : identifierRepository.findByCitizen(c.id)) {
      if (CitizenIdentifier.STATUS_ACTIVE.equals(ci.status)) {
        hashes.putIfAbsent(ci.system, ci.valueHash);
      }
    }
    Set<String> phones = new HashSet<>();
    for (CitizenContact ct : contactRepository.findByCitizen(c.id)) {
      if (!"email".equalsIgnoreCase(ct.kind)) {
        phones.add(ct.valueNorm);
      }
    }
    return new PersonFeatures(
        c.id, c.normalizedName, c.normalizedMotherName, c.birthdate, c.sex, phones, hashes);
  }
}
