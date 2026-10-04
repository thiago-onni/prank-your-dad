package br.gov.sus.nexus.connectors.lis;

import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.model.Composite;
import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.model.Primitive;
import ca.uhn.hl7v2.model.Type;
import ca.uhn.hl7v2.model.Varies;
import ca.uhn.hl7v2.model.v25.datatype.CE;
import ca.uhn.hl7v2.model.v25.datatype.CX;
import ca.uhn.hl7v2.model.v25.datatype.SN;
import ca.uhn.hl7v2.model.v25.datatype.XCN;
import ca.uhn.hl7v2.model.v25.datatype.XON;
import ca.uhn.hl7v2.model.v25.group.ORM_O01_ORDER;
import ca.uhn.hl7v2.model.v25.group.ORU_R01_ORDER_OBSERVATION;
import ca.uhn.hl7v2.model.v25.group.ORU_R01_PATIENT_RESULT;
import ca.uhn.hl7v2.model.v25.message.ORM_O01;
import ca.uhn.hl7v2.model.v25.message.ORU_R01;
import ca.uhn.hl7v2.model.v25.segment.MSH;
import ca.uhn.hl7v2.model.v25.segment.OBR;
import ca.uhn.hl7v2.model.v25.segment.OBX;
import ca.uhn.hl7v2.model.v25.segment.ORC;
import ca.uhn.hl7v2.model.v25.segment.PID;
import ca.uhn.hl7v2.util.Terser;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Extrai de uma mensagem HL7 (estruturas canônicas v2.5) um modelo plano por pedido: {@code msh.*},
 * {@code pid.*}, {@code orc.*}, {@code obr.*} (timestamps já em ISO-8601) e a lista de OBX. As
 * chaves são as fontes dos YAML de mapeamento.
 */
public final class Hl7Fields {

  /** Mensagem HL7 decomposta. */
  public record Parsed(
      String messageType,
      String triggerEvent,
      Map<String, String> msh,
      Map<String, String> pid,
      List<Order> orders) {}

  /** Um grupo ORDER (ORM) ou ORDER_OBSERVATION (ORU). */
  public record Order(Map<String, String> fields, List<Obx> observations) {}

  /** Uma observação OBX. */
  public record Obx(
      String setId,
      String valueType,
      String code,
      String text,
      String codingSystem,
      String value,
      String unit,
      String abnormalFlag,
      String resultStatus,
      String observedAt) {}

  private final ZoneId zone;

  public Hl7Fields(ZoneId zone) {
    this.zone = zone;
  }

  public Parsed extract(Message message) throws HL7Exception {
    Terser terser = new Terser(message);
    Map<String, String> msh = msh(terser);
    String type = msh.getOrDefault("msh.message_type", "");
    String trigger = msh.getOrDefault("msh.trigger", "");
    if (message instanceof ORM_O01 orm) {
      Map<String, String> pid = pid(orm.getPATIENT().getPID());
      List<Order> orders = new ArrayList<>();
      for (int i = 0; i < orm.getORDERReps(); i++) {
        ORM_O01_ORDER g = orm.getORDER(i);
        Map<String, String> f = new LinkedHashMap<>();
        orc(f, g.getORC());
        obr(f, g.getORDER_DETAIL().getOBR());
        List<Obx> obx = new ArrayList<>();
        for (int j = 0; j < g.getORDER_DETAIL().getOBSERVATIONReps(); j++) {
          obx.add(obx(g.getORDER_DETAIL().getOBSERVATION(j).getOBX()));
        }
        orders.add(new Order(f, obx));
      }
      return new Parsed(type, trigger, msh, pid, orders);
    }
    if (message instanceof ORU_R01 oru) {
      ORU_R01_PATIENT_RESULT pr = oru.getPATIENT_RESULT();
      Map<String, String> pid = pid(pr.getPATIENT().getPID());
      List<Order> orders = new ArrayList<>();
      for (int i = 0; i < pr.getORDER_OBSERVATIONReps(); i++) {
        ORU_R01_ORDER_OBSERVATION g = pr.getORDER_OBSERVATION(i);
        Map<String, String> f = new LinkedHashMap<>();
        orc(f, g.getORC());
        obr(f, g.getOBR());
        List<Obx> obx = new ArrayList<>();
        for (int j = 0; j < g.getOBSERVATIONReps(); j++) {
          obx.add(obx(g.getOBSERVATION(j).getOBX()));
        }
        orders.add(new Order(f, obx));
      }
      return new Parsed(type, trigger, msh, pid, orders);
    }
    return new Parsed(type, trigger, msh, Map.of(), List.of());
  }

  private Map<String, String> msh(Terser t) throws HL7Exception {
    Map<String, String> m = new LinkedHashMap<>();
    m.put("msh.sending_application", get(t, "MSH-3-1"));
    m.put("msh.sending_facility", get(t, "MSH-4-1"));
    m.put("msh.receiving_application", get(t, "MSH-5-1"));
    m.put("msh.receiving_facility", get(t, "MSH-6-1"));
    m.put("msh.timestamp", Hl7Dates.toIso(get(t, "MSH-7-1"), zone));
    m.put("msh.message_type", get(t, "MSH-9-1"));
    m.put("msh.trigger", get(t, "MSH-9-2"));
    m.put("msh.control_id", get(t, "MSH-10"));
    m.put("msh.processing_id", get(t, "MSH-11-1"));
    m.put("msh.version", get(t, "MSH-12-1"));
    MSH msh = (MSH) t.getSegment("MSH");
    for (int i = 13; i <= msh.numFields(); i++) {
      String v = get(t, "MSH-" + i);
      if (v != null && !v.isBlank()) m.put("msh.field_" + i, v);
    }
    return m;
  }

  private Map<String, String> pid(PID pid) {
    Map<String, String> m = new LinkedHashMap<>();
    List<String> ids = new ArrayList<>();
    for (CX cx : pid.getPatientIdentifierList()) {
      String id = value(cx.getIDNumber());
      String type = value(cx.getIdentifierTypeCode()).toUpperCase(Locale.ROOT);
      String authority = value(cx.getAssigningAuthority().getNamespaceID());
      if (id.isBlank()) continue;
      ids.add(id + "^" + authority + "^" + type);
      m.put("pid.identifier." + (type.isBlank() ? "untyped" : type), id);
    }
    m.put("pid.identifiers", String.join("~", ids));
    if (pid.getPatientID() != null)
      m.put("pid.external_id", value(pid.getPatientID().getIDNumber()));
    if (pid.getPatientNameReps() > 0) {
      m.put("pid.family_name", value(pid.getPatientName(0).getFamilyName().getSurname()));
      m.put("pid.given_name", value(pid.getPatientName(0).getGivenName()));
    }
    m.put("pid.birthdate", Hl7Dates.toDate(value(pid.getDateTimeOfBirth().getTime())));
    m.put("pid.sex", value(pid.getAdministrativeSex()));
    return m;
  }

  private void orc(Map<String, String> f, ORC orc) {
    f.put("orc.order_control", value(orc.getOrderControl()));
    f.put("orc.placer_order", value(orc.getPlacerOrderNumber().getEntityIdentifier()));
    f.put("orc.filler_order", value(orc.getFillerOrderNumber().getEntityIdentifier()));
    f.put("orc.placer_group", value(orc.getPlacerGroupNumber().getEntityIdentifier()));
    f.put("orc.order_status", value(orc.getOrderStatus()));
    f.put(
        "orc.transaction_datetime",
        Hl7Dates.toIso(value(orc.getDateTimeOfTransaction().getTime()), zone));
    f.put("orc.ordering_provider_id", xcnId(orc.getOrderingProvider()));
    f.put("orc.entering_organization", value(orc.getEnteringOrganization().getIdentifier()));
    if (orc.getOrderingFacilityNameReps() > 0) {
      XON facility = orc.getOrderingFacilityName(0);
      // CNES pode vir em XON-10 (identificador), XON-3 (ID numérico) ou XON-1 (nome) conforme o LIS
      f.put(
          "orc.ordering_facility_id",
          firstDigits7(
              value(facility.getOrganizationIdentifier()),
              value(facility.getIDNumber()),
              value(facility.getOrganizationName())));
      f.put("orc.ordering_facility_name", value(facility.getOrganizationName()));
    } else {
      f.put("orc.ordering_facility_id", "");
      f.put("orc.ordering_facility_name", "");
    }
    f.put(
        "orc.priority",
        orc.getQuantityTimingReps() > 0 ? value(orc.getQuantityTiming(0).getPriority()) : "");
  }

  private void obr(Map<String, String> f, OBR obr) {
    f.put("obr.set_id", value(obr.getSetIDOBR()));
    f.put("obr.placer_order", value(obr.getPlacerOrderNumber().getEntityIdentifier()));
    f.put("obr.filler_order", value(obr.getFillerOrderNumber().getEntityIdentifier()));
    CE service = obr.getUniversalServiceIdentifier();
    f.put("obr.universal_id", value(service.getIdentifier()));
    f.put("obr.universal_text", value(service.getText()));
    f.put("obr.universal_coding_system", value(service.getNameOfCodingSystem()));
    f.put("obr.alternate_id", value(service.getAlternateIdentifier()));
    f.put("obr.alternate_coding_system", value(service.getNameOfAlternateCodingSystem()));
    f.put("obr.priority", value(obr.getPriorityOBR()));
    f.put(
        "obr.requested_datetime",
        Hl7Dates.toIso(value(obr.getRequestedDateTime().getTime()), zone));
    f.put(
        "obr.observation_datetime",
        Hl7Dates.toIso(value(obr.getObservationDateTime().getTime()), zone));
    f.put(
        "obr.specimen_received",
        Hl7Dates.toIso(value(obr.getSpecimenReceivedDateTime().getTime()), zone));
    f.put("obr.ordering_provider_id", xcnId(obr.getOrderingProvider()));
    f.put(
        "obr.results_datetime",
        Hl7Dates.toIso(value(obr.getResultsRptStatusChngDateTime().getTime()), zone));
    f.put("obr.result_status", value(obr.getResultStatus()));
    f.put("obr.diagnostic_service_section", value(obr.getDiagnosticServSectID()));
    f.put(
        "obr.quantity_timing_priority",
        obr.getQuantityTimingReps() > 0 ? value(obr.getQuantityTiming(0).getPriority()) : "");
  }

  private Obx obx(OBX obx) {
    CE id = obx.getObservationIdentifier();
    String valueType = value(obx.getValueType()).toUpperCase(Locale.ROOT);
    String value = "";
    if (obx.getObservationValueReps() > 0) {
      Varies v = obx.getObservationValue(0);
      value = typeValue(v.getData());
    }
    return new Obx(
        value(obx.getSetIDOBX()),
        valueType,
        value(id.getIdentifier()),
        value(id.getText()),
        value(id.getNameOfCodingSystem()),
        value,
        value(obx.getUnits().getIdentifier()),
        obx.getAbnormalFlagsReps() > 0 ? value(obx.getAbnormalFlags(0)) : "",
        value(obx.getObservationResultStatus()),
        Hl7Dates.toIso(value(obx.getDateTimeOfTheObservation().getTime()), zone));
  }

  /** Valor textual de um tipo HL7: primitivo direto, SN = num1, composto = primeiro componente. */
  static String typeValue(Type data) {
    if (data == null) return "";
    if (data instanceof Varies v) return typeValue(v.getData());
    if (data instanceof SN sn) {
      String comparator = value(sn.getComparator());
      String num = value(sn.getNum1());
      return (comparator.isBlank() ? "" : comparator) + num;
    }
    if (data instanceof Primitive p) return p.getValue() == null ? "" : p.getValue();
    if (data instanceof Composite c) {
      Type[] parts = c.getComponents();
      return parts.length == 0 ? "" : typeValue(parts[0]);
    }
    try {
      return data.encode();
    } catch (HL7Exception e) {
      return "";
    }
  }

  /** Primeiro candidato com exatamente 7 dígitos (CNES); senão o primeiro não vazio. */
  static String firstDigits7(String... candidates) {
    for (String c : candidates) {
      if (c != null && c.trim().matches("\\d{7}")) return c.trim();
    }
    for (String c : candidates) if (c != null && !c.isBlank()) return c.trim();
    return "";
  }

  private static String xcnId(XCN[] xcn) {
    return xcn == null || xcn.length == 0 ? "" : value(xcn[0].getIDNumber());
  }

  private static String get(Terser t, String path) throws HL7Exception {
    String v = t.get(path);
    return v == null ? "" : v;
  }

  static String value(Primitive p) {
    return p == null || p.getValue() == null ? "" : p.getValue().trim();
  }
}
