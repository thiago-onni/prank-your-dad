package br.gov.sus.nexus.connectors.rnds.submission;

import br.gov.sus.nexus.connectors.sdk.api.Period;
import java.util.List;
import java.util.Optional;

/** Persistência de {@code rnds_submission} (memória ou JDBC). */
public interface RndsSubmissionStore {

  /** Insere se o {@code event_id} ainda não existe; {@code false} quando já existe. */
  boolean claim(RndsSubmission submission);

  Optional<RndsSubmission> findByEventId(String eventId);

  /**
   * Última submissão {@code accepted} do mesmo registro de origem ({@code model} + {@code
   * source_id}) de OUTRO evento: usada para reenviar um resultado retificado como substituição
   * ({@code Composition.relatesTo} {@code replaces} {@code Composition/<protocolo RNDS>}).
   */
  Optional<RndsSubmission> findLatestAccepted(String model, String sourceId, String exceptEventId);

  /** Upsert por {@code event_id}. */
  RndsSubmission save(RndsSubmission submission);

  /** Submissões do modelo criadas no período ({@code model} nulo = todos). */
  List<RndsSubmission> list(String model, Period period);

  /** Remove tudo (testes/ambiente de homologação). */
  void clear();
}
