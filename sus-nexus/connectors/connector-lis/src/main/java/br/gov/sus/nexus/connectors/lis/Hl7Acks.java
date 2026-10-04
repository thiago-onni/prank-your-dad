package br.gov.sus.nexus.connectors.lis;

import ca.uhn.hl7v2.AcknowledgmentCode;
import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.model.Message;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Gera ACK HL7 (MSA-1 AA/AE/AR). Com a mensagem parseada usa o HAPI; sem parse (mensagem
 * malformada) monta um ACK mínimo a partir do MSH bruto.
 */
public final class Hl7Acks {

  private Hl7Acks() {}

  public static String ack(Message parsed, AcknowledgmentCode code, String errorText) {
    try {
      Message ack =
          code == AcknowledgmentCode.AA
              ? parsed.generateACK()
              : parsed.generateACK(
                  code, new HL7Exception(errorText == null ? code.name() : errorText));
      return ack.encode();
    } catch (Exception e) {
      return manualAck(safeEncode(parsed), code, errorText);
    }
  }

  private static String safeEncode(Message m) {
    try {
      return m.encode();
    } catch (HL7Exception e) {
      return "";
    }
  }

  /** ACK mínimo para mensagem que não pôde ser parseada. */
  public static String manualAck(String raw, AcknowledgmentCode code, String errorText) {
    String msh = "";
    if (raw != null) {
      String n = Hl7Parser.normalize(raw);
      int end = n.indexOf('\r');
      msh = end < 0 ? n : n.substring(0, end);
    }
    String[] f = msh.split("\\|", -1);
    String sendingApp = f.length > 2 ? f[2] : "";
    String sendingFac = f.length > 3 ? f[3] : "";
    String receivingApp = f.length > 4 ? f[4] : "SUSNEXUS";
    String receivingFac = f.length > 5 ? f[5] : "";
    String controlId = f.length > 9 ? f[9] : "";
    String version = f.length > 11 && !f[11].isBlank() ? f[11] : "2.5";
    String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
    StringBuilder sb =
        new StringBuilder("MSH|^~\\&|")
            .append(receivingApp)
            .append('|')
            .append(receivingFac)
            .append('|')
            .append(sendingApp)
            .append('|')
            .append(sendingFac)
            .append('|')
            .append(ts)
            .append("||ACK|")
            .append(ts)
            .append("|P|")
            .append(version)
            .append('\r')
            .append("MSA|")
            .append(code.name())
            .append('|')
            .append(controlId);
    if (errorText != null && !errorText.isBlank()) {
      sb.append('|').append(errorText.replace('|', ' ').replace('\r', ' '));
    }
    return sb.append('\r').toString();
  }

  /** Valor de MSA-1 de um ACK codificado. */
  public static String msaCode(String ack) {
    for (String seg : Hl7Parser.normalize(ack).split("\r")) {
      if (seg.startsWith("MSA|")) {
        String[] f = seg.split("\\|", -1);
        return f.length > 1 ? f[1] : "";
      }
    }
    return "";
  }
}
