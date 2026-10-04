package br.gov.sus.nexus.core.identity.application;

import br.gov.sus.nexus.core.identity.api.CitizenDetail;
import br.gov.sus.nexus.core.identity.api.CitizenSummary;
import br.gov.sus.nexus.core.identity.api.IdentifierSystem;
import br.gov.sus.nexus.core.identity.api.IdentityConfidence;
import br.gov.sus.nexus.core.identity.api.MaskedIdentifier;
import br.gov.sus.nexus.core.identity.api.RegistrationState;
import br.gov.sus.nexus.core.identity.api.Sex;
import br.gov.sus.nexus.core.identity.domain.Citizen;
import br.gov.sus.nexus.core.identity.domain.CitizenAddress;
import br.gov.sus.nexus.core.identity.domain.CitizenContact;
import br.gov.sus.nexus.core.identity.domain.CitizenGoldenRecordAttribute;
import br.gov.sus.nexus.core.identity.domain.CitizenIdentifier;
import br.gov.sus.nexus.core.identity.infrastructure.CitizenAddressRepository;
import br.gov.sus.nexus.core.identity.infrastructure.CitizenContactRepository;
import br.gov.sus.nexus.core.identity.infrastructure.CitizenIdentifierRepository;
import br.gov.sus.nexus.core.identity.infrastructure.GoldenRecordRepository;
import br.gov.sus.nexus.core.sharedkernel.Masks;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Converte entidades em DTOs públicos (identificadores sempre mascarados). */
@ApplicationScoped
public class CitizenMapper {

  @Inject CitizenIdentifierRepository identifierRepository;
  @Inject CitizenAddressRepository addressRepository;
  @Inject CitizenContactRepository contactRepository;
  @Inject GoldenRecordRepository goldenRecordRepository;

  public CitizenSummary summary(Citizen c) {
    return summary(c, identifierRepository.findByCitizen(c.id));
  }

  public CitizenSummary summary(Citizen c, List<CitizenIdentifier> identifiers) {
    return new CitizenSummary(
        c.id,
        displayName(c),
        c.birthdate,
        Sex.fromWire(c.sex),
        Masks.name(c.motherName),
        RegistrationState.fromWire(c.registrationState),
        identifiers.stream().map(CitizenMapper::masked).toList(),
        c.healthUnitCnes,
        c.teamIne,
        c.microarea,
        IdentityConfidence.fromWire(c.identityConfidence));
  }

  public CitizenDetail detail(Citizen c) {
    List<CitizenIdentifier> identifiers = identifierRepository.findByCitizen(c.id);
    CitizenAddress addr = addressRepository.findCurrent(c.id).orElse(null);
    List<CitizenContact> contacts = contactRepository.findByCitizen(c.id);
    Map<String, CitizenDetail.Provenance> provenance = new LinkedHashMap<>();
    for (CitizenGoldenRecordAttribute a : goldenRecordRepository.findByCitizen(c.id)) {
      provenance.put(
          a.attribute,
          new CitizenDetail.Provenance(
              a.sourceSystem,
              a.sourceRecordId,
              a.receivedAt.atOffset(ZoneOffset.UTC),
              a.confidence));
    }
    List<CitizenDetail.DataQualityIssue> issues = new ArrayList<>();
    for (CitizenIdentifier ci : identifiers) {
      if (CitizenIdentifier.STATUS_INVALID.equals(ci.status)) {
        issues.add(
            new CitizenDetail.DataQualityIssue(
                "identifier.invalid_or_conflicting",
                ci.system,
                "identificador " + ci.system + " inválido ou pertencente a outro cidadão"));
      }
    }
    if (c.motherName == null) {
      issues.add(
          new CitizenDetail.DataQualityIssue(
              "demographics.mother_name_missing", "mother_name", "nome da mãe ausente"));
    }
    return new CitizenDetail(
        c.id,
        displayName(c),
        c.birthdate,
        Sex.fromWire(c.sex),
        Masks.name(c.motherName),
        RegistrationState.fromWire(c.registrationState),
        identifiers.stream().map(CitizenMapper::masked).toList(),
        c.healthUnitCnes,
        c.teamIne,
        c.microarea,
        IdentityConfidence.fromWire(c.identityConfidence),
        c.version,
        c.legalName,
        c.socialName,
        c.motherName,
        addr == null
            ? null
            : new CitizenDetail.Address(
                addr.street,
                addr.number,
                addr.complement,
                addr.district,
                addr.cityIbge,
                addr.postalCode),
        contacts.stream()
            .map(ct -> new CitizenDetail.Contact(ct.kind, ct.valueMasked, ct.preferred))
            .toList(),
        provenance,
        issues,
        c.mergedIntoId);
  }

  static MaskedIdentifier masked(CitizenIdentifier ci) {
    return new MaskedIdentifier(
        ci.id,
        IdentifierSystem.valueOf(ci.system),
        ci.valueMasked,
        ci.status,
        ci.sourceSystem,
        ci.validFrom.atOffset(ZoneOffset.UTC));
  }

  static String displayName(Citizen c) {
    return c.socialName != null && !c.socialName.isBlank() ? c.socialName : c.legalName;
  }
}
