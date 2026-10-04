package br.gov.sus.nexus.connectors.sdk.mapping;

import java.util.List;

/**
 * Mapeamento de um campo: origem → destino canônico (caminho pontilhado, com índices {@code
 * a[0].b}) com cadeia de transformações. {@code constant} ignora a origem; {@code dependsOn} só
 * aplica o mapeamento quando o campo de origem indicado está preenchido.
 */
public record FieldMapping(
    String source,
    String target,
    String constant,
    boolean required,
    String dependsOn,
    List<Transformation> transforms) {

  public FieldMapping {
    transforms = transforms == null ? List.of() : List.copyOf(transforms);
    if (target == null || target.isBlank()) {
      throw new IllegalArgumentException("mapeamento sem target");
    }
    if ((source == null || source.isBlank()) && constant == null) {
      throw new IllegalArgumentException("mapeamento de '" + target + "' sem source nem constant");
    }
  }
}
