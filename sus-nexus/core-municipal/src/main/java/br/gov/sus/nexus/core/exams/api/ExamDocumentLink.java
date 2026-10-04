package br.gov.sus.nexus.core.exams.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.OffsetDateTime;

/** Referência segura ao laudo: URL assinada de curta duração. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ExamDocumentLink(String url, OffsetDateTime expiresAt, String contentType) {}
