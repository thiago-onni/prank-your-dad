package br.gov.sus.nexus.fhir.interaction;

import java.time.Instant;
import org.hl7.fhir.r4.model.Resource;

/**
 * Resultado de uma interação, independente de HTTP.
 *
 * @param status código HTTP sugerido
 * @param body recurso de resposta ({@code null} para 304)
 * @param etag ETag fraco ({@code W/"n"}) ou {@code null}
 * @param lastModified data da versão ou {@code null}
 * @param location URL absoluta da versão criada/atualizada ou {@code null}
 */
public record InteractionResult(
    int status, Resource body, String etag, Instant lastModified, String location) {

  public static InteractionResult ok(Resource body) {
    return new InteractionResult(200, body, null, null, null);
  }

  public static InteractionResult notModified(String etag) {
    return new InteractionResult(304, null, etag, null, null);
  }
}
