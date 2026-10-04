package br.gov.sus.nexus.core.production.application;

import br.gov.sus.nexus.core.hospital.api.HospitalEpisodeDto;
import br.gov.sus.nexus.core.hospital.api.HospitalService;
import br.gov.sus.nexus.core.identity.api.CitizenDetail;
import br.gov.sus.nexus.core.identity.api.IdentifierSystem;
import br.gov.sus.nexus.core.identity.api.MaskedIdentifier;
import br.gov.sus.nexus.core.identity.api.Sex;
import br.gov.sus.nexus.core.platform.rules.RuleEvaluator;
import br.gov.sus.nexus.core.platform.rules.RuleSets;
import br.gov.sus.nexus.core.production.api.ProductionKind;
import br.gov.sus.nexus.core.production.domain.ProductionRecord;
import br.gov.sus.nexus.core.production.infrastructure.ProductionRepositories;
import br.gov.sus.nexus.core.reference.api.HealthUnitDto;
import br.gov.sus.nexus.core.reference.api.HealthUnitService;
import br.gov.sus.nexus.core.reference.api.ProfessionalDirectory;
import br.gov.sus.nexus.core.scheduling.api.AppointmentDto;
import br.gov.sus.nexus.core.scheduling.api.AppointmentService;
import br.gov.sus.nexus.core.scheduling.api.AppointmentStatus;
import br.gov.sus.nexus.core.sharedkernel.Competence;
import br.gov.sus.nexus.core.terminology.api.CodeDto;
import br.gov.sus.nexus.core.terminology.api.TerminologyService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalTime;
import java.time.Period;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Pré-auditoria (PRO-002..004): calcula FATOS sobre o registro (terminologia SIGTAP/CBO/CID por
 * competência, CNES, cidadão, agenda, episódio hospitalar, prazo) e aplica as regras do conjunto
 * versionado {@code production-validation} vigente (cada regra = condição de violação jsonb,
 * severidade, campo e mensagem). Nenhuma regra está no código: só o cálculo dos fatos.
 */
@ApplicationScoped
public class PreAuditor {

  public static final String RULE_SET = "production-validation";

  /** Fato de entrada guardado no registro: validade (DV) do CNS/CPF informado pela origem. */
  public static final String INPUT_IDENTIFIER_VALID = "citizen_identifier_input_valid";

  /**
   * Fatos calculados por {@link #evaluate} — únicos nomes aceitos nas condições de uma nova versão
   * da regra ({@code POST /api/v1/production/rules}); um fato com erro de digitação nunca
   * dispararia a regra.
   */
  public static final java.util.Set<String> FACTS =
      java.util.Set.of(
          "kind",
          "requires_citizen",
          "citizen_resolved",
          INPUT_IDENTIFIER_VALID,
          "citizen_identifier_valid",
          "cnes_registered",
          "cnes_active",
          "procedure_valid_in_competence",
          "cbo_exists",
          "cbo_compatible",
          "instrument_compatible",
          "sex_compatible",
          "age_compatible",
          "quantity_within_max",
          "duplicate",
          "attendance_in_competence",
          "competence_open",
          "apac_number_present",
          "aih_number_present",
          "cid_valid",
          "evidence_present",
          "hospital_episode_linked",
          "professional_cbo_linked");

  static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");
  static final int MAX_PREVIOUS_COMPETENCES = 3;

  /** Violação encontrada. */
  public record Finding(String ruleId, String severity, String field, String message) {}

  /** Resultado da avaliação (reprodutível: fatos + versão da regra). */
  public record Result(
      String ruleVersion,
      Map<String, Object> facts,
      List<Finding> findings,
      BigDecimal unitValue,
      String procedureDisplay,
      String appointmentId,
      String hospitalEpisodeId) {}

  @Inject RuleSets ruleSets;
  @Inject TerminologyService terminology;
  @Inject HealthUnitService healthUnits;
  @Inject AppointmentService appointments;
  @Inject HospitalService hospital;
  @Inject ProfessionalDirectory professionals;
  @Inject ProductionRepositories.Records records;
  @Inject ProductionDeadlines deadlines;

  /** Versão vigente do conjunto de regras (label {@code production-validation/<v>}). */
  public RuleSets.RuleVersion currentRules() {
    return ruleSets
        .current(RULE_SET)
        .orElseThrow(() -> new IllegalStateException("regra " + RULE_SET + " sem versão vigente"));
  }

  public Result evaluate(ProductionRecord r, CitizenDetail citizen, Instant now) {
    RuleSets.RuleVersion rules = currentRules();
    ProductionKind kind = ProductionKind.fromWire(r.kind);
    Map<String, Object> facts = new LinkedHashMap<>();
    facts.put("kind", kind.wire());

    // cidadão (individualizados exigem identificação)
    boolean requiresCitizen = kind.individualized();
    facts.put("requires_citizen", requiresCitizen);
    if (requiresCitizen || r.citizenId != null) {
      facts.put("citizen_resolved", citizen != null);
    }
    Object inputValid = r.facts == null ? null : r.facts.get(INPUT_IDENTIFIER_VALID);
    if (inputValid instanceof Boolean b) {
      facts.put(INPUT_IDENTIFIER_VALID, b);
      facts.put("citizen_identifier_valid", b);
    } else if (citizen != null) {
      facts.put("citizen_identifier_valid", hasCnsOrCpf(citizen));
    }

    // estabelecimento
    Optional<HealthUnitDto> unit = healthUnits.findByCnes(r.cnes);
    facts.put("cnes_registered", unit.isPresent());
    unit.ifPresent(u -> facts.put("cnes_active", u.active()));

    // procedimento SIGTAP e atributos na competência
    Optional<CodeDto> procedure = terminology.find("SIGTAP", r.procedureCode, r.competence);
    facts.put("procedure_valid_in_competence", procedure.isPresent());
    Map<String, Object> attrs = procedure.map(CodeDto::attributes).orElse(Map.of());
    if (attrs == null) {
      attrs = Map.of();
    }
    facts.put("cbo_exists", terminology.isValid("CBO", r.professionalCbo, r.competence));
    List<String> cbos = strings(attrs.get("cbos"));
    if (procedure.isPresent() && !cbos.isEmpty()) {
      facts.put("cbo_compatible", cbos.contains(r.professionalCbo));
    }
    List<String> instruments = strings(attrs.get("instrumentos"));
    if (procedure.isPresent() && !instruments.isEmpty()) {
      facts.put("instrument_compatible", instruments.contains(kind.instrument()));
    }
    if (procedure.isPresent() && citizen != null) {
      sexCompatible(attrs, citizen).ifPresent(v -> facts.put("sex_compatible", v));
      ageCompatible(attrs, citizen, r).ifPresent(v -> facts.put("age_compatible", v));
    }
    Integer max = integer(attrs.get("qt_maxima"));
    if (procedure.isPresent() && max != null) {
      facts.put("quantity_within_max", r.quantity <= max);
    }

    // duplicidade, competência e prazo
    if (r.citizenId != null) {
      facts.put("duplicate", records.existsEarlierDuplicate(r));
    }
    facts.put("attendance_in_competence", attendanceInCompetence(r));
    Instant deadline = deadlines.resolve(r.competence).deadlineAt();
    facts.put("competence_open", !now.isAfter(deadline));

    // autorizações e CID
    if (kind == ProductionKind.APAC) {
      facts.put("apac_number_present", present(r.apacNumber));
    }
    if (kind == ProductionKind.AIH) {
      facts.put("aih_number_present", present(r.aihNumber));
    }
    if (present(r.cidCode)) {
      facts.put("cid_valid", cidValid(r.cidCode, r.competence));
    }

    // evidência (agenda) e conciliação hospitalar
    String appointmentId = null;
    if (kind == ProductionKind.BPA_I || kind == ProductionKind.APAC) {
      appointmentId = evidence(r, citizen);
      facts.put("evidence_present", appointmentId != null || present(r.encounterSourceRecordId));
    }
    String episodeId = null;
    if (kind == ProductionKind.AIH) {
      episodeId = episode(r);
      facts.put("hospital_episode_linked", episodeId != null);
    }

    // vínculo do profissional (só quando a base de referência conhece o profissional)
    if (r.professionalCnsHash != null) {
      professionals
          .activeCbos(r.professionalCnsHash, r.cnes)
          .ifPresent(set -> facts.put("professional_cbo_linked", set.contains(r.professionalCbo)));
    }

    List<Finding> findings = apply(rules.definition(), facts);
    BigDecimal unitValue = decimal(attrs.get("valor"));
    return new Result(
        rules.label(RULE_SET),
        facts,
        findings,
        unitValue,
        procedure.map(CodeDto::display).orElse(null),
        appointmentId,
        episodeId);
  }

  /** Aplica as regras do conjunto (condição de violação em {@code when}). */
  public static List<Finding> apply(JsonNode definition, Map<String, Object> facts) {
    List<Finding> out = new ArrayList<>();
    for (JsonNode rule : definition.path("rules")) {
      if (rule.path("enabled").isBoolean() && !rule.path("enabled").asBoolean()) {
        continue;
      }
      JsonNode when = rule.get("when");
      if (when == null || when.isNull() || when.isEmpty()) {
        continue; // regra sem condição nunca dispara (evita pendência universal por configuração)
      }
      if (RuleEvaluator.matches(when, facts)) {
        out.add(
            new Finding(
                rule.path("id").asText(),
                "warning".equals(rule.path("severity").asText()) ? "warning" : "error",
                rule.path("field").asText(null),
                rule.path("message").asText(rule.path("id").asText())));
      }
    }
    return out;
  }

  static boolean hasCnsOrCpf(CitizenDetail c) {
    if (c.identifiers() == null) {
      return false;
    }
    for (MaskedIdentifier i : c.identifiers()) {
      if ((i.system() == IdentifierSystem.CNS || i.system() == IdentifierSystem.CPF)
          && (i.status() == null || "active".equals(i.status()))) {
        return true;
      }
    }
    return false;
  }

  static Optional<Boolean> sexCompatible(Map<String, Object> attrs, CitizenDetail c) {
    Object sexo = attrs.get("sexo");
    if (sexo == null || c.sex() == null || c.sex() == Sex.UNKNOWN) {
      return Optional.empty();
    }
    String s = sexo.toString().trim().toUpperCase(Locale.ROOT);
    return switch (s) {
      case "F" -> Optional.of(c.sex() == Sex.FEMALE);
      case "M" -> Optional.of(c.sex() == Sex.MALE);
      default -> Optional.empty(); // I = ambos
    };
  }

  static Optional<Boolean> ageCompatible(
      Map<String, Object> attrs, CitizenDetail c, ProductionRecord r) {
    Integer min = integer(attrs.get("idade_min"));
    Integer max = integer(attrs.get("idade_max"));
    if (c.birthdate() == null || (min == null && max == null)) {
      return Optional.empty();
    }
    int age = Period.between(c.birthdate(), r.attendanceDate).getYears();
    return Optional.of((min == null || age >= min) && (max == null || age <= max));
  }

  /** Atendimento na competência ou até 3 competências anteriores (apresentação tardia). */
  static boolean attendanceInCompetence(ProductionRecord r) {
    YearMonth comp = new Competence(r.competence).toYearMonth();
    YearMonth attended = YearMonth.from(r.attendanceDate);
    return !attended.isAfter(comp)
        && !attended.isBefore(comp.minusMonths(MAX_PREVIOUS_COMPETENCES));
  }

  private boolean cidValid(String cid, String competence) {
    String c = cid.trim().toUpperCase(Locale.ROOT);
    String plain = c.replace(".", "");
    if (terminology.isValid("CID10", c, competence) || terminology.isValid("CID10", plain, null)) {
      return true;
    }
    if (plain.length() > 3) {
      String dotted = plain.substring(0, 3) + "." + plain.substring(3);
      return terminology.isValid("CID10", dotted, null)
          || terminology.isValid("CID10", plain.substring(0, 3), null);
    }
    return false;
  }

  /** Agendamento realizado ({@code fulfilled}) correspondente: por vínculo ou cidadão+data+CNES. */
  private String evidence(ProductionRecord r, CitizenDetail citizen) {
    if (present(r.appointmentSourceRecordId)) {
      Optional<AppointmentDto> a =
          appointments.findBySourceRecord(r.appointmentSourceSystem, r.appointmentSourceRecordId);
      if (a.isPresent() && a.get().status() == AppointmentStatus.FULFILLED) {
        return a.get().id();
      }
    }
    if (citizen == null) {
      return null;
    }
    ZonedDateTime from = r.attendanceDate.atStartOfDay(ZONE);
    ZonedDateTime to = r.attendanceDate.atTime(LocalTime.MAX).atZone(ZONE);
    return appointments
        .list(
            citizen.id(),
            r.cnes,
            AppointmentStatus.FULFILLED,
            from.toOffsetDateTime(),
            to.toOffsetDateTime(),
            null,
            1)
        .items()
        .stream()
        .findFirst()
        .map(AppointmentDto::id)
        .orElse(null);
  }

  /** Episódio hospitalar vinculado à AIH (ADT), pelo vínculo de origem informado. */
  private String episode(ProductionRecord r) {
    if (present(r.hospitalEpisodeSourceRecordId) && present(r.hospitalEpisodeSourceSystem)) {
      return hospital
          .findBySource(r.hospitalEpisodeSourceSystem, r.hospitalEpisodeSourceRecordId)
          .map(HospitalEpisodeDto::id)
          .orElse(null);
    }
    return null;
  }

  static List<String> strings(Object v) {
    if (v instanceof Collection<?> c) {
      List<String> out = new ArrayList<>();
      for (Object o : c) {
        if (o != null) {
          out.add(o.toString().trim());
        }
      }
      return out;
    }
    return List.of();
  }

  static Integer integer(Object v) {
    if (v instanceof Number n) {
      return n.intValue();
    }
    if (v instanceof String s && s.matches("^-?[0-9]+$")) {
      return Integer.valueOf(s);
    }
    return null;
  }

  static BigDecimal decimal(Object v) {
    if (v instanceof Number n) {
      return new BigDecimal(n.toString()).setScale(2, RoundingMode.HALF_UP);
    }
    if (v instanceof String s) {
      try {
        return new BigDecimal(s.trim()).setScale(2, RoundingMode.HALF_UP);
      } catch (NumberFormatException e) {
        return null;
      }
    }
    return null;
  }

  static boolean present(String s) {
    return s != null && !s.isBlank();
  }
}
