package br.gov.sus.nexus.connectors.sdk.core.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.List;

/**
 * Payload de {@code POST /api/v1/hospital/episodes/{id}/discharge} e de {@code
 * .../by-source/{system}/{sourceRecordId}/discharge} (OpenAPI {@code DischargeRegistration}).
 * {@code disposition}: home | home_with_care | transfer | against_advice | deceased | other. O
 * sumário de alta nunca vai no corpo: só {@code summary_document_ref}/{@code
 * summary_document_sha256}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DischargeRegistration(
    SourceRef source,
    String dischargedAt,
    String disposition,
    String principalDiagnosisCid,
    Integer proceduresCount,
    Boolean followupPlanPresent,
    Integer followupDueDays,
    List<String> careLines,
    String summaryDocumentRef,
    String summaryDocumentSha256) {}
