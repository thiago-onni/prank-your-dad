package br.gov.sus.nexus.connectors.sdk.mapping;

import java.util.Map;

/** Contexto disponível às transformações (tabelas de lookup). */
public interface MappingContext {
  Map<String, String> lookup(String table);
}
