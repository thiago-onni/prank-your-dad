package br.gov.sus.nexus.fhir.validation;

import java.util.List;
import org.hl7.fhir.r4.model.Resource;

/**
 * Ponto de extensão para validação estrutural/de perfil com StructureDefinitions (base FHIR 4.0.1
 * e, quando os pacotes NPM estiverem disponíveis, br-core/RNDS). Veja {@code
 * src/main/resources/fhir/profiles/README.md}.
 */
public interface ProfileValidator {

  /**
   * Valida o recurso contra a especificação base e os perfis declarados em {@code meta.profile} que
   * forem conhecidos. Perfis desconhecidos devem gerar aviso (não erro) — a obrigatoriedade do
   * canônico é verificada pelo pipeline.
   */
  List<ValidationIssue> validate(Resource resource, String json);

  /** Descrição curta do que está carregado (exibida em {@code CapabilityStatement.software}). */
  String describe();

  /** Verdadeiro se o perfil (canônico) está carregado e é efetivamente verificado. */
  boolean knowsProfile(String canonical);
}
