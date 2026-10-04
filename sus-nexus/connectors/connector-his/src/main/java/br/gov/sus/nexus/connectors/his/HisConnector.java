package br.gov.sus.nexus.connectors.his;

import br.gov.sus.nexus.connectors.sdk.api.CanonicalBatch;
import br.gov.sus.nexus.connectors.sdk.api.CanonicalRecord;
import br.gov.sus.nexus.connectors.sdk.api.ConnectorDescriptor;
import br.gov.sus.nexus.connectors.sdk.api.ConnectorException;
import br.gov.sus.nexus.connectors.sdk.api.HealthStatus;
import br.gov.sus.nexus.connectors.sdk.api.RawMessage;
import br.gov.sus.nexus.connectors.sdk.api.ValidationReport;
import br.gov.sus.nexus.connectors.sdk.config.ConnectorConfig;
import br.gov.sus.nexus.connectors.sdk.hl7.Hl7Fields;
import br.gov.sus.nexus.connectors.sdk.hl7.Hl7Parser;
import br.gov.sus.nexus.connectors.sdk.hl7.Hl7Receiver;
import br.gov.sus.nexus.connectors.sdk.mapping.MappingEngine;
import br.gov.sus.nexus.connectors.sdk.mapping.MappingLoader;
import br.gov.sus.nexus.connectors.sdk.mapping.MappingVersion;
import br.gov.sus.nexus.connectors.sdk.runtime.AbstractConnector;
import br.gov.sus.nexus.connectors.sdk.util.Hashes;
import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.model.Message;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * Conector HIS (hospital) via HL7 v2.x ADT. Gatilhos A01/A02/A04/A06/A07/A08/A11/A13 → {@code
 * hospital_movement} ({@code POST /hospital/episodes}, upsert por nº do atendimento PV1-19); A03 →
 * {@code hospital_discharge} ({@code POST /hospital/episodes/by-source/HIS/{visita}/discharge}). As
 * regras de código (classe de paciente, movimento por gatilho, origem da admissão, destino da alta)
 * vivem nos YAML de {@code mappings/<vendor>/}; o Java só deriva campos {@code adt.*} a partir do
 * modelo plano de {@link Hl7Fields}. Diagnósticos passam como código CID-10 normalizado (sem
 * ponto); a classificação de sensibilidade é do core.
 */
@ApplicationScoped
public class HisConnector extends AbstractConnector {

  public static final String SOURCE_SYSTEM = "HIS";
  public static final String CONNECTOR_ID = "connector-his";
  public static final String METRIC_ADT = "connector_his_adt_total";
  public static final String METRIC_ACK = "connector_his_ack_total";

  static final Set<String> MOVEMENT_TRIGGERS =
      Set.of("A01", "A02", "A04", "A06", "A07", "A08", "A11", "A13");
  static final Set<String> DISCHARGE_TRIGGERS = Set.of("A03");

  private static final Logger LOG = Logger.getLogger(HisConnector.class);
  private static final Set<String> EPISODE_CLASSES =
      Set.of("inpatient", "emergency", "observation", "day_hospital");
  private static final Set<String> MOVEMENTS =
      Set.of("admit", "transfer", "bed_change", "discharge", "death", "cancel");
  private static final Set<String> ADMISSION_SOURCES =
      Set.of("emergency", "regulation", "transfer", "elective", "other");
  private static final Set<String> DISPOSITIONS =
      Set.of("home", "home_with_care", "transfer", "against_advice", "deceased", "other");

  private final HisConfig config;
  private final ConnectorConfig connectorConfig;
  private Hl7Parser parser;
  private Hl7Fields fields;
  private MappingVersion movementMapping;
  private MappingVersion dischargeMapping;
  private ConnectorDescriptor descriptor;

  @Inject
  public HisConnector(HisConfig config, ConnectorConfig connectorConfig) {
    this.config = config;
    this.connectorConfig = connectorConfig;
  }

  @PostConstruct
  void init() {
    this.parser = new Hl7Parser();
    this.fields = new Hl7Fields(ZoneId.of(config.zone()));
    String dir = "mappings/" + config.vendor().toLowerCase(Locale.ROOT) + "/";
    this.movementMapping = MappingLoader.fromClasspath(dir + config.mapping().movement());
    this.dischargeMapping = MappingLoader.fromClasspath(dir + config.mapping().discharge());
    this.descriptor =
        ConnectorDescriptor.builder()
            .connectorId(CONNECTOR_ID)
            .connectorVersion("0.1.0")
            .sourceSystem(SOURCE_SYSTEM)
            .supportedSourceVersions(
                List.of(
                    config.sourceVersion() + " [perfil " + config.vendor() + "]",
                    "HL7 v2.3",
                    "HL7 v2.3.1",
                    "HL7 v2.4",
                    "HL7 v2.5",
                    "HL7 v2.5.1"))
            .supportedProtocols(List.of("mllp", "file-hl7"))
            .supportedEntities(
                List.of(CanonicalBatch.HOSPITAL_MOVEMENT, CanonicalBatch.HOSPITAL_DISCHARGE))
            .authenticationMethod(
                connectorConfig.edge()
                    ? ConnectorDescriptor.AuthenticationMethod.MTLS
                    : ConnectorDescriptor.AuthenticationMethod.NONE)
            .requiredNetworkAccess(
                List.of(
                    "core-municipal:8080 (saída única; mTLS quando connector.edge=true)",
                    "HIS → connector-his:" + config.mllp().port() + " (MLLP, rede do hospital)"))
            .dataClassification(ConnectorDescriptor.DataClassification.HIGHLY_RESTRICTED)
            .pollingOrEventMode(ConnectorDescriptor.IngestionMode.EVENT)
            .rateLimitPolicy(new ConnectorDescriptor.RateLimitPolicy(1200, 4))
            .fieldMappingVersion(movementMapping.version())
            .testSuiteVersion("1.0.0")
            .owner("equipe-integracao@sus-nexus")
            .supportSla(
                new ConnectorDescriptor.SupportSla(
                    "gold", Duration.ofHours(1), Duration.ofHours(8)))
            .build();
  }

  @Override
  public ConnectorDescriptor descriptor() {
    return descriptor;
  }

  public Hl7Parser parser() {
    return parser;
  }

  public Charset charset() {
    return Charset.forName(config.charset());
  }

  @Override
  public HealthStatus healthCheck() {
    Map<String, String> details = new LinkedHashMap<>();
    details.put(
        "mllp",
        config.mllp().enabled() ? config.mllp().host() + ":" + config.mllp().port() : "disabled");
    Path in = Path.of(config.file().inputDir());
    details.put("input_dir", in.toString());
    details.put("vendor_profile", config.vendor());
    details.put("edge", String.valueOf(connectorConfig.edge()));
    if (connectorConfig.edge()) {
      details.put("outbound", "core-municipal (mTLS) apenas; sem entrada externa além do MLLP");
    }
    return new HealthStatus(
        Files.isDirectory(in) || config.mllp().enabled()
            ? HealthStatus.State.HEALTHY
            : HealthStatus.State.DEGRADED,
        details);
  }

  /** {@code entity_type} por MSH-9: ADT^A03 → alta; demais gatilhos suportados → movimento. */
  static String entityTypeOf(String type, String trigger) {
    if (!"ADT".equalsIgnoreCase(type) || trigger == null) return null;
    String t = trigger.toUpperCase(Locale.ROOT);
    if (DISCHARGE_TRIGGERS.contains(t)) return CanonicalBatch.HOSPITAL_DISCHARGE;
    if (MOVEMENT_TRIGGERS.contains(t)) return CanonicalBatch.HOSPITAL_MOVEMENT;
    return null;
  }

  /** Nº do atendimento: PV1-19 → PID-18 (conta) → vazio (o receptor usa MSH-10). */
  static String visitIdOf(Message message) {
    return Hl7Receiver.firstTerser(message, "/.PV1-19-1", "/.PID-18-1");
  }

  @Override
  public CanonicalBatch transform(RawMessage raw) {
    String text = new String(raw.content(), charset());
    Hl7Fields.Parsed parsed;
    try {
      Message message = parser.parse(text);
      parsed = fields.extract(message);
    } catch (HL7Exception e) {
      throw ConnectorException.permanent("transform", "HL7 inválido: " + e.getMessage(), e);
    }
    String sha = Hashes.sha256Hex(raw.content());
    String trigger = parsed.triggerEvent().toUpperCase(Locale.ROOT);
    Map<String, String> source = flat(parsed, trigger, sha, text);
    return switch (raw.entityType()) {
      case CanonicalBatch.HOSPITAL_MOVEMENT -> movement(raw, parsed, source);
      case CanonicalBatch.HOSPITAL_DISCHARGE -> discharge(raw, parsed, source);
      default ->
          throw ConnectorException.permanent(
              "transform", "entity_type não suportado: " + raw.entityType(), null);
    };
  }

  private CanonicalBatch movement(
      RawMessage raw, Hl7Fields.Parsed parsed, Map<String, String> source) {
    Map<String, Object> payload = MappingEngine.apply(movementMapping, source);
    payload.put("citizen_ref", citizenRef(parsed.pid()));
    String id = source.get("adt.visit_id");
    LOG.debugf("ADT^%s visita %s → %s", parsed.triggerEvent(), id, payload.get("movement"));
    return new CanonicalBatch(
        CanonicalBatch.HOSPITAL_MOVEMENT,
        movementMapping.version(),
        List.of(new CanonicalRecord(id, parsed.msh().get("msh.control_id"), payload)),
        Map.of("source_record_id", raw.sourceRecordId(), "trigger", parsed.triggerEvent()));
  }

  private CanonicalBatch discharge(
      RawMessage raw, Hl7Fields.Parsed parsed, Map<String, String> source) {
    Map<String, Object> payload = MappingEngine.apply(dischargeMapping, source);
    String id = source.get("adt.visit_id");
    return new CanonicalBatch(
        CanonicalBatch.HOSPITAL_DISCHARGE,
        dischargeMapping.version(),
        List.of(new CanonicalRecord(id, parsed.msh().get("msh.control_id"), payload)),
        Map.of("source_record_id", raw.sourceRecordId(), "trigger", parsed.triggerEvent()));
  }

  /** Modelo plano (MSH/PID/EVN/PV1/Z) + campos derivados {@code adt.*}. */
  Map<String, String> flat(Hl7Fields.Parsed parsed, String trigger, String sha, String text) {
    Map<String, String> m = parsed.flat();
    m.put("adt.trigger", trigger);
    m.put(
        "adt.visit_id",
        firstNonBlank(
            m.get("pv1.visit_number"), m.get("pid.account_number"), m.get("msh.control_id")));
    m.put(
        "adt.hospital_cnes",
        cnes(
            config.hospital().cnes().orElse(""),
            m.get("msh.sending_facility"),
            m.get("pv1.facility")));
    m.put(
        "adt.occurred_at",
        firstNonBlank(
            m.get("evn.occurred_at"),
            m.get("evn.recorded_at"),
            m.get("pv1.admit_datetime"),
            m.get("msh.timestamp")));
    m.put(
        "adt.discharged_at",
        firstNonBlank(
            m.get("pv1.discharge_datetime"),
            m.get("evn.occurred_at"),
            m.get("evn.recorded_at"),
            m.get("msh.timestamp")));
    m.put("adt.patient_class", patientClass(trigger, m.get("pv1.patient_class")));
    m.put("adt.trigger_key", triggerKey(trigger, m));
    m.put("adt.bed", firstNonBlank(m.get("pv1.bed"), m.get("pv1.room")));
    m.put("adt.admit_source", m.getOrDefault("pv1.admit_source", ""));
    m.put("adt.regulation_id", regulationId(m));
    m.put("adt.aih_number", aihNumber(m));
    boolean discharge = DISCHARGE_TRIGGERS.contains(trigger);
    m.put(
        "adt.principal_diagnosis",
        principalDiagnosis(
            parsed.diagnoses(),
            discharge ? config.diagnosis().dischargeType() : config.diagnosis().admitType()));
    m.put("adt.procedures_count", String.valueOf(parsed.procedures().size()));
    if (discharge && config.discharge().summaryRefFromRaw() && hasSummary(text)) {
      m.put("adt.summary_ref", "raw://" + CONNECTOR_ID + "/sha256/" + sha);
      m.put("adt.summary_sha256", sha);
    }
    return m;
  }

  /** Classe de paciente: PV1-2, com padrões por gatilho (A04 → emergência salvo observação). */
  String patientClass(String trigger, String pv12) {
    String c = pv12 == null ? "" : pv12.trim().toUpperCase(Locale.ROOT);
    return switch (trigger) {
      case "A04" -> c.equals("O") ? "O" : config.hospital().a04PatientClass();
      case "A06" -> c.isBlank() ? "I" : c;
      case "A07" -> c.isBlank() ? "O" : c;
      default -> c.isBlank() ? "I" : c;
    };
  }

  /** A02 na mesma enfermaria (PV1-6-1 == PV1-3-1) é troca de leito: chave {@code A02_BED}. */
  static String triggerKey(String trigger, Map<String, String> m) {
    if ("A02".equals(trigger)) {
      String ward = m.getOrDefault("pv1.ward", "");
      String prior = m.getOrDefault("pv1.prior_ward", "");
      if (!ward.isBlank() && ward.equalsIgnoreCase(prior)) return "A02_BED";
    }
    return trigger;
  }

  private String regulationId(Map<String, String> m) {
    String src = config.regulation().source().toLowerCase(Locale.ROOT);
    if (src.equals("none")) return "";
    if (src.equals("pv1-5")) return m.getOrDefault("pv1.preadmit_number", "");
    return m.getOrDefault(src, "");
  }

  private String aihNumber(Map<String, String> m) {
    String src = config.aih().source().toLowerCase(Locale.ROOT);
    return switch (src) {
      case "pv1-50" -> m.getOrDefault("pv1.alternate_visit_id", "");
      case "zai" -> m.getOrDefault(config.aih().zaiField().toLowerCase(Locale.ROOT), "");
      default -> "";
    };
  }

  /** DG1 do tipo preferido (DG1-6), senão o primeiro; código CID-10 sem ponto, maiúsculo. */
  static String principalDiagnosis(List<Hl7Fields.Diagnosis> diagnoses, String preferredType) {
    if (diagnoses.isEmpty()) return "";
    Hl7Fields.Diagnosis chosen = null;
    for (Hl7Fields.Diagnosis d : diagnoses) {
      if (!d.code().isBlank() && d.type().equalsIgnoreCase(preferredType)) {
        chosen = d;
        break;
      }
    }
    if (chosen == null) {
      for (Hl7Fields.Diagnosis d : diagnoses) {
        if (!d.code().isBlank()) {
          chosen = d;
          break;
        }
      }
    }
    return chosen == null ? "" : normalizeCid(chosen.code());
  }

  static String normalizeCid(String code) {
    return code == null ? "" : code.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
  }

  private static boolean hasSummary(String text) {
    for (String seg : Hl7Parser.normalize(text).split("\r")) {
      if (seg.startsWith("OBX|") || seg.startsWith("NTE|")) return true;
    }
    return false;
  }

  /** CNS (PID-3 tipo CNS) → CPF → identificador local do HIS (assigning authority). */
  Map<String, Object> citizenRef(Map<String, String> pid) {
    Map<String, Object> ref = new LinkedHashMap<>();
    String cnsType = config.pid().cnsIdentifierType().toUpperCase(Locale.ROOT);
    String cpfType = config.pid().cpfIdentifierType().toUpperCase(Locale.ROOT);
    String cns = digits(pid.get("pid.identifier." + cnsType));
    String cpf = digits(pid.get("pid.identifier." + cpfType));
    String local = null;
    for (String entry : pid.getOrDefault("pid.identifiers", "").split("~")) {
      if (entry.isBlank()) continue;
      String[] parts = entry.split("\\^", -1);
      String id = parts[0];
      String d = digits(id);
      boolean numeric = d.equals(id.trim());
      if (config.pid().inferByLength() && cns.isEmpty() && numeric && d.length() == 15) cns = d;
      else if (config.pid().inferByLength() && cpf.isEmpty() && numeric && d.length() == 11)
        cpf = d;
      else if (local == null
          && !(parts.length > 2 && (parts[2].equals(cnsType) || parts[2].equals(cpfType))))
        local = id;
    }
    if (cns.length() == 15) {
      ref.put("identifier_system", "CNS");
      ref.put("identifier_value", cns);
    } else if (cpf.length() == 11) {
      ref.put("identifier_system", "CPF");
      ref.put("identifier_value", cpf);
    } else {
      String fallback =
          firstNonBlank(local, pid.get("pid.external_id"), pid.get("pid.identifier.untyped"));
      if (!fallback.isBlank()) {
        ref.put("identifier_system", config.pid().localIdentifierSystem());
        ref.put("identifier_value", fallback);
      }
    }
    return ref;
  }

  private static String cnes(String... candidates) {
    for (String c : candidates) {
      String d = digits(c);
      if (d.length() == 7) return d;
    }
    return "";
  }

  private static String firstNonBlank(String... values) {
    for (String v : values) if (v != null && !v.isBlank()) return v.trim();
    return "";
  }

  private static String digits(String v) {
    return v == null ? "" : v.replaceAll("\\D", "");
  }

  @Override
  @SuppressWarnings("unchecked")
  public ValidationReport validate(CanonicalBatch batch) {
    ValidationReport.Builder b = ValidationReport.builder();
    for (CanonicalRecord r : batch.records()) {
      Map<String, Object> p = r.payload();
      String id = r.sourceRecordId();
      Map<String, Object> source = (Map<String, Object>) p.get("source");
      b.required(
          id, "source.source_record_id", source == null ? null : source.get("source_record_id"));
      if (CanonicalBatch.HOSPITAL_MOVEMENT.equals(batch.entityType())) {
        Map<String, Object> ref = (Map<String, Object>) p.get("citizen_ref");
        if (ref == null || ref.isEmpty()) {
          b.error(
              id, "citizen_ref", "required", "PID sem identificador utilizável (CNS/CPF/local)");
        } else if ("CNS".equals(ref.get("identifier_system"))
            && !String.valueOf(ref.get("identifier_value")).matches("\\d{15}")) {
          b.error(id, "citizen_ref", "format", "CNS deve ter 15 dígitos");
        }
        b.required(id, "hospital_cnes", p.get("hospital_cnes"));
        b.required(id, "occurred_at", p.get("occurred_at"));
        enumCheck(b, id, p, "episode_class", EPISODE_CLASSES);
        enumCheck(b, id, p, "movement", MOVEMENTS);
        enumCheck(b, id, p, "admission_source", ADMISSION_SOURCES);
      } else {
        Map<String, Object> target = (Map<String, Object>) p.get("target_ref");
        b.required(
            id,
            "target_ref.source_record_id",
            target == null ? null : target.get("source_record_id"));
        b.required(id, "discharged_at", p.get("discharged_at"));
        enumCheck(b, id, p, "disposition", DISPOSITIONS);
        if (p.get("procedures_count") != null && !(p.get("procedures_count") instanceof Number)) {
          b.error(id, "procedures_count", "format", "procedures_count deve ser inteiro");
        }
      }
      if (p.get("hospital_cnes") != null && !p.get("hospital_cnes").toString().matches("\\d{7}")) {
        b.error(id, "hospital_cnes", "format", "hospital_cnes deve ter 7 dígitos");
      }
      Object cid = p.get("principal_diagnosis_cid");
      if (cid != null && !cid.toString().matches("[A-Z][0-9]{2}[0-9A-Z]{0,2}")) {
        b.warning(id, "principal_diagnosis_cid", "format", "CID-10 fora do padrão: " + cid);
      }
    }
    return b.build();
  }

  private static void enumCheck(
      ValidationReport.Builder b, String id, Map<String, Object> p, String field, Set<String> dom) {
    Object v = p.get(field);
    if (v == null) {
      if (field.equals("episode_class")
          || field.equals("movement")
          || field.equals("disposition")) {
        b.required(id, field, null);
      }
      return;
    }
    if (!dom.contains(v.toString())) {
      b.error(id, field, "enum", field + " fora do domínio: " + v);
    }
  }
}
