package br.gov.sus.nexus.fhir.mapping;

import static br.gov.sus.nexus.fhir.mapping.MappingSupport.date;
import static br.gov.sus.nexus.fhir.mapping.MappingSupport.extensionCode;
import static br.gov.sus.nexus.fhir.mapping.MappingSupport.extensionString;
import static br.gov.sus.nexus.fhir.mapping.MappingSupport.isBlank;
import static br.gov.sus.nexus.fhir.mapping.MappingSupport.lower;

import br.gov.sus.nexus.fhir.FhirConstants;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.hl7.fhir.r4.model.BooleanType;
import org.hl7.fhir.r4.model.CarePlan;
import org.hl7.fhir.r4.model.CarePlan.CarePlanActivityComponent;
import org.hl7.fhir.r4.model.CarePlan.CarePlanActivityDetailComponent;
import org.hl7.fhir.r4.model.CarePlan.CarePlanActivityStatus;
import org.hl7.fhir.r4.model.CarePlan.CarePlanIntent;
import org.hl7.fhir.r4.model.CarePlan.CarePlanStatus;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.IntegerType;
import org.hl7.fhir.r4.model.Period;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.StringType;

/**
 * Canônico {@code CarePlan} → FHIR {@code CarePlan} com uma {@code activity} por {@code items[]}.
 *
 * <ul>
 *   <li>Status: active→active, on_hold→on-hold, completed→completed, cancelled→revoked (canônico
 *       íntegro em {@code care-plan-status}); {@code intent=plan}; {@code category} = linha de
 *       cuidado ({@code care-line}); {@code title} derivado da linha de cuidado; {@code period} =
 *       created_at..(updated_at quando encerrado).
 *   <li>{@code activity.detail}: {@code code} = {@code code/code_system} do item quando houver,
 *       senão {@code kind} no CodeSystem municipal {@code care-plan-item-kind}; {@code description}
 *       = título; status planned→not-started, scheduled→scheduled, done→completed, missed→stopped,
 *       cancelled→cancelled (canônico em {@code care-plan-item-status}); {@code
 *       scheduledPeriod.end} = expected_by; extensões {@code care-plan-item-id}, {@code
 *       item-overdue}; {@code detail.performer}/evidência não são projetados (referência opaca).
 *   <li>{@code author} = profissional responsável (PractitionerRole lógico); {@code contributor}
 *       Organization por CNES (resolvida na projeção); {@code supportingInfo} = origem (episódio
 *       hospitalar → Encounter; {@code basedOn} só admite CarePlan); extensões protocol-id/version,
 *       open-gaps, team-ine.
 * </ul>
 */
@ApplicationScoped
public class CarePlanMapper {

  @Inject MapperSettings settings;

  public CarePlanMapper() {}

  public CarePlanMapper(MapperSettings settings) {
    this.settings = settings;
  }

  public CarePlan map(CanonicalCarePlan c) {
    CarePlan plan = new CarePlan();
    plan.setId(CanonicalIds.toFhirId(c.id()));
    plan.getMeta().addProfile(settings.carePlanProfile());
    plan.addIdentifier(
        MappingSupport.identifier(FhirConstants.SYSTEM_MUNICIPAL_CARE_PLAN_ID, c.id()));
    plan.setStatus(status(c.status()));
    extensionCode(plan, FhirConstants.EXT_CARE_PLAN_STATUS, c.status());
    plan.setIntent(CarePlanIntent.PLAN);
    if (!isBlank(c.careLine())) {
      plan.addCategory(
          new CodeableConcept()
              .addCoding(new Coding().setSystem(FhirConstants.CS_CARE_LINE).setCode(c.careLine()))
              .setText(c.careLine()));
      plan.setTitle("Plano de cuidado: " + c.careLine());
    } else {
      plan.setTitle("Plano de cuidado");
    }
    plan.setSubject(MappingSupport.patientRef(c.citizenId()));
    if (c.createdAt() != null) {
      plan.setCreatedElement(new DateTimeType(date(c.createdAt())));
      Period period = new Period().setStart(date(c.createdAt()));
      if (c.updatedAt() != null
          && (plan.getStatus() == CarePlanStatus.COMPLETED
              || plan.getStatus() == CarePlanStatus.REVOKED)) {
        period.setEnd(date(c.updatedAt()));
      }
      plan.setPeriod(period);
    }
    if (!isBlank(c.responsibleProfessionalId())) {
      plan.setAuthor(
          MappingSupport.logical(
              "PractitionerRole",
              FhirConstants.SYSTEM_MUNICIPAL_PROFESSIONAL_ID,
              c.responsibleProfessionalId()));
    }
    if (!isBlank(c.healthUnitCnes())) {
      plan.addContributor(MappingSupport.organizationByCnes(c.healthUnitCnes()));
    }
    if (c.origin() != null && !isBlank(c.origin().id())) {
      Reference basedOn =
          MappingSupport.referenceFromPrefixedId(c.origin().id())
              .orElseGet(
                  () ->
                      MappingSupport.logical(
                          null,
                          FhirConstants.SUS_NEXUS_BASE
                              + "/NamingSystem/origin-"
                              + lower(c.origin().kind()),
                          c.origin().id()));
      basedOn.setDisplay(lower(c.origin().kind()));
      plan.addSupportingInfo(basedOn);
    }
    if (!isBlank(c.closedReason())) {
      plan.setDescription(c.closedReason());
    }
    if (c.items() != null) {
      for (CanonicalCarePlan.Item item : c.items()) {
        plan.addActivity(activity(item));
      }
    }
    extensionString(plan, FhirConstants.EXT_PROTOCOL_ID, c.protocolId());
    extensionString(plan, FhirConstants.EXT_PROTOCOL_VERSION, c.protocolVersion());
    extensionString(plan, FhirConstants.EXT_TEAM_INE, c.teamIne());
    if (c.openGaps() != null) {
      plan.addExtension(FhirConstants.EXT_OPEN_GAPS, new IntegerType(c.openGaps()));
    }
    return plan;
  }

  private CarePlanActivityComponent activity(CanonicalCarePlan.Item item) {
    CarePlanActivityComponent activity = new CarePlanActivityComponent();
    CarePlanActivityDetailComponent detail = activity.getDetail();
    CodeableConcept code = new CodeableConcept();
    if (!isBlank(item.code())) {
      code.addCoding(
          MappingSupport.procedureCoding(settings, item.codeSystem(), item.code(), null));
    }
    code.addCoding(
        new Coding()
            .setSystem(FhirConstants.CS_CARE_PLAN_ITEM_KIND)
            .setCode(isBlank(item.kind()) ? "other" : item.kind()));
    if (!isBlank(item.title())) {
      code.setText(item.title());
      detail.setDescription(item.title());
    }
    detail.setCode(code);
    detail.setStatus(itemStatus(item.status()));
    if (item.expectedBy() != null) {
      detail.setScheduled(new Period().setEnd(date(item.expectedBy())));
    }
    if (!isBlank(item.id())) {
      detail.addExtension(FhirConstants.EXT_CARE_PLAN_ITEM_ID, new StringType(item.id()));
    }
    if (!isBlank(item.status())) {
      detail.addExtension(
          FhirConstants.EXT_CARE_PLAN_ITEM_STATUS,
          new org.hl7.fhir.r4.model.CodeType(item.status()));
    }
    if (item.overdue() != null) {
      detail.addExtension(FhirConstants.EXT_ITEM_OVERDUE, new BooleanType(item.overdue()));
    }
    if (item.periodicityDays() != null) {
      detail.addExtension(
          FhirConstants.SUS_NEXUS_BASE + "/StructureDefinition/periodicity-days",
          new IntegerType(item.periodicityDays()));
    }
    if (item.performedAt() != null) {
      detail.addExtension(
          FhirConstants.SUS_NEXUS_BASE + "/StructureDefinition/performed-at",
          new DateTimeType(date(item.performedAt())));
    }
    return activity;
  }

  public static CarePlanStatus status(String canonical) {
    return switch (lower(canonical)) {
      case "active" -> CarePlanStatus.ACTIVE;
      case "on_hold" -> CarePlanStatus.ONHOLD;
      case "completed" -> CarePlanStatus.COMPLETED;
      case "cancelled" -> CarePlanStatus.REVOKED;
      default -> throw new IllegalArgumentException("Status de plano de cuidado desconhecido");
    };
  }

  public static CarePlanActivityStatus itemStatus(String canonical) {
    return switch (lower(canonical)) {
      case "", "planned" -> CarePlanActivityStatus.NOTSTARTED;
      case "scheduled" -> CarePlanActivityStatus.SCHEDULED;
      case "done" -> CarePlanActivityStatus.COMPLETED;
      case "missed" -> CarePlanActivityStatus.STOPPED;
      case "cancelled" -> CarePlanActivityStatus.CANCELLED;
      default -> throw new IllegalArgumentException("Status de item de plano desconhecido");
    };
  }
}
