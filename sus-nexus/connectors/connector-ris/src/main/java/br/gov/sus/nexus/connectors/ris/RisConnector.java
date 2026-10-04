package br.gov.sus.nexus.connectors.ris;

import br.gov.sus.nexus.connectors.sdk.api.CanonicalBatch;
import br.gov.sus.nexus.connectors.sdk.api.CanonicalRecord;
import br.gov.sus.nexus.connectors.sdk.api.ConnectorDescriptor;
import br.gov.sus.nexus.connectors.sdk.api.ConnectorException;
import br.gov.sus.nexus.connectors.sdk.api.HealthStatus;
import br.gov.sus.nexus.connectors.sdk.api.RawMessage;
import br.gov.sus.nexus.connectors.sdk.api.ValidationReport;
import br.gov.sus.nexus.connectors.sdk.core.dto.ExamResultRegistration;
import br.gov.sus.nexus.connectors.sdk.hl7.Hl7Dates;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * Conector RIS/PACS (imagem). Três fontes:
 *
 * <ul>
 *   <li>{@code ORM^O01} → {@code exam_order} ({@code POST /exams/orders}, categoria {@code
 *       imaging}; OBR-4 → SIGTAP via coding system ou catálogo local, senão {@code LOCAL});
 *   <li>{@code ORU^R01} (laudo) → {@code exam_result} by-source: só {@code document_ref} para a raw
 *       zone, {@code critical} por OBX-8/marcador; <b>nenhum texto do laudo</b> e nenhuma
 *       observação vão ao core;
 *   <li>metadados DICOM exportados do PACS (JSON/CSV) → {@code exam_result} by-source com {@code
 *       document_content_type=application/dicom-study-ref}, {@code document_ref =
 *       dicom://<AE>/<StudyInstanceUID>} e {@code document_sha256} = SHA-256 do StudyInstanceUID. A
 *       imagem nunca é copiada: só a referência ao estudo.
 * </ul>
 */
@ApplicationScoped
public class RisConnector extends AbstractConnector {

  public static final String SOURCE_SYSTEM = "RIS";
  public static final String CONNECTOR_ID = "connector-ris";
  public static final String METRIC_ACK = "connector_ris_ack_total";
  public static final String METRIC_MESSAGES = "connector_ris_messages_total";
  public static final String METRIC_REPORT_TEXT_SKIPPED = "connector_ris_report_text_skipped_total";
  public static final String META_SOURCE = "source";
  public static final String META_SOURCE_DICOM = "dicom";
  public static final String DICOM_JSON = "application/json";
  public static final String DICOM_CSV = "text/csv";

  private static final Logger LOG = Logger.getLogger(RisConnector.class);
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

  private final RisConfig config;
  private Hl7Parser parser;
  private Hl7Fields fields;
  private ExamCatalog catalog;
  private MappingVersion orderMapping;
  private MappingVersion resultMapping;
  private MappingVersion dicomMapping;
  private ConnectorDescriptor descriptor;
  private Set<String> criticalFlags;

  @Inject
  public RisConnector(RisConfig config) {
    this.config = config;
  }

  @PostConstruct
  void init() {
    this.parser = new Hl7Parser();
    this.fields = new Hl7Fields(ZoneId.of(config.zone()));
    this.catalog = ExamCatalog.load(config.catalog().path());
    this.orderMapping = MappingLoader.fromClasspath(config.mapping().order());
    this.resultMapping = MappingLoader.fromClasspath(config.mapping().result());
    this.dicomMapping = MappingLoader.fromClasspath(config.mapping().dicom());
    this.criticalFlags = new HashSet<>();
    config.critical().flags().forEach(f -> criticalFlags.add(f.trim().toUpperCase(Locale.ROOT)));
    this.descriptor =
        ConnectorDescriptor.builder()
            .connectorId(CONNECTOR_ID)
            .connectorVersion("0.1.0")
            .sourceSystem(SOURCE_SYSTEM)
            .supportedSourceVersions(
                List.of(
                    config.sourceVersion(),
                    "HL7 v2.3",
                    "HL7 v2.3.1",
                    "HL7 v2.4",
                    "HL7 v2.5",
                    "HL7 v2.5.1",
                    "DICOM study metadata export (JSON/CSV)"))
            .supportedProtocols(List.of("mllp", "file-hl7", "file-dicom-metadata"))
            .supportedEntities(List.of(CanonicalBatch.EXAM_ORDER, CanonicalBatch.EXAM_RESULT))
            .authenticationMethod(ConnectorDescriptor.AuthenticationMethod.NONE)
            .requiredNetworkAccess(
                List.of(
                    "core-municipal:8080",
                    "RIS → connector-ris:" + config.mllp().port() + " (MLLP, rede interna)",
                    "PACS → volume " + config.dicom().inputDir() + " (export de metadados)"))
            .dataClassification(ConnectorDescriptor.DataClassification.HIGHLY_RESTRICTED)
            .pollingOrEventMode(ConnectorDescriptor.IngestionMode.HYBRID)
            .rateLimitPolicy(new ConnectorDescriptor.RateLimitPolicy(1200, 4))
            .fieldMappingVersion(orderMapping.version())
            .testSuiteVersion("1.0.0")
            .owner("equipe-integracao@sus-nexus")
            .supportSla(
                new ConnectorDescriptor.SupportSla(
                    "gold", Duration.ofHours(1), Duration.ofHours(8)))
            .build();
    LOG.infof(
        "catálogo %s v%s com %d código(s) local→SIGTAP",
        catalog.name(), catalog.version(), catalog.size());
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

  ExamCatalog catalog() {
    return catalog;
  }

  @Override
  public HealthStatus healthCheck() {
    Map<String, String> details = new LinkedHashMap<>();
    details.put(
        "mllp",
        config.mllp().enabled() ? config.mllp().host() + ":" + config.mllp().port() : "disabled");
    details.put("input_dir", config.file().inputDir());
    details.put("dicom_dir", config.dicom().enabled() ? config.dicom().inputDir() : "disabled");
    details.put("catalog", catalog.name() + "@" + catalog.version() + " (" + catalog.size() + ")");
    boolean ok = config.mllp().enabled() || Files.isDirectory(Path.of(config.file().inputDir()));
    return new HealthStatus(ok ? HealthStatus.State.HEALTHY : HealthStatus.State.DEGRADED, details);
  }

  static String entityTypeOf(String type, String trigger) {
    if ("ORM".equalsIgnoreCase(type) && "O01".equalsIgnoreCase(trigger))
      return CanonicalBatch.EXAM_ORDER;
    if ("ORU".equalsIgnoreCase(type) && "R01".equalsIgnoreCase(trigger))
      return CanonicalBatch.EXAM_RESULT;
    return null;
  }

  /** Nº do pedido (Accession Number): placer ou filler conforme {@code ris.order.id-source}. */
  String orderIdOf(Message message) {
    boolean filler = "filler".equalsIgnoreCase(config.order().idSource());
    return filler
        ? Hl7Receiver.firstTerser(message, "/.ORC-3-1", "/.OBR-3-1", "/.ORC-2-1", "/.OBR-2-1")
        : Hl7Receiver.firstTerser(message, "/.ORC-2-1", "/.OBR-2-1", "/.ORC-3-1", "/.OBR-3-1");
  }

  @Override
  public CanonicalBatch transform(RawMessage raw) {
    if (META_SOURCE_DICOM.equals(raw.metadata().get(META_SOURCE))) {
      return dicomStudies(raw);
    }
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
      boolean critical = mshCritical || obrCritical(order.fields());
      int skipped = 0;
      for (Hl7Fields.Obx obx : order.observations()) {
        skipped++;
        String flag = obx.abnormalFlag().toUpperCase(Locale.ROOT);
        if (!flag.isBlank() && criticalFlags.contains(flag)) critical = true;
        metrics.counter(
            METRIC_REPORT_TEXT_SKIPPED,
            1,
            "connector_id",
            CONNECTOR_ID,
            "value_type",
            obx.valueType().isBlank() ? "unknown" : obx.valueType());
      }
      // laudo de imagem: nunca texto, nunca observações — só a referência segura à raw zone
      payload.put("observations", List.of());
      payload.put("critical", critical);
      payload.put("document_ref", "raw://" + CONNECTOR_ID + "/sha256/" + sha);
      payload.put("document_content_type", Hl7Receiver.HL7_CONTENT_TYPE);
      payload.put("document_sha256", sha);
      LOG.debugf(
          "ORU %s: %d OBX do laudo mantidos apenas na raw zone (critical=%s)",
          parsed.msh().get("msh.control_id"), skipped, critical);
      records.add(
          new CanonicalRecord(source.get("order.id"), parsed.msh().get("msh.control_id"), payload));
    }
    return new CanonicalBatch(
        CanonicalBatch.EXAM_RESULT,
        resultMapping.version(),
        records,
        Map.of("source_record_id", raw.sourceRecordId()));
  }

  /** Metadados DICOM (JSON/CSV) → um resultado por estudo, by-source pelo Accession Number. */
  private CanonicalBatch dicomStudies(RawMessage raw) {
    List<Map<String, String>> rows;
    try {
      rows =
          DicomStudyReader.read(
              raw.content(), raw.contentType(), Charset.forName(config.dicom().csvCharset()));
    } catch (RuntimeException e) {
      throw ConnectorException.permanent(
          "transform", "export DICOM inválido: " + e.getMessage(), e);
    }
    String accessionKey = DicomStudyReader.key(config.dicom().accessionField());
    List<CanonicalRecord> records = new ArrayList<>();
    for (Map<String, String> row : rows) {
      Map<String, String> source = new LinkedHashMap<>(row);
      String uid = source.getOrDefault("dicom.studyinstanceuid", "").trim();
      String accession = source.getOrDefault(accessionKey, "").trim();
      if (uid.isBlank() || accession.isBlank()) {
        LOG.warnf(
            "estudo DICOM ignorado em %s: sem StudyInstanceUID/AccessionNumber",
            raw.sourceRecordId());
        continue;
      }
      source.put("dicom.accessionnumber", accession);
      String ae = source.getOrDefault("dicom.aetitle", "").trim();
      if (ae.isBlank()) ae = config.dicom().aeTitle();
      source.put("study.document_ref", ExamResultRegistration.DICOM_REF_SCHEME + ae + "/" + uid);
      source.put("study.document_sha256", Hashes.sha256Hex(uid));
      source.put(
          "study.reported_at",
          firstNonBlank(
              Hl7Dates.toIso(
                  digits(source.getOrDefault("dicom.studydate", ""))
                      + digits(source.getOrDefault("dicom.studytime", ""))
                          .replaceAll("^(\\d{6}).*", "$1"),
                  ZoneId.of(config.zone())),
              raw.receivedAt().toString()));
      source.put("study.status", config.dicom().status());
      source.put(
          "study.performer_cnes",
          cnes(
              source.getOrDefault("dicom.performercnes", ""),
              config.order().defaultPerformerCnes().orElse("")));
      Map<String, Object> payload = MappingEngine.apply(dicomMapping, source);
      payload.put("observations", List.of());
      payload.putIfAbsent("critical", false);
      records.add(new CanonicalRecord(accession, uid, payload));
    }
    return new CanonicalBatch(
        CanonicalBatch.EXAM_RESULT,
        dicomMapping.version(),
        records,
        Map.of("source_record_id", raw.sourceRecordId(), "source", META_SOURCE_DICOM));
  }

  /** Modelo plano de um pedido: MSH + PID + PV1 + ORC/OBR + campos derivados {@code order.*}. */
  Map<String, String> flat(Hl7Fields.Parsed parsed, Hl7Fields.Order order) {
    Map<String, String> m = parsed.flat();
    m.putAll(order.fields());
    String placer = firstNonBlank(m.get("orc.placer_order"), m.get("obr.placer_order"));
    String filler = firstNonBlank(m.get("orc.filler_order"), m.get("obr.filler_order"));
    boolean preferFiller = "filler".equalsIgnoreCase(config.order().idSource());
    m.put("order.id", preferFiller ? firstNonBlank(filler, placer) : firstNonBlank(placer, filler));
    m.put("order.placer", placer);
    m.put("order.filler", filler);
    String[] code = examCode(m.get("obr.universal_id"), m.get("obr.universal_coding_system"));
    m.put("order.exam_code", code[0]);
    m.put("order.code_system", code[1]);
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

  /** {exam_code, code_system}: SIGTAP direto, catálogo local→SIGTAP, LOINC (LN) ou LOCAL. */
  String[] examCode(String obr4Id, String codingSystem) {
    String id = obr4Id == null ? "" : obr4Id.trim();
    String cs = codingSystem == null ? "" : codingSystem.trim().toUpperCase(Locale.ROOT);
    if (cs.equals("SIGTAP") && id.matches("\\d{10}")) return new String[] {id, "SIGTAP"};
    String sigtap = catalog.sigtap(id).orElse(null);
    if (sigtap != null) return new String[] {sigtap, "SIGTAP"};
    if (cs.equals("LN") || cs.equals("LOINC")) return new String[] {id, "LOINC"};
    return new String[] {id, "LOCAL"};
  }

  /** CNS (PID-3 tipo CNS) → CPF → identificador local. */
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
        .map(v -> containsIgnoreCase(v, config.critical().mshValue()))
        .orElse(false);
  }

  private boolean obrCritical(Map<String, String> obr) {
    return config
        .critical()
        .obrField()
        .map(
            i ->
                switch (i) {
                  case 5 -> obr.getOrDefault("obr.priority", "");
                  case 13 -> obr.getOrDefault("obr.clinical_info", "");
                  default -> "";
                })
        .map(v -> containsIgnoreCase(v, config.critical().obrValue()))
        .orElse(false);
  }

  private static boolean containsIgnoreCase(String text, String needle) {
    return text != null
        && !needle.isBlank()
        && text.toUpperCase(Locale.ROOT).contains(needle.toUpperCase(Locale.ROOT));
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
        if ("SIGTAP".equals(p.get("code_system"))
            && !String.valueOf(p.get("exam_code")).matches("\\d{10}")) {
          b.error(id, "exam_code", "format", "código SIGTAP deve ter 10 dígitos");
        }
        if (p.get("status") != null && !ORDER_STATUSES.contains(p.get("status").toString())) {
          b.error(id, "status", "enum", "status fora do domínio: " + p.get("status"));
        }
      } else {
        b.required(id, "reported_at", p.get("reported_at"));
        b.required(id, "document_ref", p.get("document_ref"));
        b.required(id, "document_sha256", p.get("document_sha256"));
        Map<String, Object> target = (Map<String, Object>) p.get("target_ref");
        b.required(
            id,
            "target_ref.source_record_id",
            target == null ? null : target.get("source_record_id"));
        if (p.get("status") != null && !RESULT_STATUSES.contains(p.get("status").toString())) {
          b.error(id, "status", "enum", "status de resultado fora do domínio: " + p.get("status"));
        }
        List<?> obs = (List<?>) p.getOrDefault("observations", List.of());
        if (!obs.isEmpty()) {
          b.error(
              id,
              "observations",
              "imaging_no_observations",
              "laudo de imagem não leva observações ao core");
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
