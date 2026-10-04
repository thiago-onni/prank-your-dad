package br.gov.sus.nexus.connectors.esusreg;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.util.Optional;

/**
 * Configuração do conector e-SUS Regulação (prefixo {@code esus.*}). As rotas e o formato da API
 * dependem da homologação com o MS/estado; por isso tudo que é "forma da API" (caminhos, nomes de
 * parâmetros de paginação, caminhos JSON) é parametrizável aqui e nos YAML de mapeamento.
 */
@ConfigMapping(prefix = "esus")
public interface EsusConfig {

  /** {@code api} (REST paginado com updated_since) ou {@code file} (exportação JSON/CSV). */
  @WithDefault("file")
  String mode();

  @WithDefault("e-SUS Regulação (API em homologação)")
  String sourceVersion();

  Api api();

  File file();

  Mapping mapping();

  interface Api {
    @WithDefault("http://localhost:9080")
    String baseUrl();

    /** {@code oauth2} (client credentials), {@code static} (token fixo) ou {@code none}. */
    @WithDefault("oauth2")
    String authMode();

    @WithDefault("http://localhost:9080/oauth/token")
    String tokenUrl();

    @WithDefault("sus-nexus")
    String clientId();

    Optional<String> clientSecret();

    Optional<String> scope();

    Optional<String> staticToken();

    /** Caminho de listagem de solicitações (relativo a base-url). */
    @WithDefault("/api/v1/regulacao/solicitacoes")
    String requestsPath();

    /** Caminho de listagem de eventos/mudanças de status. */
    @WithDefault("/api/v1/regulacao/eventos")
    String eventsPath();

    /** Nome do parâmetro de filtro incremental. */
    @WithDefault("updated_since")
    String updatedSinceParam();

    /** {@code page} (número de página) ou {@code cursor} (token opaco). */
    @WithDefault("page")
    String pagination();

    @WithDefault("page")
    String pageParam();

    /** Primeira página (0 ou 1 conforme a API). */
    @WithDefault("1")
    int firstPage();

    @WithDefault("size")
    String pageSizeParam();

    @WithDefault("100")
    int pageSize();

    @WithDefault("cursor")
    String cursorParam();

    /** Caminho JSON (a.b) da lista de itens na resposta. */
    @WithDefault("items")
    String itemsPath();

    /** Caminho JSON do próximo cursor (paginação cursor); nulo/ausente encerra. */
    @WithDefault("next_cursor")
    String nextCursorPath();

    /** Caminho JSON, dentro do item, do instante de atualização (marca d'água). */
    @WithDefault("atualizado_em")
    String updatedAtPath();

    /** Caminho JSON do identificador do item (solicitação). */
    @WithDefault("id")
    String requestIdPath();

    /** Caminho JSON do identificador do evento. */
    @WithDefault("id")
    String eventIdPath();

    @WithDefault("300000")
    long pollIntervalMs();

    @WithDefault("5000")
    long initialDelayMs();

    @WithDefault("data/esus-regulacao/watermark.json")
    String watermarkFile();

    @WithDefault("1970-01-01T00:00:00Z")
    String initialWatermark();

    @WithDefault("30000")
    int timeoutMs();
  }

  interface File {
    @WithDefault("data/esus-regulacao/in")
    String inputDir();

    @WithDefault("UTF-8")
    String charset();

    @WithDefault(";")
    String delimiter();

    @WithDefault("5000")
    long pollDelayMs();
  }

  interface Mapping {
    @WithDefault("mappings/esus-regulacao-request-1.0.0.yaml")
    String request();

    @WithDefault("mappings/esus-regulacao-status-1.0.0.yaml")
    String status();
  }
}
