package br.gov.sus.nexus.connectors.sdk.core.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * Payload de {@code POST /api/v1/hospital/episodes} (OpenAPI {@code HospitalMovementRegistration}):
 * movimentação ADT vinda do HIS. Upsert do episódio por {@code source.source_record_id} (nº do
 * atendimento/visita). {@code movement}: admit | transfer | bed_change | discharge | death |
 * cancel; {@code episode_class}: inpatient | emergency | observation | day_hospital; {@code
 * admission_source}: emergency | regulation | transfer | elective | other.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record HospitalMovementRegistration(
    SourceRef source,
    CitizenRef citizenRef,
    String hospitalCnes,
    String episodeClass,
    String movement,
    String occurredAt,
    String ward,
    String bed,
    String attendingProfessionalId,
    String admissionSource,
    String regulationSourceRecordId,
    String principalDiagnosisCid,
    String aihNumber,
    String reason) {}
