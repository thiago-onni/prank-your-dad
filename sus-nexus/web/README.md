# SUS Nexus — Web

Monorepo do frontend do SUS Nexus: **Turborepo + pnpm 10**, Node 22, Next.js 15 (App Router), React 19, TypeScript 5 `strict`, Tailwind CSS 4, Radix UI, TanStack Query v5, Vitest + Testing Library + axe, Storybook 8, Playwright.

Este diretório segue as convenções de `../CONVENTIONS.md` (seção _TypeScript (web)_) e a seção 11 do `docs/sus-nexus/PLANO_IMPLEMENTACAO.md`.

## Arquitetura

```text
web/
├── apps/
│   └── shell/                      # Único app interno (Next.js 15) com módulos por rota
│       ├── src/app/                # App Router: /, /integracoes, /cadastro, /cidadaos/[id] (?aba=plano), /tarefas,
│       │                           #   /cuidado (?aba=tarefas|busca-ativa|linhas), /hospital, /hospital/[id],
│       │                           #   /admin/protocolos, /regulacao, /regulacao/[id], /regulacao/capacidade, /exames,
│       │                           #   /exames/[id], /agentes, /agentes/execucoes/[id], /agentes/kill-switch, placeholders
│       ├── src/app/api/core/[...path]/route.ts   # Proxy BFF → core (Bearer no servidor)
│       ├── src/app/api/ai/[...path]/route.ts     # Proxy BFF → ai-service (Bearer + tenant; lista de rotas permitida)
│       ├── src/app/api/auth/[...nextauth]/route.ts   # Auth.js (Keycloak) ou mock
│       ├── src/app/api/session/purpose/route.ts  # Cookie httpOnly com X-Purpose-Of-Use
│       ├── src/app/api/situacao/[view]/route.ts  # BFF Sala de Situação → Trino (consultas fixas, município do token)
│       ├── src/app/api/fhir/r4/[...path]/route.ts # BFF → FHIR Gateway (só Patient/$everything; CPF/CNS mascarados)
│       ├── src/middleware.ts       # CSP com nonce + redirecionamento para /entrar
│       ├── src/features/           # integracoes | cadastro | cidadaos | tarefas | cuidado | hospital | admin | regulacao | exames | agentes
│       ├── src/mocks/              # MSW: dados sintéticos + handlers de todos os endpoints (core e ai-service);
│       │                           #   care-data.ts/care-handlers.ts = Fase 3 (hospital, planos, lacunas, protocolos)
│       ├── src/i18n/pt-BR.ts       # Strings centralizadas
│       └── Dockerfile              # output standalone, usuário non-root
└── packages/
    ├── design-system/              # @sus-nexus/design-system — tokens gov.br, componentes acessíveis, Storybook
    ├── domain-components/          # @sus-nexus/domain-components — os 15 componentes da especificação
    ├── api-client/                 # @sus-nexus/api-client — tipos gerados dos OpenAPI (core + ai-service) + openapi-fetch + hooks TanStack Query
    └── auth/                       # @sus-nexus/auth — BFF OIDC (Auth.js v5 + Keycloak), modo mock, withAuth, useSession
```

Os pacotes são consumidos **como fonte** (`exports` → `src/index.ts`); o shell os transpila via `transpilePackages`. Não há etapa de build nos pacotes — apenas `lint`, `typecheck` e `test`.

### Fluxo de dados (navegador → core)

```text
Componente cliente ──(TanStack Query hook)──► openapi-fetch (baseUrl /api/core)
   │  injeta X-Purpose-Of-Use (da sessão) e X-Correlation-Id
   ▼
Route Handler /api/core/[...path]  (withAuth)
   │  Bearer da sessão (cookie httpOnly) + X-Tenant-Id; o token nunca chega ao navegador
   ▼
Core Municipal (CORE_API_URL)  ── ou MSW (NEXT_PUBLIC_API_MOCK=true), interceptado no servidor
```

O ai-service segue o mesmo desenho: hooks `useAgents`/`useAgentRuns`/… → cliente `ai` (openapi-fetch tipado por
`src/generated/ai.d.ts`, base `/api/ai`) → Route Handler `/api/ai/[...path]` (Bearer + `X-Tenant-Id`; só as rotas
`agents`, `tools`, `runs`, `approvals` e `admin/kill-switch` são encaminhadas) → `AI_SERVICE_URL`.

### Segurança

| Tema              | Implementação                                                                                                                                                                                                                                     |
| ----------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Autenticação      | Auth.js v5 + provider Keycloak; sessão JWT criptografada em cookie `httpOnly` / `Secure` / `SameSite=strict`; refresh do access token no servidor (`jwt` callback); `/api/auth/session` sanitizado (sem tokens); logout RP-initiated no Keycloak  |
| Autorização na UI | Somente usabilidade (`hasRole`/`useHasRole`, itens de navegação por papel). A autorização real é no backend (OPA)                                                                                                                                 |
| Finalidade (LGPD) | Seletor no cabeçalho → `POST /api/session/purpose` (cookie httpOnly) → proxy injeta `X-Purpose-Of-Use`; telas com dado de cidadão exigem finalidade selecionada                                                                                   |
| CPF/CNS           | Sempre mascarados (`value_masked`). Ação explícita **Revelar** abre diálogo com finalidade + justificativa (10–500) e chama `POST .../identifiers/{id}/reveal`; o valor vive apenas em memória da tela                                            |
| CSP               | `middleware.ts`: `script-src 'self' 'nonce-…' 'strict-dynamic'`, `frame-ancestors 'none'`, `object-src 'none'`… (`style-src 'unsafe-inline'` por conta de atributos `style` do Radix/Next)                                                        |
| Cabeçalhos        | `next.config.ts`: HSTS, `X-Content-Type-Options`, `X-Frame-Options`, `Referrer-Policy`, `Permissions-Policy`, COOP/CORP                                                                                                                           |
| HTML              | Sem `dangerouslySetInnerHTML` (regra ESLint `react/no-danger`)                                                                                                                                                                                    |
| Laudos de exame   | "Abrir laudo" chama `GET …/results/{id}/document` com a finalidade corrente (gera `access_log` no core) e abre a URL assinada curta em nova aba; o resultado é uma mutação (nunca entra no cache). Valores de observações só para papéis clínicos |
| Regulação         | O SUS Nexus **não decide, não nega e não altera prioridade** (REG-009): a UI só registra pendências (`POST …/issues`) e organiza a fila; aviso explícito em `/regulacao/[id]`                                                                     |
| Lotação (UI)      | `cnes`/`teams`/`microareas` do token (`useUserScope`) só definem filtros padrão (UBS de referência, microárea do ACS). O escopo real é do OPA; o ACS ainda tem filtro defensivo na tela de busca ativa                                            |
| Hospital          | CID exibido **somente** se vier no payload (`principal_diagnosis_cid`); ausente ⇒ "Não informado (restrito pela política)" — nunca inferido. Risco vem do core com `risk_rule_version` (tooltip); a UI não recalcula                              |
| Protocolos        | `/admin/protocolos` só para `gestor`/`admin_municipal`/`admin` (usabilidade). Aprovar exige justificativa (10–500) e casos de teste: sem casos a UI bloqueia (botão desabilitado + alerta) e o core devolve 422                                   |
| Agentes           | Aprovação/rejeição de ações exige justificativa (10–1000) auditada (`HumanApprovalPanel`); kill switch restrito a `dpo`/`admin` com dupla confirmação (revisão + palavra CONFIRMAR). A autorização real é do ai-service (`admin_roles`)           |
| Logs              | Sem PII: o proxy loga apenas `correlation_id` e mensagem de erro                                                                                                                                                                                  |

### Acessibilidade (WCAG 2.1 AA)

- Tokens com contraste AA nos temas claro e escuro; foco visível de 4px (padrão gov.br); `prefers-reduced-motion`.
- Todos os campos têm rótulo visível (ou `sr-only`), descrição e erro associados por `aria-describedby`; erros em `role="alert"`.
- Diálogos (Radix) com foco preso, título/descrição, fechamento por Esc; navegação lateral com `aria-current`; _skip link_; tabelas semânticas e tabela virtualizada com roles ARIA e navegação por teclado.
- Testes automáticos com `vitest-axe` nos componentes principais e nas telas de busca.

## Como rodar

```bash
cd sus-nexus/web
pnpm install
pnpm generate            # regenera packages/api-client/src/generated/{core,ai}.d.ts a partir de ../contracts/openapi/{core-municipal,ai-service}.yaml
cp apps/shell/.env.example apps/shell/.env.local
pnpm dev                 # http://localhost:3000 — AUTH_MODE=mock + NEXT_PUBLIC_API_MOCK=true por padrão (sem backend)
```

Scripts raiz (Turborepo):

| Script                                    | O que faz                                                                                      |
| ----------------------------------------- | ---------------------------------------------------------------------------------------------- |
| `pnpm build`                              | `next build` do shell (output standalone)                                                      |
| `pnpm lint`                               | ESLint 9 (flat config, type-aware, jsx-a11y strict) em todos os pacotes                        |
| `pnpm typecheck`                          | `tsc --noEmit` em todos os pacotes (`next typegen` no shell)                                   |
| `pnpm test`                               | Vitest em todos os pacotes (Testing Library + axe + MSW)                                       |
| `pnpm generate`                           | Gera tipos dos OpenAPI (`openapi-typescript`): `generate:core` + `generate:ai` no `api-client` |
| `pnpm storybook`                          | Storybook 8 do design system (porta 6006)                                                      |
| `pnpm format`                             | Prettier                                                                                       |
| `pnpm --filter @sus-nexus/shell test:e2e` | Playwright (não faz parte de `pnpm test`)                                                      |

### Produção local com mocks (ex.: validar o standalone)

```bash
pnpm build
AUTH_MODE=mock AUTH_ALLOW_MOCK_IN_PRODUCTION=true \
NEXT_PUBLIC_API_MOCK=true ALLOW_API_MOCK_IN_PRODUCTION=true \
node apps/shell/.next/standalone/apps/shell/server.js
```

Em produção real **nunca** defina as variáveis `*_ALLOW_*`: `readAuthEnv` lança erro com `AUTH_MODE=mock` e o MSW é ignorado.

### Docker

```bash
# contexto = sus-nexus/ (o Dockerfile copia web/)
docker build -f web/apps/shell/Dockerfile -t sus-nexus-web .
docker run -p 3000:3000 --env-file web/apps/shell/.env.local sus-nexus-web
```

## Variáveis de ambiente (apps/shell)

| Variável                                    | Padrão                                              | Descrição                                                                                      |
| ------------------------------------------- | --------------------------------------------------- | ---------------------------------------------------------------------------------------------- |
| `AUTH_MODE`                                 | `keycloak`                                          | `mock` para desenvolvimento sem Keycloak                                                       |
| `AUTH_SECRET`                               | —                                                   | Segredo do Auth.js (obrigatório em `keycloak`)                                                 |
| `AUTH_URL`                                  | —                                                   | URL pública do shell (ex.: `http://localhost:3000`)                                            |
| `AUTH_KEYCLOAK_ID` / `AUTH_KEYCLOAK_SECRET` | —                                                   | Cliente OIDC confidencial                                                                      |
| `AUTH_KEYCLOAK_ISSUER`                      | `http://localhost:8180/realms/sus-nexus`            | Issuer do realm                                                                                |
| `AUTH_MOCK_USER` / `AUTH_MOCK_EMAIL`        | `Maria Operadora (mock)`                            | Usuário fake                                                                                   |
| `AUTH_MOCK_ROLES`                           | `cadastrador,operador_integracao,enfermagem,gestor` | Papéis do usuário fake (vírgula)                                                               |
| `AUTH_MOCK_MUNICIPALITY`                    | `ibge_3143302`                                      | `municipality_id` do usuário fake                                                              |
| `AUTH_MOCK_CNES`                            | `2126672`                                           | Lotação fake (claim `cnes`, vírgula) — UBS padrão                                              |
| `AUTH_MOCK_TEAMS`                           | —                                                   | Equipes (INE) fake (claim `teams`, vírgula)                                                    |
| `AUTH_MOCK_MICROAREAS`                      | —                                                   | Microáreas fake (claim `microareas`; ex. ACS `01,02`)                                          |
| `AUTH_SECURE_COOKIES`                       | `false` (true em produção)                          | Força cookies `Secure`                                                                         |
| `CORE_API_URL`                              | `http://localhost:8080`                             | Base do core municipal (somente servidor)                                                      |
| `AI_SERVICE_URL`                            | `http://localhost:8090`                             | Base do ai-service (somente servidor; proxy `/api/ai`)                                         |
| `NEXT_PUBLIC_API_MOCK`                      | `false`                                             | `true` ativa o MSW no servidor (core, ai-service, FHIR Gateway e Trino)                        |
| `FHIR_GATEWAY_URL`                          | `http://localhost:8081`                             | Base do FHIR Gateway (somente servidor; proxy `/api/fhir/r4`)                                  |
| `TRINO_URL`                                 | `http://localhost:8088`                             | API HTTP do Trino (Sala de Situação; somente servidor)                                         |
| `TRINO_USER`                                | `sus-nexus-web`                                     | Usuário de serviço do grupo `bi` no Trino (precisa constar em `groups.txt`/provedor de grupos) |
| `TRINO_CATALOG`                             | `iceberg`                                           | Catálogo do lakehouse                                                                          |
| `TRINO_PASSWORD`                            | —                                                   | Senha opcional do usuário de serviço (Basic, HTTPS)                                            |
| `METABASE_URL`                              | —                                                   | Link opcional para o painel "Sala de Situação" no Metabase                                     |

Papéis conhecidos (`ROLES` em `@sus-nexus/auth`): `acs`, `enfermagem`, `medico`, `cadastrador`, `regulador`, `operador_integracao`, `auditor`, `gestor`, `dpo`, `admin` e, do realm, `profissional_aps`, `profissional_hospitalar`, `admin_municipal`. Vêm de `realm_access.roles` / `resource_access.<client>.roles` do token Keycloak. A lotação vem das claims `cnes`, `teams`, `microareas` (`extractScope`) e é exposta em `session.user`.

## Convenções

- **Contrato primeiro**: `contracts/openapi/core-municipal.yaml` e `contracts/openapi/ai-service.yaml` são a fonte única; `pnpm generate` após qualquer mudança e commit dos arquivos gerados (`src/generated/core.d.ts`, `src/generated/ai.d.ts`).
- **Hooks do core** (`@sus-nexus/api-client`): `useCitizenSearch`, `useCitizen`, `useCitizenSummary`, `useTimeline`, `useRevealIdentifier`, `useMergeCases`, `useMergeCase`, `useMergeCaseDecision`, `useUnmerge`, `useTasks`, `useTask`, `useTransitionTask`, `useCreateTask`, `useConnectors`, `useIntegrationMessages`, `useIntegrationMessage`, `useReprocessMessage`, `useDeadLetters`, `useReconciliation`, `useAppointments`, `useHealthUnits`, `useCodes`, `useAccessLog`, `useRuleSets`, `useRegulationRequests`, `useRegulationRequest`, `useAddRegulationIssue`, `useProviderCapacity`, `useRegulationQueueSummary`, `useExamOrders`, `useExamOrder`, `useExamResultDocument` (mutação), **Fase 3**: `useHospitalEpisodes`, `useHospitalEpisode`, `useRegisterDischargeFollowup`, `useCarePlans`, `useCarePlan`, `useCreateCarePlan`, `useUpdateCarePlanItem`, `useCloseCarePlan`, `useCareGaps`, `useResolveCareGap`, `useProtocols`, `useCreateProtocolVersion`, `useTransitionProtocol`. Chaves de cache em `coreKeys` (`hospital*`, `carePlans`, `carePlan`, `careGaps`, `protocols`); as mutações invalidam episódios/tarefas/lacunas/planos e o resumo do cidadão.
- **Cliente `ai`** (`createAiClient`, `useAiClient`): `useAgents`, `useAgentTools`, `useAgentRuns`, `useAgentRun`, `useApprovals`, `useApproveAction`, `useRejectAction`, `useKillSwitch`, `useSetKillSwitch`. Chaves em `aiKeys`. Os tipos normalizados (`AgentRunRecord`, `ToolDescriptor`, `KillSwitchState`…) aplicam os defaults do Pydantic sobre os tipos gerados; `deriveAutonomy` classifica o agente pelas ferramentas de escrita concedidas.
- **Paginação por cursor** (`CursorPagination`), erros RFC 9457 (`CoreApiError` com `correlationId` exibido como código de suporte).
- **Estados explícitos** em toda consulta: carregando (`Skeleton`), vazio (`EmptyState`), erro (`ErrorState`), e na timeline: pendente / divergente / não sincronizado.
- **i18n**: strings em `apps/shell/src/i18n/pt-BR.ts` (objeto tipado `t`); rótulos de enums em `@sus-nexus/domain-components` (`labels.ts`).
- **Tailwind 4**: tokens em `packages/design-system/src/styles/tokens.css` (`@theme` + variáveis semânticas `--sn-*` com tema claro/escuro via `data-theme` ou `prefers-color-scheme`). O shell importa os tokens em `globals.css` e aponta `@source` para os pacotes.
- **Componentes de domínio** tipados pelos schemas do OpenAPI (`RegulationQueueSummaryCard` usa `RegulationQueueItem` do contrato; `CareGapCard` usa `CareGap` do contrato; `AgentDecisionTrace` recebe o `AgentRunRecord` adaptado por `features/agentes/runAdapter.ts`); onde o contrato ainda não tem schema (consentimento), o tipo é local ao pacote e deve migrar para o contrato quando ele evoluir.
- **Workbench de Cuidado** (`/cuidado`, abas com `?aba=`): **Tarefas** — tarefas da equipe/UBS agrupadas por tipo (`CARE_TASK_TYPES`), SLA, transições compartilhadas com `/tarefas` (`useTaskTransition`) e filtro por microárea resolvido a partir dos cidadãos das tarefas (limitação: o contrato de tarefas ainda não expõe `microarea`); **Busca ativa** — `GET /caregaps` por UBS (padrão = lotação), equipe, microárea, linha, tipo e atraso mínimo; dias de atraso, `contact_valid` e "Registrar desfecho" (`/resolve`). ACS (sem outro papel clínico) vê só as próprias microáreas; **Linhas de cuidado** — planos ativos por `care_line` com % de itens em dia/atrasados, lacunas mais antigas (`CareGapCard`) e cidadãos do plano.
- **Hospital** (`/hospital`, `/hospital/[id]`): visão da APS sobre sua população — filtros `reference_cnes` (padrão = UBS do usuário), status, período de alta, `followup_status` e risco (filtro local: o contrato não filtra por risco); destaque de risco com `risk_rule_version` em tooltip e reinternação ≤ 30 dias. O detalhe mostra `CitizenHeader`, movimentações, dados de alta (sem CID quando ausente), contrarreferência (metadados) e o painel pós-alta (tarefa vinculada com `TaskSLAIndicator`, desfecho via `/followup`).
- **Plano de cuidado do cidadão** (`/cidadaos/[id]?aba=plano`): planos com itens/status, "Atualizar" item (`/items/{itemId}`), criar plano a partir de protocolo **vigente** com pré-filtro de elegibilidade (sexo/idade — a decisão final é do core) e encerrar com motivo (5–500). O resumo ganhou cartões de última alta, linhas de cuidado, lacunas e contato válido.
- **Protocolos** (`/admin/protocolos`): versões por linha de cuidado com a vigente em destaque; editor de nova versão (itens: tipo, título, código, `due_in_days`, `periodicity_days`, `gap_after_days`, prioridade; casos de teste em JSON validado como lista de objetos) e ciclo `submit → approve → activate` (+ `revoke`) com justificativa; ativar revoga a vigente anterior (comportamento do mock).
- **Home**: seção "Para o seu papel" (`FEATURED_SHORTCUTS`): `acs`/APS → `/cuidado?aba=busca-ativa`; `gestor`/`admin_municipal` → `/admin/protocolos`; `profissional_hospitalar` → `/hospital`.
- Commits: Conventional Commits em português (`feat(web): ...`).

### Dados sintéticos da Fase 3 (MSW)

`src/mocks/care-data.ts` deriva tudo dos cidadãos de `data.ts` (gerador próprio, determinístico): 3 protocolos vigentes (`pre_natal` 2.1.0, `hipertensao` 1.4.0, `diabetes` 1.2.0) + versões aprovada (pré-natal 2.2.0), em revisão **sem casos de teste** (HAS 1.5.0), rascunho (DM 1.3.0) e revogada; planos de gestante (só mulheres em idade fértil), hipertensão e diabetes com itens realizados/agendados/atrasados; lacunas por item atrasado (UBS/equipe/microárea do cidadão, `contact_valid` a partir do cadastro) + perda de seguimento; 10 episódios hospitalares com risco alto/médio/baixo, uma reinternação ≤ 30 dias sem CID, um internado sem risco calculado e **um pós-alta sem contato no SLA de 72 h** (tarefa vencida + lacuna `post_discharge_no_contact`). Os handlers (`care-handlers.ts`) são stateful: o desfecho pós-alta conclui/escala a tarefa e resolve a lacuna, atualizar item resolve a lacuna vinculada, e o resumo do cidadão é recalculado.

### Sala de Situação (`/situacao`, Fase 4 — S26)

- **Fonte**: agregados gold pseudonimizados do dbt no Trino — `marts_aggregated.agg_indicadores_mensais`,
  `agg_care_gaps_monthly`, `agg_hospital_monthly`, `agg_regulation_queue_current` e `marts.dim_health_unit` /
  `marts.dim_territory`. **Nunca** `marts_identified`, bronze, staging ou silver.
- **BFF** `GET /api/situacao/<indicadores|serie|ranking|territorios|capacidade|filtros>` (`lib/situacao/*`):
  `withAuth` (gestor, auditor, admin_municipal; admin), whitelist de visões e parâmetros (`competence` AAAAMM,
  `cnes` 7 dígitos, `team_ine` 10 dígitos, `indicator` ∈ códigos do `seed_metas_indicadores`, `care_line`
  `[a-z][a-z0-9_]`); parâmetro desconhecido/repetido → 400. SQL fixo enviado como _prepared statement_
  (`X-Trino-Prepared-Statement` + `EXECUTE … USING` com literais validados e escapados), seguindo `nextUri`
  (só no mesmo host, limite de páginas/tempo). O filtro `tenant_id = ?` usa **sempre** o `municipality_id`
  do token (o grupo `bi` do Trino não tem filtro de linha).
- **Supressão**: células `0 < n < 5` chegam nulas do dbt; o BFF normaliza para `{ value: null, suppressed: true }`
  (ou `is_suppressed`) e a tela mostra **"<5 (suprimido)"** — nunca zero; suprimidos ficam fora do ranking e
  da desigualdade.
- **Tela**: cartões por indicador com meta e semáforo (atingida = `is_on_target`; atenção = fora da meta até 10% de
  distância relativa; crítica = além disso; ícone + texto), série histórica (SVG + tabela), comparação entre
  unidades com desigualdade (maior × menor, diferença e razão), territórios (mapa esquemático SVG por unidade ×
  equipe + tabela; sem tiles externos), capacidade e risco (internações por hospital, fila do lakehouse e fila
  ao vivo do core `GET /api/v1/regulation/queues/summary`) e link opcional para o Metabase.
- **Análise assistida (IA)**: painel que chama `POST /agents/bi_situation_analyst/run` pelo proxy `/api/ai`
  (`useRunBiSituationAnalyst`; corpo `BiSituationInput` sem município). Mostra fora da meta, tendência,
  desigualdade, hipóteses (sempre rotuladas "Hipótese"), recomendações, limitações e fontes, com aviso de que a
  análise é gerada por IA a partir de dados agregados e exige revisão humana; `invalid_output` vira erro
  amigável e nada da saída é exibido. Oculto para papéis sem permissão.
- **Mocks**: `mocks/situacao-data.ts` (linhas com o schema dos modelos dbt, dois municípios), `mocks/trino-handlers.ts`
  (protocolo `/v1/statement` + `nextUri`, nega `marts_identified`), `mocks/bi-agent-handlers.ts`.

### Aba FHIR do cidadão (`/cidadaos/[id]?aba=fhir`)

- `FHIRResourceViewer` (`@sus-nexus/domain-components`): resumo legível por tipo (Patient, Encounter, Observation,
  DiagnosticReport, ServiceRequest, Appointment, CarePlan, DocumentReference, Task, Condition), JSON/árvore
  colapsável (`collapsibleRaw`) e `masking="identifiers"` (CPF/CNS mascarados por `system` RNDS/OID e por formato
  em qualquer texto). Utilitários sem React em `@sus-nexus/domain-components/fhir` (`maskFhirIdentifiers`,
  `summarizeFhirResource`).
- BFF `GET /api/fhir/r4/Patient/{id}/$everything` (`_type` whitelist, `_count` 1–200, `_cursor`): Bearer + `X-Tenant-Id`
  - `X-Purpose-Of-Use` (obrigatória) e mascaramento de CPF/CNS **antes** de responder ao navegador.
- Visível só para papéis clínicos/gestão (`medico`, `enfermagem`, `profissional_aps`, `profissional_hospitalar`,
  `gestor`, `admin_municipal`, `admin`).

## Testes

- `packages/design-system`: Button, Input, Select, Dialog, Form (zod), Pagination — com `axe`.
- `packages/domain-components`: CitizenHeader (nome social, máscara, revelar), HumanApprovalPanel (justificativa obrigatória), TimelineEvent (cadeia causa-efeito), FHIRResourceViewer (mascaramento), TaskSLAIndicator, CareGapCard (contrato, contato inválido, desfecho — com `axe`), utilitários de formatação.
- `packages/api-client`: interceptador de cabeçalhos, `unwrap` → `CoreApiError`, normalização/cliente do ai-service.
- `packages/auth`: `hasRole`, sanitização da sessão, leitura de env (inclusive lotação mock), decodificação de JWT/papéis/lotação (`extractScope`).
- `apps/shell`: proxies BFF core e ai (Bearer, tenant, finalidade, rotas permitidas, 502), handlers MSW (finalidade obrigatória, reveal, pendências, laudo, aprovações, kill switch), máscaras, busca de cidadão, decisão de fusão, cockpit e detalhe de regulação (REG-009), exames (stepper, laudo por URL assinada, papéis não clínicos), workbench de cuidado, cockpit de agentes (aprovação com justificativa), kill switch (restrição de papel + dupla confirmação) — todos com `axe`. **Fase 3**: lista hospitalar com filtro de risco e UBS de lotação, detalhe sem CID inferido, registro de desfecho pós-alta (`Hospital.test.tsx`); busca ativa restrita à microárea do ACS, filtro por microárea, resolução de lacuna e linhas de cuidado (`ActiveSearch.test.tsx`); atualizar item/encerrar/criar plano e cartões do resumo (`CarePlans.test.tsx`); protocolos — acesso por papel, aprovação sem casos de teste bloqueada, ciclo submit→approve→activate, editor com JSON (`Protocols.test.tsx`); handlers MSW da Fase 3 (`careHandlers.test.ts`) e atalhos por papel (`nav.test.ts`) — telas com `axe`.
- E2E (`apps/shell/e2e`): smoke com Playwright, executado separadamente.
