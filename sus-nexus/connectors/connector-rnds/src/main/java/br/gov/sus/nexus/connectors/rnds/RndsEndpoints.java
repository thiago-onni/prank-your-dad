package br.gov.sus.nexus.connectors.rnds;

import java.util.Locale;
import java.util.Set;

/**
 * Resolve os endereços da RNDS a partir de {@code rnds.environment}, {@code rnds.uf} e {@code
 * rnds.endpoints.*} (com {@code rnds.auth-url}/{@code rnds.ehr-url} como sobrescrita explícita).
 *
 * <p>Fonte: Guia de Integração da RNDS, "Ambientes" (rnds-guia.saude.gov.br/docs/rnds/ambientes) e
 * Manual de Integração DATASUS v1.2, cap. 5: homologação única ({@code ehr-auth-hmg.saude.gov.br},
 * {@code ehr-services.hmg.saude.gov.br}); produção com Auth único ({@code ehr-auth.saude.gov.br}) e
 * EHR por UF ({@code <uf>-ehr-services.saude.gov.br}); a credencial pertence a uma UF e acessos a
 * outra UF são bloqueados.
 */
public final class RndsEndpoints {

  /** As 27 UFs com EHR de produção publicado (Manual v1.2, cap. 5.2). */
  public static final Set<String> UFS =
      Set.of(
          "ac", "al", "ap", "am", "ba", "ce", "df", "es", "go", "ma", "mt", "ms", "mg", "pa", "pb",
          "pr", "pe", "pi", "rj", "rn", "rs", "ro", "rr", "sc", "sp", "se", "to");

  private RndsEndpoints() {}

  public static String authUrl(RndsConfig config) {
    return config.authUrl().filter(s -> !s.isBlank()).orElseGet(() -> endpoint(config).authUrl());
  }

  public static String ehrUrl(RndsConfig config) {
    String url =
        config.ehrUrl().filter(s -> !s.isBlank()).orElseGet(() -> endpoint(config).ehrUrl());
    if (!url.contains("{uf}")) return url;
    String uf =
        config
            .uf()
            .map(u -> u.trim().toLowerCase(Locale.ROOT))
            .filter(u -> !u.isBlank())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "rnds.uf obrigatório no ambiente " + config.environment()));
    if (!UFS.contains(uf)) {
      throw new IllegalStateException("rnds.uf inválida: " + uf);
    }
    return url.replace("{uf}", uf);
  }

  private static RndsConfig.Endpoint endpoint(RndsConfig config) {
    RndsConfig.Endpoint e = config.endpoints().get(config.environment());
    if (e == null) {
      throw new IllegalStateException(
          "rnds.endpoints."
              + config.environment()
              + " não configurado (use homologacao ou producao)");
    }
    return e;
  }
}
