package br.gov.sus.nexus.connectors.lis;

import ca.uhn.hl7v2.DefaultHapiContext;
import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.HapiContext;
import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.parser.CanonicalModelClassFactory;
import ca.uhn.hl7v2.parser.PipeParser;
import ca.uhn.hl7v2.validation.impl.ValidationContextFactory;

/**
 * Parser HL7 v2 tolerante (HAPI): toda versão 2.x é lida com as estruturas canônicas v2.5 ({@link
 * CanonicalModelClassFactory}) e sem validação estrita, o que aceita mensagens 2.3–2.5.1 de LIS
 * heterogêneos. Separadores {@code \n}/{@code \r\n} e envelopes MLLP residuais são normalizados.
 */
public final class Hl7Parser {

  public static final char MLLP_START = 0x0b;
  public static final char MLLP_END = 0x1c;

  private final HapiContext context;
  private final PipeParser parser;

  public Hl7Parser() {
    this.context = new DefaultHapiContext();
    context.setModelClassFactory(new CanonicalModelClassFactory("2.5"));
    context.setValidationContext(ValidationContextFactory.noValidation());
    this.parser = context.getPipeParser();
  }

  public PipeParser pipeParser() {
    return parser;
  }

  public Message parse(String text) throws HL7Exception {
    return parser.parse(normalize(text));
  }

  /** Remove envelope MLLP e normaliza quebras de linha para {@code \r}. */
  public static String normalize(String text) {
    if (text == null) return "";
    String t = text.replace(String.valueOf(MLLP_START), "").replace(String.valueOf(MLLP_END), "");
    t = t.replace("\r\n", "\r").replace('\n', '\r');
    while (t.endsWith("\r\r")) t = t.substring(0, t.length() - 1);
    return t.strip();
  }

  /** Divide um arquivo com várias mensagens (uma por {@code MSH|}). */
  public static java.util.List<String> splitMessages(String text) {
    String t = normalize(text);
    java.util.List<String> out = new java.util.ArrayList<>();
    for (String part : t.split("(?=MSH\\|)")) {
      if (part.isBlank()) continue;
      out.add(part.strip());
    }
    return out;
  }
}
