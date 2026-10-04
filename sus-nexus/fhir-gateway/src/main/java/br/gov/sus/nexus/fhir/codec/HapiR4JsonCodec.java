package br.gov.sus.nexus.fhir.codec;

import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import org.hl7.fhir.r4.formats.IParser;
import org.hl7.fhir.r4.formats.JsonParser;
import org.hl7.fhir.r4.model.Resource;

/**
 * Codec baseado no {@link JsonParser} de referência HL7 (org.hl7.fhir.r4), em modo estrito:
 * conteúdo desconhecido é rejeitado. O parser não é thread-safe, por isso uma instância por
 * chamada.
 */
@ApplicationScoped
public class HapiR4JsonCodec implements FhirCodec {

  @Override
  public Resource parse(String json) {
    if (json == null || json.isBlank()) {
      throw new FhirParseException("Corpo da requisição vazio");
    }
    try {
      JsonParser parser = new JsonParser();
      parser.setAllowUnknownContent(false);
      parser.setAllowComments(false);
      Resource resource = parser.parse(json);
      if (resource == null) {
        throw new FhirParseException("Nenhum recurso FHIR encontrado no corpo");
      }
      StrictContentChecker.firstMismatch(json, compose(resource, IParser.OutputStyle.NORMAL))
          .ifPresent(
              path -> {
                throw new FhirParseException("Conteúdo desconhecido ou inválido em " + path);
              });
      return resource;
    } catch (IOException | RuntimeException e) {
      throw new FhirParseException(sanitize(e.getMessage()), e);
    }
  }

  @Override
  public String encode(Resource resource) {
    return compose(resource, IParser.OutputStyle.NORMAL);
  }

  @Override
  public String encodePretty(Resource resource) {
    return compose(resource, IParser.OutputStyle.PRETTY);
  }

  private static String compose(Resource resource, IParser.OutputStyle style) {
    try {
      JsonParser parser = new JsonParser();
      parser.setOutputStyle(style);
      return parser.composeString(resource);
    } catch (IOException e) {
      throw new IllegalStateException("Falha ao serializar recurso FHIR", e);
    }
  }

  /** Mensagens do parser podem conter trechos do conteúdo; limita o tamanho por segurança. */
  private static String sanitize(String message) {
    if (message == null) {
      return "JSON FHIR inválido";
    }
    String m = message.replaceAll("\\s+", " ").trim();
    return m.length() > 300 ? m.substring(0, 300) + "..." : m;
  }
}
