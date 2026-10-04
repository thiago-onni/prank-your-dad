package br.gov.sus.nexus.core.identity.domain;

import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

/**
 * Atributos já normalizados usados pelos comparadores. {@code identifierHashes} mapeia sistema →
 * value_hash (apenas identificadores válidos/ativos).
 *
 * @param citizenId id do cidadão (nulo para o registro de entrada)
 */
public record PersonFeatures(
    String citizenId,
    String normalizedName,
    String normalizedMotherName,
    LocalDate birthdate,
    String sex,
    Set<String> phones,
    Map<String, String> identifierHashes) {

  public String identifierHash(String system) {
    return identifierHashes == null ? null : identifierHashes.get(system);
  }
}
