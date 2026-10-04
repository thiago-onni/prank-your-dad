package br.gov.sus.nexus.fhir.projection;

import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Incoming;

/**
 * Consumidor SmallRye Reactive Messaging dos tópicos de domínio projetados em FHIR. Cada canal
 * corresponde a um tópico ({@code application.properties}); em {@code %test} usa o conector em
 * memória. Falhas propagam (nack) e seguem a {@code failure-strategy} configurada.
 */
@ApplicationScoped
public class ProjectionEventConsumer {

  @Inject ProjectionEventHandler handler;

  @Incoming("schedule-appointment")
  @Blocking
  public void appointment(String payload) {
    handler.handle(ProjectionEventHandler.TOPIC_APPOINTMENT, payload);
  }

  @Incoming("task")
  @Blocking
  public void task(String payload) {
    handler.handle(ProjectionEventHandler.TOPIC_TASK, payload);
  }

  @Incoming("regulation-request")
  @Blocking
  public void regulationRequest(String payload) {
    handler.handle(ProjectionEventHandler.TOPIC_REGULATION_REQUEST, payload);
  }

  @Incoming("regulation-status")
  @Blocking
  public void regulationStatus(String payload) {
    handler.handle(ProjectionEventHandler.TOPIC_REGULATION_STATUS, payload);
  }

  @Incoming("exam-order")
  @Blocking
  public void examOrder(String payload) {
    handler.handle(ProjectionEventHandler.TOPIC_EXAM_ORDER, payload);
  }

  @Incoming("aps-encounter")
  @Blocking
  public void apsEncounter(String payload) {
    handler.handle(ProjectionEventHandler.TOPIC_APS_ENCOUNTER, payload);
  }

  // ---- FHIR-3 ---------------------------------------------------------------------------------

  @Incoming("exam-result")
  @Blocking
  public void examResult(String payload) {
    handler.handle(ProjectionEventHandler.TOPIC_EXAM_RESULT, payload);
  }

  @Incoming("hospital-adt")
  @Blocking
  public void hospitalAdt(String payload) {
    handler.handle(ProjectionEventHandler.TOPIC_HOSPITAL_ADT, payload);
  }

  @Incoming("hospital-discharge")
  @Blocking
  public void hospitalDischarge(String payload) {
    handler.handle(ProjectionEventHandler.TOPIC_HOSPITAL_DISCHARGE, payload);
  }

  @Incoming("careplan")
  @Blocking
  public void carePlan(String payload) {
    handler.handle(ProjectionEventHandler.TOPIC_CAREPLAN, payload);
  }

  @Incoming("caregap")
  @Blocking
  public void careGap(String payload) {
    handler.handle(ProjectionEventHandler.TOPIC_CAREGAP, payload);
  }
}
