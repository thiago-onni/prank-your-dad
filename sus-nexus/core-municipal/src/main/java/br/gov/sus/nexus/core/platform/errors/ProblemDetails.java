package br.gov.sus.nexus.core.platform.errors;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.List;

/** Corpo RFC 9457 (Problem Details). */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ProblemDetails(
    String type,
    String title,
    int status,
    String detail,
    String instance,
    String correlationId,
    List<ProblemException.FieldError> errors) {

  public static final String MEDIA_TYPE = "application/problem+json";
}
