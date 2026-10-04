package br.gov.sus.nexus.connectors.sdk.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lote canônico produzido por {@link Connector#transform(RawMessage)}.
 *
 * <p>Tipos de entidade conhecidos pelo SDK: {@link #CITIZEN}, {@link #APPOINTMENT}, {@link
 * #HEALTH_UNIT}, {@link #CODE}. {@code attributes} carrega metadados do lote (ex.: {@code system} e
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

  /** Atributo preenchido pelo pipeline com o correlation_id da integration_message. */
  public static final String CORRELATION_ID = "correlation_id";

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
