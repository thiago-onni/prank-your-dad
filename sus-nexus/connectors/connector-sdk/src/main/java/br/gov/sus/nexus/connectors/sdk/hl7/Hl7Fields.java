package br.gov.sus.nexus.connectors.sdk.hl7;

import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.model.Composite;
import ca.uhn.hl7v2.model.Group;
import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.model.Primitive;
import ca.uhn.hl7v2.model.Segment;
import ca.uhn.hl7v2.model.Structure;
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
 * Extrai de uma mensagem HL7 (estruturas canônicas v2.5) um modelo plano: {@code msh.*}, {@code
 * pid.*}, {@code evn.*}, {@code pv1.*} (visita/internação), diagnósticos DG1, procedimentos PR1,
 * segmentos Z ({@code zai.2}, {@code zai.2.1}...) e, para ORM/ORU, os grupos de pedido ({@code
 * orc.*}, {@code obr.*}, lista de OBX). Timestamps já em ISO-8601. As chaves são as fontes dos YAML
 * de mapeamento. Segmentos de visita/diagnóstico são localizados por nome em qualquer profundidade
 * da estrutura, o que tolera as variações ADT_A01/A02/A03/A06/A09 do HAPI.
 */
public final class Hl7Fields {

  /** Mensagem HL7 decomposta. */
  public record Parsed(
      String messageType,
      String triggerEvent,
      Map<String, String> msh,
      Map<String, String> pid,
      Map<String, String> visit,
      List<Diagnosis> diagnoses,
      List<Procedure> procedures,
      Map<String, String> custom,
      List<Order> orders) {

    /** Modelo plano de MSH + PID + EVN/PV1 + segmentos Z (sem os grupos de pedido). */
    public Map<String, String> flat() {
      Map<String, String> m = new LinkedHashMap<>(msh);
      m.putAll(pid);
      m.putAll(visit);
      m.putAll(custom);
      return m;
    }
  }

  /** Um diagnóstico DG1 ({@code type} = DG1-6: A admitting, W working, F final). */
  public record Diagnosis(
      String setId, String code, String text, String codingSystem, String type, String datetime) {}

  /** Um procedimento PR1. */
  public record Procedure(
      String setId, String code, String text, String codingSystem, String datetime) {}

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
    List<Segment> all = new ArrayList<>();
    walk(message, all);
    Map<String, String> pid = Map.of();
    for (Segment s : all) {
      if (s instanceof PID p) {
        pid = pid(p);
        break;
      }
    }
    Map<String, String> visit = new LinkedHashMap<>();
    List<Diagnosis> diagnoses = new ArrayList<>();
    List<Procedure> procedures = new ArrayList<>();
    Map<String, String> custom = new LinkedHashMap<>();
    boolean evnDone = false;
    boolean pv1Done = false;
    for (Segment s : all) {
      String name = s.getName();
      if ("EVN".equals(name) && !evnDone) {
        evn(visit, s);
        evnDone = true;
      } else if ("PV1".equals(name) && !pv1Done) {
        pv1(visit, s);
        pv1Done = true;
      } else if ("DG1".equals(name)) {
        diagnoses.add(
            new Diagnosis(
                get(s, 1),
                get(s, 3, 1),
                get(s, 3, 2),
                get(s, 3, 3),
                get(s, 6),
                Hl7Dates.toIso(get(s, 5), zone)));
      } else if ("PR1".equals(name)) {
        procedures.add(
            new Procedure(
                get(s, 1),
                get(s, 3, 1),
                get(s, 3, 2),
                get(s, 3, 3),
                Hl7Dates.toIso(get(s, 5), zone)));
      } else if (name.startsWith("Z")) {
        custom(custom, s);
      }
    }
    if (message instanceof ORM_O01 orm) {
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
      return new Parsed(type, trigger, msh, pid, visit, diagnoses, procedures, custom, orders);
    }
    if (message instanceof ORU_R01 oru) {
      ORU_R01_PATIENT_RESULT pr = oru.getPATIENT_RESULT();
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
      return new Parsed(type, trigger, msh, pid, visit, diagnoses, procedures, custom, orders);
    }
    return new Parsed(type, trigger, msh, pid, visit, diagnoses, procedures, custom, List.of());
  }

  /** Percorre a estrutura (grupos aninhados e segmentos não padrão) em ordem de documento. */
  static void walk(Group group, List<Segment> out) throws HL7Exception {
    for (String name : group.getNames()) {
      for (Structure st : group.getAll(name)) {
        if (st instanceof Group g) {
          walk(g, out);
        } else if (st instanceof Segment seg) {
          if (!seg.isEmpty()) out.add(seg);
        }
      }
    }
  }

  private void evn(Map<String, String> m, Segment evn) throws HL7Exception {
    m.put("evn.type_code", get(evn, 1));
    m.put("evn.recorded_at", Hl7Dates.toIso(get(evn, 2), zone));
    m.put("evn.reason_code", get(evn, 4));
    m.put("evn.occurred_at", Hl7Dates.toIso(get(evn, 6), zone));
  }

  /** PV1 (tabela 0004 em PV1-2, 0023 em PV1-14, 0112 em PV1-36). */
  private void pv1(Map<String, String> m, Segment pv1) throws HL7Exception {
    m.put("pv1.set_id", get(pv1, 1));
    m.put("pv1.patient_class", get(pv1, 2));
    m.put("pv1.ward", get(pv1, 3, 1));
    m.put("pv1.room", get(pv1, 3, 2));
    m.put("pv1.bed", get(pv1, 3, 3));
    m.put("pv1.facility", get(pv1, 3, 4));
    m.put("pv1.location_description", get(pv1, 3, 9));
    m.put("pv1.admission_type", get(pv1, 4));
    m.put("pv1.preadmit_number", get(pv1, 5, 1));
    m.put("pv1.prior_ward", get(pv1, 6, 1));
    m.put("pv1.prior_bed", get(pv1, 6, 3));
    m.put("pv1.attending_id", get(pv1, 7, 1));
    m.put("pv1.attending_name", get(pv1, 7, 2));
    m.put("pv1.referring_id", get(pv1, 8, 1));
    m.put("pv1.hospital_service", get(pv1, 10));
    m.put("pv1.readmission", get(pv1, 13));
    m.put("pv1.admit_source", get(pv1, 14));
    m.put("pv1.admitting_id", get(pv1, 17, 1));
    m.put("pv1.patient_type", get(pv1, 18));
    m.put("pv1.visit_number", get(pv1, 19, 1));
    m.put("pv1.financial_class", get(pv1, 20, 1));
    m.put("pv1.discharge_disposition", get(pv1, 36));
    m.put("pv1.discharged_to_location", get(pv1, 37, 1));
    m.put("pv1.account_status", get(pv1, 41));
    m.put("pv1.admit_datetime", Hl7Dates.toIso(get(pv1, 44), zone));
    m.put("pv1.discharge_datetime", Hl7Dates.toIso(get(pv1, 45), zone));
    m.put("pv1.alternate_visit_id", get(pv1, 50, 1));
  }

  /** Segmento Z: {@code zxx.N} = campo inteiro codificado, {@code zxx.N.C} = componente C. */
  private static void custom(Map<String, String> m, Segment z) throws HL7Exception {
    String prefix = z.getName().toLowerCase(Locale.ROOT) + ".";
    for (int i = 1; i <= z.numFields(); i++) {
      Type[] reps = z.getField(i);
      if (reps.length == 0) continue;
      String encoded = reps[0].encode();
      if (encoded == null || encoded.isBlank()) continue;
      m.put(prefix + i, encoded.trim());
      if (reps[0] instanceof Composite c) {
        Type[] parts = c.getComponents();
        for (int j = 0; j < parts.length; j++) {
          String v = typeValue(parts[j]);
          if (!v.isBlank()) m.put(prefix + i + "." + (j + 1), v);
        }
      } else {
        m.put(prefix + i + ".1", encoded.trim());
      }
    }
  }

  static String get(Segment s, int field) throws HL7Exception {
    return get(s, field, 1);
  }

  static String get(Segment s, int field, int component) throws HL7Exception {
    if (field > s.numFields()) return "";
    String v = Terser.get(s, field, 0, component, 1);
    return v == null ? "" : v.trim();
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
    m.put("pid.account_number", value(pid.getPatientAccountNumber().getIDNumber()));
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
  public static String typeValue(Type data) {
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
  public static String firstDigits7(String... candidates) {
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

  public static String value(Primitive p) {
    return p == null || p.getValue() == null ? "" : p.getValue().trim();
  }
}
