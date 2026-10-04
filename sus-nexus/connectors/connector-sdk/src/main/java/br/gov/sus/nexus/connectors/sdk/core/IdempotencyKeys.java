package br.gov.sus.nexus.connectors.sdk.core;

import br.gov.sus.nexus.connectors.sdk.api.CanonicalRecord;
import br.gov.sus.nexus.connectors.sdk.util.Hashes;
import java.util.List;
import java.util.stream.Collectors;

/** {@code Idempotency-Key} = SHA-256(source_record_id + ":" + source_record_version). */
public final class IdempotencyKeys {

  private IdempotencyKeys() {}

  public static String of(String sourceRecordId, String sourceRecordVersion) {
    return Hashes.sha256Hex(
        sourceRecordId + ":" + (sourceRecordVersion == null ? "" : sourceRecordVersion));
  }

  public static String of(CanonicalRecord record) {
    return of(record.sourceRecordId(), record.sourceRecordVersion());
  }

  /** Chave de lote: hash da concatenação ordenada das chaves individuais. */
  public static String ofBatch(List<CanonicalRecord> records) {
    String joined =
        records.stream()
            .map(
                r ->
                    r.sourceRecordId()
                        + ":"
                        + (r.sourceRecordVersion() == null ? "" : r.sourceRecordVersion()))
            .sorted()
            .collect(Collectors.joining("|"));
    return Hashes.sha256Hex(joined);
  }
}
