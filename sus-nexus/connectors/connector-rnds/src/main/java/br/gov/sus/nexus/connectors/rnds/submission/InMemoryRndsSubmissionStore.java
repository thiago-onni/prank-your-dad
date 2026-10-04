package br.gov.sus.nexus.connectors.rnds.submission;

import br.gov.sus.nexus.connectors.sdk.api.Period;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Store em memória (dev/test; não sobrevive a reinício). */
public class InMemoryRndsSubmissionStore implements RndsSubmissionStore {

  private final Map<String, RndsSubmission> byEvent = new ConcurrentHashMap<>();

  @Override
  public boolean claim(RndsSubmission submission) {
    return byEvent.putIfAbsent(submission.eventId(), submission) == null;
  }

  @Override
  public Optional<RndsSubmission> findByEventId(String eventId) {
    return Optional.ofNullable(byEvent.get(eventId));
  }

  @Override
  public RndsSubmission save(RndsSubmission submission) {
    byEvent.put(submission.eventId(), submission);
    return submission;
  }

  @Override
  public List<RndsSubmission> list(String model, Period period) {
    return byEvent.values().stream()
        .filter(s -> model == null || model.equals(s.model()))
        .filter(s -> period == null || period.contains(s.createdAt()))
        .toList();
  }

  @Override
  public void clear() {
    byEvent.clear();
  }
}
