package br.gov.sus.nexus.fhir.mapping;

import br.gov.sus.nexus.fhir.FhirConstants;
import java.time.Instant;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.hl7.fhir.r4.model.CodeType;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.DomainResource;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Identifier.IdentifierUse;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.StringType;
import org.hl7.fhir.r4.model.Type;

/** Utilitários comuns aos mapeadores canônico → FHIR (FHIR-2). */
public final class MappingSupport {

  private MappingSupport() {}

  /** Prefixos de ULID do core → tipo FHIR projetado correspondente. */
  private static final Map<String, String> PREFIX_TYPES =
      Map.ofEntries(
          Map.entry("cit", "Patient"),
          Map.entry("apt", "Appointment"),
          Map.entry("reg", "ServiceRequest"),
          Map.entry("exo", "ServiceRequest"),
          Map.entry("enc", "Encounter"),
          Map.entry("task", "Task"),
          Map.entry("hu", "Organization"),
          Map.entry("hep", "Encounter"),
          Map.entry("cp", "CarePlan"),
          Map.entry("exr", "DiagnosticReport"),
          Map.entry("gap", "Task"));

  static Reference patientRef(String citizenId) {
    return new Reference("Patient/" + CanonicalIds.toFhirId(citizenId));
  }

  /** Referência lógica a uma Organization pelo CNES (resolvida pelo {@code ProjectionService}). */
  public static Reference organizationByCnes(String cnes) {
    return new Reference()
        .setType("Organization")
        .setIdentifier(new Identifier().setSystem(FhirConstants.SYSTEM_CNES).setValue(cnes))
        .setDisplay("CNES " + cnes);
  }

  /** Referência lógica a uma Location pelo CNES (participante de Appointment). */
  static Reference locationByCnes(String cnes) {
    return new Reference()
        .setType("Location")
        .setIdentifier(new Identifier().setSystem(FhirConstants.SYSTEM_CNES).setValue(cnes))
        .setDisplay("CNES " + cnes);
  }

  static Reference logical(String type, String system, String value) {
    return new Reference()
        .setType(type)
        .setIdentifier(new Identifier().setSystem(system).setValue(value));
  }

  /** Referência direta a partir de um id canônico prefixado conhecido ({@code reg_…} etc.). */
  static Optional<Reference> referenceFromPrefixedId(String canonicalId) {
    if (canonicalId == null) {
      return Optional.empty();
    }
    int underscore = canonicalId.indexOf('_');
    if (underscore <= 0) {
      return Optional.empty();
    }
    String type = PREFIX_TYPES.get(canonicalId.substring(0, underscore));
    if (type == null) {
      return Optional.empty();
    }
    try {
      return Optional.of(new Reference(type + "/" + CanonicalIds.toFhirId(canonicalId)));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  static Identifier identifier(String system, String value) {
    return new Identifier().setUse(IdentifierUse.OFFICIAL).setSystem(system).setValue(value);
  }

  static Optional<Identifier> sourceIdentifier(String sourceSystem, String sourceRecordId) {
    if (isBlank(sourceRecordId)) {
      return Optional.empty();
    }
    Identifier id =
        new Identifier()
            .setUse(IdentifierUse.SECONDARY)
            .setSystem(FhirConstants.SYSTEM_SOURCE_RECORD_ID)
            .setValue(sourceRecordId);
    if (!isBlank(sourceSystem)) {
      id.setAssigner(new Reference().setDisplay(sourceSystem));
    }
    return Optional.of(id);
  }

  /** Coding de procedimento/exame conforme {@code code_system} canônico (SIGTAP padrão). */
  static Coding procedureCoding(MapperSettings s, String codeSystem, String code, String display) {
    String cs = codeSystem == null ? "SIGTAP" : codeSystem.toUpperCase(Locale.ROOT);
    String system =
        switch (cs) {
          case "SIGTAP" -> s.sigtapSystem();
          case "LOINC" -> s.loincSystem();
          case "LOCAL" -> s.localSystem();
          default -> FhirConstants.SUS_NEXUS_BASE + "/CodeSystem/" + cs.toLowerCase(Locale.ROOT);
        };
    Coding c = new Coding().setSystem(system).setCode(code);
    if (!isBlank(display)) {
      c.setDisplay(display);
    }
    return c;
  }

  static Date date(Instant instant) {
    return instant == null ? null : Date.from(instant);
  }

  static void extension(DomainResource r, String url, Type value) {
    if (value != null) {
      r.addExtension(url, value);
    }
  }

  static void extensionString(DomainResource r, String url, String value) {
    if (!isBlank(value)) {
      r.addExtension(url, new StringType(value));
    }
  }

  static void extensionCode(DomainResource r, String url, String value) {
    if (!isBlank(value)) {
      r.addExtension(url, new CodeType(value));
    }
  }

  /** {@code meta.security} para classificação {@code highly_restricted}. */
  static void applySensitivity(DomainResource r, String sensitivity) {
    if ("highly_restricted".equals(sensitivity)) {
      r.getMeta()
          .addSecurity(
              new Coding()
                  .setSystem(FhirConstants.CS_SENSITIVITY)
                  .setCode("highly_restricted")
                  .setDisplay("Altamente restrito"));
      r.getMeta()
          .addSecurity(new Coding().setSystem(FhirConstants.CS_V3_CONFIDENTIALITY).setCode("V"));
    } else if ("restricted".equals(sensitivity)) {
      r.getMeta()
          .addSecurity(new Coding().setSystem(FhirConstants.CS_V3_CONFIDENTIALITY).setCode("R"));
    }
  }

  static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }

  static String lower(String s) {
    return s == null ? "" : s.toLowerCase(Locale.ROOT);
  }
}
