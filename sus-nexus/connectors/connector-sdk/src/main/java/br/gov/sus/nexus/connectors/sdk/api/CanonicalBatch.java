package br.gov.sus.nexus.connectors.sdk.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lote canônico produzido por {@link Connector#transform(RawMessage)}.
 *
 * <p>Tipos de entidade conhecidos pelo SDK: {@link #CITIZEN}, {@link #APPOINTMENT}, {@link
 * #HEALTH_UNIT}, {@link #CODE}, {@link #REGULATION_REQUEST}, {@link #REGULATION_STATUS}, {@link
 * #PROVIDER_CAPACITY}, {@link #EXAM_ORDER}, {@link #EXAM_RESULT}, {@link #HOSPITAL_MOVEMENT},
 * {@link #HOSPITAL_DISCHARGE}. {@code attributes} carrega metadados do lote (ex.: {@code system} e
 * {@code competence} para terminologia).
 */
public record CanonicalBatch(
    String entityType,
    String mappingVersion,
    List<CanonicalRecord> records,
    Map<String, String> attributes) {

  public static final String CITIZEN = "citizen";
  public static final String APPOINTMENT = "appointment";
  public static final String HEALTH_UNIT = "health_unit";
  public static final String CODE = "code";
  public static final String REGULATION_REQUEST = "regulation_request";
  public static final String REGULATION_STATUS = "regulation_status";
  public static final String PROVIDER_CAPACITY = "provider_capacity";
  public static final String EXAM_ORDER = "exam_order";
  public static final String EXAM_RESULT = "exam_result";
  public static final String HOSPITAL_MOVEMENT = "hospital_movement";
  public static final String HOSPITAL_DISCHARGE = "hospital_discharge";

  /**
   * Registro de produção (BPA-C/BPA-I/APAC/AIH) publicado no tópico de ingestão {@code
   * sus.ingest.production.v1} (data = {@code ProductionRecordRegistration}).
   */
  public static final String PRODUCTION_RECORD = "production_record";

  /**
   * Retorno do processamento oficial SIA/SIH (rejeição/glosa, aceite, pagamento) em {@code
   * sus.ingest.production.v1} (data = {@code ProductionOutcomeRegistration}).
   */
  public static final String PRODUCTION_OUTCOME = "production_outcome";

  /** Atributo preenchido pelo pipeline com o correlation_id da integration_message. */
  public static final String CORRELATION_ID = "correlation_id";

  /**
   * Atributo preenchido pelo pipeline ({@code "true"}) quando o lote vem de um reprocessamento
   * (KAF-012): publicadores em barramento marcam {@code replay=true} no envelope.
   */
  public static final String REPROCESS = "reprocess";

  public CanonicalBatch {
    records = List.copyOf(records);
    attributes =
        attributes == null
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
  }

  public int size() {
    return records.size();
  }

  public boolean isEmpty() {
    return records.isEmpty();
  }
}
