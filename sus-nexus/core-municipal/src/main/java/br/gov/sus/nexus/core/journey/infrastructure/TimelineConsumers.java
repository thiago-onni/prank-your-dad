package br.gov.sus.nexus.core.journey.infrastructure;

import br.gov.sus.nexus.core.journey.application.TimelineProjector;
import br.gov.sus.nexus.core.platform.events.InboundEventProcessor;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Incoming;

/**
 * Consumidores que alimentam a timeline: {@code sus.identity.citizen.v1} ({@code
 * journey-identity-in}), {@code sus.identity.merge.v1} ({@code journey-merge-in}), {@code
 * sus.schedule.appointment.v1} ({@code journey-appointment-in}) e {@code sus.task.v1} ({@code
 * journey-task-in}). Idempotentes via {@code event_inbox} (grupo {@code core-journey}).
 */
@ApplicationScoped
public class TimelineConsumers {

  public static final String CONSUMER_GROUP = "core-journey";

  @Inject InboundEventProcessor processor;
  @Inject TimelineProjector projector;

  @Incoming("journey-identity-in")
  @Blocking
  public void onCitizen(String payload) {
    processor.process(payload, CONSUMER_GROUP, projector::projectCitizen);
  }

  @Incoming("journey-merge-in")
  @Blocking
  public void onMerge(String payload) {
    processor.process(payload, CONSUMER_GROUP, projector::projectMerge);
  }

  @Incoming("journey-appointment-in")
  @Blocking
  public void onAppointment(String payload) {
    processor.process(payload, CONSUMER_GROUP, projector::projectAppointment);
  }

  @Incoming("journey-task-in")
  @Blocking
  public void onTask(String payload) {
    processor.process(payload, CONSUMER_GROUP, projector::projectTask);
  }
}
