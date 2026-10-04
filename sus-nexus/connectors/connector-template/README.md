# connector-template — como criar um conector novo

1. Copie esta pasta para `connector-<nome>` e ajuste `artifactId`, pacote `br.gov.sus.nexus.connectors.<nome>`, porta (`8090+`) e `quarkus.application.name`. Adicione o módulo ao `pom.xml` raiz.
2. Preencha o `ConnectorDescriptor` em `ExampleConnector` — **todos** os metadados são obrigatórios (`build()` falha se faltar algum): connector_id, connector_version, source_system, supported_source_versions, supported_protocols, supported_entities, authentication_method, required_network_access, data_classification, polling_or_event_mode, retry_policy, rate_limit_policy, field_mapping_version, test_suite_version, owner, support_sla.
3. Escreva o mapeamento declarativo em `src/main/resources/mappings/<set>-<versão>.yaml` (origem → caminho canônico; transformações `trim`, `upper`, `lower`, `unaccent`, `digits`, `blank_to_null`, `to_integer`, `{date: {from, to}}`, `{lookup: {table, default, strict}}`, `{default: v}`, `{substring: {start, end}}`, `{to_boolean: {true_values}}`; `constant`, `required`, `depends_on`). A versão do YAML é o `field_mapping_version` do descriptor.
4. Implemente `transform(RawMessage)` (→ `CanonicalBatch` com `entityType` `citizen` | `appointment` | `health_unit` | `code`) e `validate(CanonicalBatch)`. `publish` já é feito por `CorePublisher` conforme o `entityType`.
5. Crie a rota de fonte (`ExampleRoutes`) que entrega `RawMessage` em `ConnectorRuntime.INGEST`. Em fontes por registro (PEC), gere uma `RawMessage` por linha; em tabelas de referência, uma por arquivo.
6. Sobrescreva `healthCheck()`, `authenticate()` e `sourceCounter()` (contagem na fonte para a reconciliação).
7. Teste: golden file entrada → saída canônica (sem Quarkus) + ponta a ponta com `@QuarkusTest` e WireMock (veja `connector-cnes`).
8. Atualize README (metadados do descriptor, plano B), `application.properties` por perfil e `src/main/docker/Dockerfile`.

Regras: nunca escrever na fonte; nunca logar CPF/CNS (filtro `pii-mask` ativo); publicar apenas pela API do core.
