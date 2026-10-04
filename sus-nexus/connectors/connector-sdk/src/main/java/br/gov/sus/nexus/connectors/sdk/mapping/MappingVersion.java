package br.gov.sus.nexus.connectors.sdk.mapping;

import java.util.List;
import java.util.Map;

/** Uma versão de um conjunto de mapeamentos (corresponde a {@code field_mapping_version}). */
public record MappingVersion(
    String mappingSet,
    String version,
    String entityType,
    List<FieldMapping> fields,
    Map<String, Map<String, String>> lookups)
    implements MappingContext {

  public MappingVersion {
    fields = List.copyOf(fields);
    lookups = lookups == null ? Map.of() : Map.copyOf(lookups);
  }

  @Override
  public Map<String, String> lookup(String table) {
    Map<String, String> t = lookups.get(table);
    if (t == null) throw new MappingException("tabela de lookup não declarada: " + table, null);
    return t;
  }
}
