package br.gov.sus.nexus.connectors.lis;

import br.gov.sus.nexus.connectors.sdk.api.CanonicalBatch;
import br.gov.sus.nexus.connectors.sdk.api.CanonicalRecord;
import br.gov.sus.nexus.connectors.sdk.api.ConnectorDescriptor;
import br.gov.sus.nexus.connectors.sdk.api.ConnectorException;
import br.gov.sus.nexus.connectors.sdk.api.HealthStatus;
import br.gov.sus.nexus.connectors.sdk.api.RawMessage;
import br.gov.sus.nexus.connectors.sdk.api.ValidationReport;
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
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * Conector LIS (laboratório) via HL7 v2.x: {@code ORM^O01} → {@code exam_order} ({@code POST
 * /exams/orders}, upsert por número do pedido) e {@code ORU^R01} → {@code exam_result} ({@code POST
 * /exams/orders/by-source/LIS/{pedido}/results}). Resultados estruturados só com OBX numéricos
 * (NM/SN); texto livre (TX/FT/ST...) nunca vai ao core — fica na raw zone, referenciada por {@code
 * document_ref}/{@code document_sha256}. Mudanças de status do pedido (ORM com ORC-1 SC/CA, ORC-5)
 * são publicadas como upsert do pedido com o novo {@code status}.
 */
@ApplicationScoped
public class LisConnector extends AbstractConnector {

  public static final String SOURCE_SYSTEM = "LIS";
  public static final String HL7_CONTENT_TYPE = "x-application/hl7-v2+er7";
  public static final String META_MESSAGE_TYPE = "message_type";
  public static final String META_CONTROL_ID = "control_id";
  public static final String META_TRANSPORT = "transport";
  public static final String METRIC_OBX_SKIPPED = "connector_lis_obx_skipped_total";

  /** Chave temporária do payload com identificadores PID (removida no transform). */
  static final String PATIENT_IDENTIFIERS = "patient_identifiers";

  private static final Logger LOG = Logger.getLogger(LisConnector.class);
  private static final Set<String> NUMERIC = Set.of("NM", "SN");
  private static final Set<String> ABNORMAL =
      Set.of("A", "H", "L", "HH", "LL", "AA", "HU", "LU", ">", "<", "AB");
  private static final Set<String> ORDER_STATUSES =
      Set.of(
          "requested",
          "authorized",
          "scheduled",
          "collected",
          "performed",
          "reported",
          "cancelled",
          "not_performed");
  private static final Set<String> RESULT_STATUSES =
      Set.of("final", "preliminary", "amended", "inconclusive", "cancelled");

  private final LisConfig config;
  private Hl7Parser parser;
  private Hl7Fields fields;
  private MappingVersion orderMapping;
  private MappingVersion resultMapping;
  private ConnectorDescriptor descriptor;
  private Set<String> criticalFlags;

  @Inject
  public LisConnector(LisConfig config) {
    this.config = config;
  }

  @PostConstruct
  void init() {
    this.parser = new Hl7Parser();
    this.fields = new Hl7Fields(ZoneId.of(config.zone()));
    this.orderMapping = MappingLoader.fromClasspath(config.mapping().order());
    this.resultMapping = MappingLoader.fromClasspath(config.mapping().result());
    this.criticalFlags = new HashSet<>();
    config.critical().flags().forEach(f -> criticalFlags.add(f.trim().toUpperCase(Locale.ROOT)));
    this.descriptor =
        ConnectorDescriptor.builder()
            .connectorId("connector-lis")
            .connectorVersion("0.1.0")
            .sourceSystem(SOURCE_SYSTEM)
            .supportedSourceVersions(
                List.of(
                    config.sourceVersion(),
                    "HL7 v2.3",
                    "HL7 v2.3.1",
                    "HL7 v2.4",
                    "HL7 v2.5",
                    "HL7 v2.5.1"))
            .supportedProtocols(List.of("mllp", "file-hl7"))
            .supportedEntities(List.of(CanonicalBatch.EXAM_ORDER, CanonicalBatch.EXAM_RESULT))
            .authenticationMethod(ConnectorDescriptor.AuthenticationMethod.NONE)
            .requiredNetworkAccess(
                List.of(
                    "core-municipal:8080",
                    "LIS → connector-lis:" + config.mllp().port() + " (MLLP, rede interna)"))
            .dataClassification(ConnectorDescriptor.DataClassification.HIGHLY_RESTRICTED)
            .pollingOrEventMode(ConnectorDescriptor.IngestionMode.EVENT)
            .rateLimitPolicy(new ConnectorDescriptor.RateLimitPolicy(1200, 4))
            .fieldMappingVersion(orderMapping.version())
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
    return new HealthStatus(
        Files.isDirectory(in) || config.mllp().enabled()
            ? HealthStatus.State.HEALTHY
            : HealthStatus.State.DEGRADED,
        details);
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
    return switch (raw.entityType()) {
      case CanonicalBatch.EXAM_ORDER -> orders(raw, parsed);
      case CanonicalBatch.EXAM_RESULT -> results(raw, parsed, sha);
      default ->
          throw ConnectorException.permanent(
              "transform", "entity_type não suportado: " + raw.entityType(), null);
    };
  }

  private CanonicalBatch orders(RawMessage raw, Hl7Fields.Parsed parsed) {
    List<CanonicalRecord> records = new ArrayList<>();
    Map<String, Object> citizenRef = citizenRef(parsed.pid());
    for (Hl7Fields.Order order : parsed.orders()) {
      Map<String, String> source = flat(parsed, order);
      Map<String, Object> payload = MappingEngine.apply(orderMapping, source);
      payload.remove(PATIENT_IDENTIFIERS);
      Object fromControl = payload.remove("status_from_control");
      if (fromControl != null) payload.put("status", fromControl);
      payload.putIfAbsent("status", "requested");
      payload.put("citizen_ref", citizenRef);
      records.add(
          new CanonicalRecord(source.get("order.id"), parsed.msh().get("msh.control_id"), payload));
    }
    return new CanonicalBatch(
        CanonicalBatch.EXAM_ORDER,
        orderMapping.version(),
        records,
        Map.of("source_record_id", raw.sourceRecordId()));
  }

  private CanonicalBatch results(RawMessage raw, Hl7Fields.Parsed parsed, String sha) {
    List<CanonicalRecord> records = new ArrayList<>();
    boolean mshCritical = mshCritical(parsed.msh());
    for (Hl7Fields.Order order : parsed.orders()) {
      Map<String, String> source = flat(parsed, order);
      Map<String, Object> payload = MappingEngine.apply(resultMapping, source);
      payload.remove(PATIENT_IDENTIFIERS);
      List<Map<String, Object>> observations = new ArrayList<>();
      boolean critical = mshCritical;
      int skipped = 0;
      for (Hl7Fields.Obx obx : order.observations()) {
        if (!NUMERIC.contains(obx.valueType())) {
          skipped++;
          metrics.counter(
              METRIC_OBX_SKIPPED,
              1,
              "connector_id",
              descriptor.connectorId(),
              "value_type",
              obx.valueType().isBlank() ? "unknown" : obx.valueType());
          continue;
        }
        BigDecimal value = numeric(obx.value());
        if (value == null) {
          skipped++;
          continue;
        }
        String flag = obx.abnormalFlag().toUpperCase(Locale.ROOT);
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("code", obx.code());
        o.put("code_system", codeSystem(obx.codingSystem()));
        o.put("value", value);
        if (!obx.unit().isBlank()) o.put("unit", obx.unit());
        if (!flag.isBlank())
          o.put("abnormal", ABNORMAL.contains(flag) || criticalFlags.contains(flag));
        observations.add(o);
        if (criticalFlags.contains(flag)) critical = true;
      }
      payload.put("observations", observations);
      payload.put("critical", critical);
      if (skipped > 0 || observations.isEmpty()) {
        // texto livre / laudo: só referência segura à raw zone, nunca o conteúdo
        payload.put("document_ref", "raw://" + descriptor.connectorId() + "/sha256/" + sha);
        payload.put("document_content_type", HL7_CONTENT_TYPE);
        payload.put("document_sha256", sha);
      }
      if (skipped > 0) {
        LOG.debugf(
            "ORU %s: %d OBX não numérico(s) mantido(s) apenas na raw zone",
            parsed.msh().get("msh.control_id"), skipped);
      }
      records.add(
          new CanonicalRecord(source.get("order.id"), parsed.msh().get("msh.control_id"), payload));
    }
    return new CanonicalBatch(
        CanonicalBatch.EXAM_RESULT,
        resultMapping.version(),
        records,
        Map.of("source_record_id", raw.sourceRecordId()));
  }

  /** Modelo plano de um pedido: MSH + PID + ORC/OBR + campos derivados {@code order.*}. */
  Map<String, String> flat(Hl7Fields.Parsed parsed, Hl7Fields.Order order) {
    Map<String, String> m = new LinkedHashMap<>(parsed.msh());
    m.putAll(parsed.pid());
    m.putAll(order.fields());
    String placer = firstNonBlank(m.get("orc.placer_order"), m.get("obr.placer_order"));
    String filler = firstNonBlank(m.get("orc.filler_order"), m.get("obr.filler_order"));
    boolean preferFiller = "filler".equalsIgnoreCase(config.order().idSource());
    m.put("order.id", preferFiller ? firstNonBlank(filler, placer) : firstNonBlank(placer, filler));
    m.put("order.placer", placer);
    m.put("order.filler", filler);
    m.put(
        "order.requesting_cnes",
        cnes(
            m.get("orc.ordering_facility_id"),
            m.get("orc.entering_organization"),
            config.order().defaultRequestingCnes().orElse("")));
    m.put(
        "order.performer_cnes",
        cnes(m.get("msh.sending_facility"), config.order().defaultPerformerCnes().orElse("")));
    m.put(
        "order.priority",
        firstNonBlank(
            m.get("obr.priority"), m.get("obr.quantity_timing_priority"), m.get("orc.priority")));
    m.put(
        "order.requested_at",
        firstNonBlank(
            m.get("obr.requested_datetime"),
            m.get("orc.transaction_datetime"),
            m.get("msh.timestamp")));
    m.put(
        "order.occurred_at",
        firstNonBlank(m.get("orc.transaction_datetime"), m.get("msh.timestamp")));
    m.put(
        "order.reported_at",
        firstNonBlank(
            m.get("obr.results_datetime"),
            m.get("obr.observation_datetime"),
            m.get("msh.timestamp")));
    m.put(
        "order.provider_id",
        firstNonBlank(m.get("orc.ordering_provider_id"), m.get("obr.ordering_provider_id")));
    m.put("order.category", config.order().category());
    return m;
  }

  /** CNS (PID-3 tipo CNS) → CPF → identificador local do LIS/HIS. */
  Map<String, Object> citizenRef(Map<String, String> pid) {
    Map<String, Object> ref = new LinkedHashMap<>();
    String cnsType = config.pid().cnsIdentifierType().toUpperCase(Locale.ROOT);
    String cpfType = config.pid().cpfIdentifierType().toUpperCase(Locale.ROOT);
    String cns = digits(pid.get("pid.identifier." + cnsType));
    String cpf = digits(pid.get("pid.identifier." + cpfType));
    String local = null;
    if (config.pid().inferByLength()) {
      for (String entry : pid.getOrDefault("pid.identifiers", "").split("~")) {
        if (entry.isBlank()) continue;
        String id = entry.split("\\^", -1)[0];
        String d = digits(id);
        if (cns.isEmpty() && d.length() == 15 && d.equals(id.trim())) cns = d;
        else if (cpf.isEmpty() && d.length() == 11 && d.equals(id.trim())) cpf = d;
        else if (local == null) local = id;
      }
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

  private boolean mshCritical(Map<String, String> msh) {
    return config
        .critical()
        .mshField()
        .map(i -> msh.getOrDefault("msh.field_" + i, ""))
        .map(
            v ->
                v.toUpperCase(Locale.ROOT)
                    .contains(config.critical().mshValue().toUpperCase(Locale.ROOT)))
        .orElse(false);
  }

  static BigDecimal numeric(String value) {
    if (value == null || value.isBlank()) return null;
    String v = value.trim().replaceAll("^[<>=]+", "").replace(',', '.');
    try {
      return new BigDecimal(v);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  static String codeSystem(String hl7CodingSystem) {
    String cs = hl7CodingSystem == null ? "" : hl7CodingSystem.trim().toUpperCase(Locale.ROOT);
    if (cs.equals("LN") || cs.equals("LOINC")) return "LOINC";
    if (cs.equals("SIGTAP")) return "SIGTAP";
    return "LOCAL";
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
      b.required(id, "status", p.get("status"));
      if (CanonicalBatch.EXAM_ORDER.equals(batch.entityType())) {
        b.required(id, "requested_at", p.get("requested_at"));
        b.required(id, "exam_code", p.get("exam_code"));
        Map<String, Object> ref = (Map<String, Object>) p.get("citizen_ref");
        if (ref == null || ref.isEmpty()) {
          b.error(
              id, "citizen_ref", "required", "PID sem identificador utilizável (CNS/CPF/local)");
        } else if ("CNS".equals(ref.get("identifier_system"))
            && !String.valueOf(ref.get("identifier_value")).matches("\\d{15}")) {
          b.error(id, "citizen_ref", "format", "CNS deve ter 15 dígitos");
        }
        if (p.get("status") != null && !ORDER_STATUSES.contains(p.get("status").toString())) {
          b.error(id, "status", "enum", "status fora do domínio: " + p.get("status"));
        }
      } else {
        b.required(id, "reported_at", p.get("reported_at"));
        Map<String, Object> target = (Map<String, Object>) p.get("target_ref");
        b.required(
            id,
            "target_ref.source_record_id",
            target == null ? null : target.get("source_record_id"));
        if (p.get("status") != null && !RESULT_STATUSES.contains(p.get("status").toString())) {
          b.error(id, "status", "enum", "status de resultado fora do domínio: " + p.get("status"));
        }
        List<Map<String, Object>> obs =
            (List<Map<String, Object>>) p.getOrDefault("observations", List.of());
        for (Map<String, Object> o : obs) {
          if (!(o.get("value") instanceof Number)) {
            b.error(
                id, "observations", "numeric_only", "observação não numérica não pode ir ao core");
          }
        }
      }
      for (String f : List.of("requesting_cnes", "performer_cnes")) {
        if (p.get(f) != null && !p.get(f).toString().matches("\\d{7}")) {
          b.error(id, f, "format", f + " deve ter 7 dígitos");
        }
      }
    }
    return b.build();
  }
}
