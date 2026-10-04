package br.gov.sus.nexus.core.reference.api;

import java.util.Optional;
import java.util.Set;

/**
 * Consulta de vínculos profissionais (CNES) da base de referência. O profissional é localizado pelo
 * {@code cns_hash} (HMAC-SHA256 por tenant, mesmo algoritmo de {@code IdentifierHash} com sistema
 * {@code CNS}) — o CNS em claro nunca chega aqui.
 */
public interface ProfessionalDirectory {

  /**
   * CBOs dos vínculos ativos do profissional no estabelecimento. Vazio quando o profissional não
   * consta da base (base não carregada ou profissional desconhecido) — o chamador não deve tratar
   * isso como incompatibilidade.
   */
  Optional<Set<String>> activeCbos(String professionalCnsHash, String cnes);
}
