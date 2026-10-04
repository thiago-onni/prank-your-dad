package br.gov.sus.nexus.core.identity.application;

import br.gov.sus.nexus.core.identity.api.CitizenRegistration;
import br.gov.sus.nexus.core.identity.api.IdentifierSystem;
import br.gov.sus.nexus.core.identity.domain.CitizenIdentifier;
import br.gov.sus.nexus.core.platform.ids.Ulid;
import br.gov.sus.nexus.core.platform.security.FieldCipher;
import br.gov.sus.nexus.core.sharedkernel.Cns;
import br.gov.sus.nexus.core.sharedkernel.Cpf;
import br.gov.sus.nexus.core.sharedkernel.IdentifierHash;
import br.gov.sus.nexus.core.sharedkernel.Masks;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Valida, normaliza, mascara, cifra e calcula hash de identificadores. */
@ApplicationScoped
public class IdentifierCodec {

  /** Identificador de entrada já processado. */
  public record Parsed(
      IdentifierSystem system,
      String normalized,
      boolean valid,
      String hash,
      String masked,
      String issue) {
    public String systemName() {
      return system.name();
    }
  }

  @Inject IdentifierHash hasher;
  @Inject FieldCipher cipher;

  public List<Parsed> parse(String tenantId, List<CitizenRegistration.IdentifierInput> inputs) {
    List<Parsed> out = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    if (inputs == null) {
      return out;
    }
    for (CitizenRegistration.IdentifierInput in : inputs) {
      if (in == null || in.system() == null || in.value() == null || in.value().isBlank()) {
        continue;
      }
      String normalized = IdentifierHash.normalize(in.system().name(), in.value());
      if (!seen.add(in.system().name() + ":" + normalized)) {
        continue;
      }
      String issue = validate(in.system(), normalized);
      String hash = hasher.hash(tenantId, in.system().name(), normalized);
      String masked = Masks.forSystem(in.system().name(), normalized);
      out.add(new Parsed(in.system(), normalized, issue == null, hash, masked, issue));
    }
    return out;
  }

  public String hash(String tenantId, String system, String rawValue) {
    return hasher.hash(tenantId, system, IdentifierHash.normalize(system, rawValue));
  }

  public CitizenIdentifier newEntity(
      String tenantId, String citizenId, Parsed p, String sourceSystem, String status) {
    CitizenIdentifier ci = new CitizenIdentifier();
    ci.id = Ulid.generate(Ulid.CITIZEN_IDENTIFIER);
    ci.tenantId = tenantId;
    ci.citizenId = citizenId;
    ci.system = p.systemName();
    ci.valueHash = p.hash();
    ci.valueEnc = cipher.encrypt(p.normalized());
    ci.valueMasked = p.masked();
    ci.status = status;
    ci.sourceSystem = sourceSystem;
    return ci;
  }

  public String decrypt(CitizenIdentifier ci) {
    return cipher.decrypt(ci.valueEnc);
  }

  static String validate(IdentifierSystem system, String normalized) {
    return switch (system) {
      case CNS -> Cns.isValid(normalized) ? null : "CNS inválido (dígito verificador)";
      case CPF -> Cpf.isValid(normalized) ? null : "CPF inválido (dígito verificador)";
      default -> normalized.isBlank() ? "valor vazio" : null;
    };
  }
}
