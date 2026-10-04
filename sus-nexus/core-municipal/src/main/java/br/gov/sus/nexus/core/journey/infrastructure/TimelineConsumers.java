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
 * journey-task-in}), {@code sus.regulation.request.v1}/{@code status.v1} ({@code
 * journey-regulation-*-in}) e {@code sus.exam.order.v1}/{@code result.v1} ({@code
 * journey-exam-*-in}), {@code sus.hospital.adt.v1}/{@code discharge.v1} ({@code
 * journey-hospital-*-in}) e {@code sus.careplan.v1}/{@code sus.caregap.v1} ({@code
 * journey-careplan-in}/{@code journey-caregap-in}). Idempotentes via {@code event_inbox} (grupo
 * {@code core-journey}).
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

  @Incoming("journey-regulation-request-in")
  @Blocking
  public void onRegulationRequest(String payload) {
    processor.process(payload, CONSUMER_GROUP, projector::projectRegulation);
  }

  @Incoming("journey-regulation-status-in")
  @Blocking
  public void onRegulationStatus(String payload) {
    processor.process(payload, CONSUMER_GROUP, projector::projectRegulation);
  }

  @Incoming("journey-exam-order-in")
  @Blocking
  public void onExamOrder(String payload) {
    processor.process(payload, CONSUMER_GROUP, projector::projectExam);
  }

  @Incoming("journey-exam-result-in")
  @Blocking
  public void onExamResult(String payload) {
    processor.process(payload, CONSUMER_GROUP, projector::projectExam);
  }

  @Incoming("journey-hospital-adt-in")
  @Blocking
  public void onHospitalAdt(String payload) {
    processor.process(payload, CONSUMER_GROUP, projector::projectHospital);
  }

  @Incoming("journey-hospital-discharge-in")
  @Blocking
  public void onHospitalDischarge(String payload) {
    processor.process(payload, CONSUMER_GROUP, projector::projectHospital);
  }

  @Incoming("journey-careplan-in")
  @Blocking
  public void onCarePlan(String payload) {
    processor.process(payload, CONSUMER_GROUP, projector::projectCarePlan);
  }

  @Incoming("journey-caregap-in")
  @Blocking
  public void onCareGap(String payload) {
    processor.process(payload, CONSUMER_GROUP, projector::projectCarePlan);
  }
}
