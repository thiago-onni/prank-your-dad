# SUS Nexus — Web

Monorepo do frontend do SUS Nexus: **Turborepo + pnpm 10**, Node 22, Next.js 15 (App Router), React 19, TypeScript 5 `strict`, Tailwind CSS 4, Radix UI, TanStack Query v5, Vitest + Testing Library + axe, Storybook 8, Playwright.

Este diretório segue as convenções de `../CONVENTIONS.md` (seção _TypeScript (web)_) e a seção 11 do `docs/sus-nexus/PLANO_IMPLEMENTACAO.md`.

## Arquitetura

```text
web/
├── apps/
│   └── shell/                      # Único app interno (Next.js 15) com módulos por rota
│       ├── src/app/                # App Router: /, /integracoes, /cadastro, /cidadaos/[id], /tarefas, placeholders
│       ├── src/app/api/core/[...path]/route.ts   # Proxy BFF → core (Bearer no servidor)
│       ├── src/app/api/auth/[...nextauth]/route.ts   # Auth.js (Keycloak) ou mock
│       ├── src/app/api/session/purpose/route.ts  # Cookie httpOnly com X-Purpose-Of-Use
│       ├── src/middleware.ts       # CSP com nonce + redirecionamento para /entrar
│       ├── src/features/           # integracoes | cadastro | cidadaos | tarefas
│       ├── src/mocks/              # MSW: dados sintéticos + handlers de todos os endpoints
│       ├── src/i18n/pt-BR.ts       # Strings centralizadas
│       └── Dockerfile              # output standalone, usuário non-root
└── packages/
    ├── design-system/              # @sus-nexus/design-system — tokens gov.br, componentes acessíveis, Storybook
    ├── domain-components/          # @sus-nexus/domain-components — os 15 componentes da especificação
    ├── api-client/                 # @sus-nexus/api-client — tipos gerados do OpenAPI + openapi-fetch + hooks TanStack Query
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

### Segurança

| Tema              | Implementação                                                                                                                                                                                                                                    |
| ----------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Autenticação      | Auth.js v5 + provider Keycloak; sessão JWT criptografada em cookie `httpOnly` / `Secure` / `SameSite=strict`; refresh do access token no servidor (`jwt` callback); `/api/auth/session` sanitizado (sem tokens); logout RP-initiated no Keycloak |
| Autorização na UI | Somente usabilidade (`hasRole`/`useHasRole`, itens de navegação por papel). A autorização real é no backend (OPA)                                                                                                                                |
| Finalidade (LGPD) | Seletor no cabeçalho → `POST /api/session/purpose` (cookie httpOnly) → proxy injeta `X-Purpose-Of-Use`; telas com dado de cidadão exigem finalidade selecionada                                                                                  |
| CPF/CNS           | Sempre mascarados (`value_masked`). Ação explícita **Revelar** abre diálogo com finalidade + justificativa (10–500) e chama `POST .../identifiers/{id}/reveal`; o valor vive apenas em memória da tela                                           |
| CSP               | `middleware.ts`: `script-src 'self' 'nonce-…' 'strict-dynamic'`, `frame-ancestors 'none'`, `object-src 'none'`… (`style-src 'unsafe-inline'` por conta de atributos `style` do Radix/Next)                                                       |
| Cabeçalhos        | `next.config.ts`: HSTS, `X-Content-Type-Options`, `X-Frame-Options`, `Referrer-Policy`, `Permissions-Policy`, COOP/CORP                                                                                                                          |
| HTML              | Sem `dangerouslySetInnerHTML` (regra ESLint `react/no-danger`)                                                                                                                                                                                   |
| Logs              | Sem PII: o proxy loga apenas `correlation_id` e mensagem de erro                                                                                                                                                                                 |

### Acessibilidade (WCAG 2.1 AA)

- Tokens com contraste AA nos temas claro e escuro; foco visível de 4px (padrão gov.br); `prefers-reduced-motion`.
- Todos os campos têm rótulo visível (ou `sr-only`), descrição e erro associados por `aria-describedby`; erros em `role="alert"`.
- Diálogos (Radix) com foco preso, título/descrição, fechamento por Esc; navegação lateral com `aria-current`; _skip link_; tabelas semânticas e tabela virtualizada com roles ARIA e navegação por teclado.
- Testes automáticos com `vitest-axe` nos componentes principais e nas telas de busca.

## Como rodar

```bash
cd sus-nexus/web
pnpm install
pnpm generate            # regenera packages/api-client/src/generated/core.d.ts a partir de ../contracts/openapi/core-municipal.yaml
cp apps/shell/.env.example apps/shell/.env.local
pnpm dev                 # http://localhost:3000 — AUTH_MODE=mock + NEXT_PUBLIC_API_MOCK=true por padrão (sem backend)
```

Scripts raiz (Turborepo):

| Script                                    | O que faz                                                               |
| ----------------------------------------- | ----------------------------------------------------------------------- |
| `pnpm build`                              | `next build` do shell (output standalone)                               |
| `pnpm lint`                               | ESLint 9 (flat config, type-aware, jsx-a11y strict) em todos os pacotes |
| `pnpm typecheck`                          | `tsc --noEmit` em todos os pacotes (`next typegen` no shell)            |
| `pnpm test`                               | Vitest em todos os pacotes (Testing Library + axe + MSW)                |
| `pnpm generate`                           | Gera tipos do OpenAPI (`openapi-typescript`)                            |
| `pnpm storybook`                          | Storybook 8 do design system (porta 6006)                               |
| `pnpm format`                             | Prettier                                                                |
| `pnpm --filter @sus-nexus/shell test:e2e` | Playwright (não faz parte de `pnpm test`)                               |

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

| Variável                                    | Padrão                                              | Descrição                                           |
| ------------------------------------------- | --------------------------------------------------- | --------------------------------------------------- |
| `AUTH_MODE`                                 | `keycloak`                                          | `mock` para desenvolvimento sem Keycloak            |
| `AUTH_SECRET`                               | —                                                   | Segredo do Auth.js (obrigatório em `keycloak`)      |
| `AUTH_URL`                                  | —                                                   | URL pública do shell (ex.: `http://localhost:3000`) |
| `AUTH_KEYCLOAK_ID` / `AUTH_KEYCLOAK_SECRET` | —                                                   | Cliente OIDC confidencial                           |
| `AUTH_KEYCLOAK_ISSUER`                      | `http://localhost:8180/realms/sus-nexus`            | Issuer do realm                                     |
| `AUTH_MOCK_USER` / `AUTH_MOCK_EMAIL`        | `Maria Operadora (mock)`                            | Usuário fake                                        |
| `AUTH_MOCK_ROLES`                           | `cadastrador,operador_integracao,enfermagem,gestor` | Papéis do usuário fake (vírgula)                    |
| `AUTH_MOCK_MUNICIPALITY`                    | `ibge_3143302`                                      | `municipality_id` do usuário fake                   |
| `AUTH_SECURE_COOKIES`                       | `false` (true em produção)                          | Força cookies `Secure`                              |
| `CORE_API_URL`                              | `http://localhost:8080`                             | Base do core municipal (somente servidor)           |
| `NEXT_PUBLIC_API_MOCK`                      | `false`                                             | `true` ativa o MSW no servidor (sem backend)        |

Papéis conhecidos (`ROLES` em `@sus-nexus/auth`): `acs`, `enfermagem`, `medico`, `cadastrador`, `regulador`, `operador_integracao`, `auditor`, `gestor`, `dpo`, `admin`. Vêm de `realm_access.roles` / `resource_access.<client>.roles` do token Keycloak.

## Convenções

- **Contrato primeiro**: `contracts/openapi/core-municipal.yaml` é a fonte única; `pnpm generate` após qualquer mudança e commit do arquivo gerado (`src/generated/core.d.ts`).
- **Hooks** (`@sus-nexus/api-client`): `useCitizenSearch`, `useCitizen`, `useCitizenSummary`, `useTimeline`, `useRevealIdentifier`, `useMergeCases`, `useMergeCase`, `useMergeCaseDecision`, `useUnmerge`, `useTasks`, `useTask`, `useTransitionTask`, `useCreateTask`, `useConnectors`, `useIntegrationMessages`, `useIntegrationMessage`, `useReprocessMessage`, `useDeadLetters`, `useReconciliation`, `useAppointments`, `useHealthUnits`, `useCodes`, `useAccessLog`, `useRuleSets`. Chaves de cache em `coreKeys`.
- **Paginação por cursor** (`CursorPagination`), erros RFC 9457 (`CoreApiError` com `correlationId` exibido como código de suporte).
- **Estados explícitos** em toda consulta: carregando (`Skeleton`), vazio (`EmptyState`), erro (`ErrorState`), e na timeline: pendente / divergente / não sincronizado.
- **i18n**: strings em `apps/shell/src/i18n/pt-BR.ts` (objeto tipado `t`); rótulos de enums em `@sus-nexus/domain-components` (`labels.ts`).
- **Tailwind 4**: tokens em `packages/design-system/src/styles/tokens.css` (`@theme` + variáveis semânticas `--sn-*` com tema claro/escuro via `data-theme` ou `prefers-color-scheme`). O shell importa os tokens em `globals.css` e aponta `@source` para os pacotes.
- **Componentes de domínio** tipados pelos schemas do OpenAPI; onde o contrato ainda não tem schema (lacunas de cuidado, fila de regulação, rastro de agentes, consentimento), o tipo é local ao pacote e deve migrar para o contrato quando ele evoluir.
- Commits: Conventional Commits em português (`feat(web): ...`).

## Testes

- `packages/design-system`: Button, Input, Select, Dialog, Form (zod), Pagination — com `axe`.
- `packages/domain-components`: CitizenHeader (nome social, máscara, revelar), HumanApprovalPanel (justificativa obrigatória), TimelineEvent (cadeia causa-efeito), FHIRResourceViewer (mascaramento), TaskSLAIndicator, utilitários de formatação.
- `packages/api-client`: interceptador de cabeçalhos, `unwrap` → `CoreApiError`.
- `packages/auth`: `hasRole`, sanitização da sessão, leitura de env, decodificação de JWT/papéis.
- `apps/shell`: proxy BFF (Bearer, tenant, finalidade, 502), handlers MSW (finalidade obrigatória, reveal), máscaras, busca de cidadão (MSW + axe), decisão de fusão.
- E2E (`apps/shell/e2e`): smoke com Playwright, executado separadamente.
